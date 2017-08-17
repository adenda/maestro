package com.adendamedia.kubernetes

import akka.actor._
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

import com.adendamedia.cornucopia.Library
import skuber._
import skuber.json.format._
import scala.concurrent.Future
import org.slf4j.LoggerFactory

/**
  * Actor responsible for polling the statefulset resource until new pods show up, and then messaging the child actor
  * to join these new nodes to the Redis cluster
  */
object Conductor {
  def props(k8sController: ActorRef, replicaCounter: StatefulsetReplicaCounterAgent) =
    Props(new Conductor(k8sController, replicaCounter))

  val name = "conductor"

  case class Update(currentNodeIps: List[String], newReplicaCount: Int)
  case class Poll(currentNodeIps: List[String], newReplicaCount: Int)
  case class RemoveNodes(nodes: List[String])
}

class Conductor(k8sController: ActorRef, replicaCounter: StatefulsetReplicaCounterAgent) extends Actor with ActorLogging {
  import Conductor._
  import Cluster._
  import context._

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
  private val pollingPeriod = k8sConfig.getInt("conductor.polling-period")

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val k8s = k8sInit

  private val pollingPeriodSeconds = pollingPeriod seconds

  private val library = new Library
  private val cluster = context.system.actorOf(Cluster.props(library.ref, k8sController), Cluster.name)

  // TODO: We might want to have a timeout period after which the conductor actor simply fails

  override def preStart() = {}

  override def postRestart(reason: Throwable) = {}

  def receive = {
    case Update(currentNodeIps: List[String], newReplicaCount) =>
      system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(currentNodeIps, newReplicaCount))
    case Poll(currentNodeIps: List[String], newReplicaCount) =>
      logger.info(s"Polling redis cluster for new nodes to join: current replica count=${replicaCounter.getReplicaCount().counter}, new replica count=$newReplicaCount")
      pollForNewPods(currentNodeIps, newReplicaCount)
    case RemoveNodes(nodes: List[String]) =>
      removeNodes(nodes)
  }

  private def removeNodes(uris: List[String]) = {
    log.info(s"Telling Cluster to remove nodes: ${uris.mkString(",")}")
    uris foreach(cluster ! Remove(_))
  }

  def pollForNewPods(previousRedisIps: List[String], newReplicaCount: Int): Future[Unit] = {
    // TODO: parameterize label selector in application.conf
    val pods: Future[PodList] = k8s list[PodList] LabelSelector(LabelSelector.IsEqualRequirement("app", statefulSetName))
    pods map {
      p =>
        val redisIps: List[Option[String]] = p.items map {
          pod => pod.status.get.podIP
        }

        val newRedisIps: List[String] = for {
          pod <- redisIps
          if pod.isDefined
        } yield pod.get

        if (newRedisIps.length > previousRedisIps.length) {
          val newRedisIp = newRedisIps.toSet -- previousRedisIps.toSet
          for (ip <- newRedisIp) joinNewNode(ip, newRedisIps.toSet, newReplicaCount)
        }
        else if (replicaCounter.getReplicaCount().counter < newReplicaCount) {
          system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(newRedisIps, newReplicaCount))
        }
        else {
          logger.info("Successfully polled all new redis nodes to join cluster: we are done here.")
        }
    }
  }

  def joinNewNode(ip: String, newRedisIps: Set[String], newReplicaCount: Int) = {
    logger.info(s"Found a new redis node to join redis cluster with cluster IP address '$ip'")

    cluster ! Join(ip)

    replicaCounter.incrementReplicaNumber

    if (replicaCounter.getReplicaCount().counter < newReplicaCount) {
      system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(newRedisIps.toList, newReplicaCount))
    } else {
      logger.info("Successfully polled all new redis nodes to join cluster: we are done here.")
    }
  }

}

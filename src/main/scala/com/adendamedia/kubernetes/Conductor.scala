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
  def props(k8sController: ActorRef) = Props(new Conductor(k8sController))

  case class Update(currentNodeIps: List[String], currentReplicaCount: Int, newReplicaCount: Int)
  case class Poll(currentNodeIps: List[String], currentReplicaCount: Int, newReplicaCount: Int)
}

class Conductor(k8sController: ActorRef) extends Actor {
  import Conductor._
  import Cluster._
  import context._

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
  private val pollingPeriod = k8sConfig.getInt("conductor.polling-period")

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val k8s = k8sInit

  private val pollingPeriodSeconds = pollingPeriod seconds

  private val cluster = context.system.actorOf(Cluster.props(Library.ref, k8sController))

  // TO-DO: We might want to have a timeout period after which the conductor actor simply fails

  override def preStart() = {}

  override def postRestart(reason: Throwable) = {}

  def receive = {
    case Update(currentNodeIps: List[String], currentReplicaCount, newReplicaCount) =>
      logger.info(s"Polling redis cluster for new nodes to join: current replica count: ${currentReplicaCount}, new replica count: ${newReplicaCount}")
      system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(currentNodeIps, currentReplicaCount, newReplicaCount))
    case Poll(currentNodeIps: List[String], currentReplicaCount, newReplicaCount) =>
      logger.info(s"Polling redis cluster again for new nodes to join: current replica count: ${currentReplicaCount}, new replica count: ${newReplicaCount}")
      pollForNewPods(currentNodeIps, currentReplicaCount, newReplicaCount)
  }

  def pollForNewPods(previousRedisIps: List[String], currentReplicaCount: Int, newReplicaCount: Int) = {
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
          for (ip <- newRedisIp) joinNewNode(ip, newRedisIps.toSet, currentReplicaCount + 1, newReplicaCount)
        }
        else system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(newRedisIps, currentReplicaCount, newReplicaCount))
    }
  }

  def joinNewNode(ip: String, newRedisIps: Set[String], currentReplicaCount: Int, newReplicaCount: Int) = {
    logger.info(s"Found a new redis node to join redis cluster with cluster IP address '$ip'")

    cluster ! Join(ip)

    if (currentReplicaCount < newReplicaCount) {
      system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(newRedisIps.toList, currentReplicaCount, newReplicaCount))
    } else {
      logger.info("Successfully polled all new redis nodes to join cluster: we are done here.")
    }
  }

}

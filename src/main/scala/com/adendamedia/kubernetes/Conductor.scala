package com.adendamedia.kubernetes

import akka.actor._
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

import com.github.kliewkliew.cornucopia.Library
import com.github.kliewkliew.cornucopia.actors.CornucopiaSource.Task
import skuber._
import skuber.json.format._
import scala.concurrent.Future
import org.slf4j.LoggerFactory

/**
  * Actor responsible for polling the statefulset resource until new pods show up, and then messaging the child actor
  * to join these new nodes to the Redis cluster
  */
object Conductor {
  def props = Props(new Conductor)

  case class Update(currentNodeIps: List[String], currentReplicaCount: Int, newReplicaCount: Int)
  case class Poll(currentNodeIps: List[String], currentReplicaCount: Int, newReplicaCount: Int, k8sController: ActorRef)
}

class Conductor extends Actor {
  import Conductor._
  import Cluster._
  import context._

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
  private val pollingPeriod = k8sConfig.getInt("conductor.polling-period")

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val k8s = k8sInit

  private val pollingPeriodSeconds = pollingPeriod seconds

  private val cluster = context.system.actorOf(Cluster.props(Library.ref))

  // TO-DO: We might want to have a timeout period after which the conductor actor simply fails

  override def preStart() = {}

  override def postRestart(reason: Throwable) = {}

  def receive = {
    case Update(currentNodeIps: List[String], currentReplicaCount, newReplicaCount) =>
      logger.info(s"Polling redis cluster for new nodes to join: current replica count: ${currentReplicaCount}, new replica count: ${newReplicaCount}")
      val k8sController = sender // we keep track of parent to inform it once the resharding is completed
      system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(currentNodeIps, currentReplicaCount, newReplicaCount, k8sController))
    case Poll(currentNodeIps: List[String], currentReplicaCount, newReplicaCount, k8sController: ActorRef) =>
      logger.info(s"Polling redis cluster again for new nodes to join: current replica count: ${currentReplicaCount}, new replica count: ${newReplicaCount}")
      pollForNewPods(currentNodeIps, currentReplicaCount, newReplicaCount, k8sController)
  }

  def pollForNewPods(previousRedisIps: List[String], currentReplicaCount: Int, newReplicaCount: Int, k8sController: ActorRef) = {
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
          for (ip <- newRedisIp) joinNewNode(ip, newRedisIps.toSet, currentReplicaCount + 1, newReplicaCount, k8sController)
        }
        else system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(newRedisIps, currentReplicaCount, newReplicaCount, k8sController))
    }
  }

  def joinNewNode(ip: String, newRedisIps: Set[String], currentReplicaCount: Int, newReplicaCount: Int, k8sController: ActorRef) = {
    logger.info(s"Found a new redis node to join redis cluster with cluster IP address '$ip'")

    cluster ! Join(ip, k8sController)

    if (currentReplicaCount < newReplicaCount) {
      system.scheduler.scheduleOnce(pollingPeriodSeconds, self, Poll(newRedisIps.toList, currentReplicaCount, newReplicaCount, k8sController))
    } else {
      logger.info("Successfully polled all new redis nodes to join cluster: we are done here.")
    }
  }

}

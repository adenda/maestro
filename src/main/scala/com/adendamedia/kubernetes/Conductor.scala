package com.adendamedia.kubernetes

import akka.actor._
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

import skuber._
import skuber.json.format._
import scala.concurrent.Future
import org.slf4j.LoggerFactory

/**
  * Actor responsible for polling the stateful set resource until new pods show up, and then messaging the child actor
  * to join these new nodes to the Redis cluster
  */
object Conductor {
  def props = Props(new Conductor)

  case class Update(currentNodeIps: List[String], currentReplicaCount: Int, newReplicaCount: Int)
  case class Poll(currentNodeIps: List[String], currentReplicaCount: Int, newReplicaCount: Int)

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
}

class Conductor extends Actor {
  import Conductor._
  import Cluster._
  import context._

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val k8s = k8sInit

  // TO-DO: Make these configuration parameters
  private val pollingPeriod = 20 seconds

  private val cluster = context.system.actorOf(Cluster.props)

  // TO-DO: We might want to have a timeout period after which the conductor actor simply fails

  override def preStart() = {}

  override def postRestart(reason: Throwable) = {}

  def receive = {
    case Update(currentNodeIps: List[String], currentReplicaCount, newReplicaCount) =>
      logger.info(s"Polling redis cluster for new nodes to join: current replica count: ${currentReplicaCount}, new replica count: ${newReplicaCount}")
      system.scheduler.scheduleOnce(pollingPeriod, self, Poll(currentNodeIps, currentReplicaCount, newReplicaCount))
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
        else system.scheduler.scheduleOnce(pollingPeriod, self, Poll(newRedisIps, currentReplicaCount, newReplicaCount))
    }
  }

  def joinNewNode(ip: String, newRedisIps: Set[String], currentReplicaCount: Int, newReplicaCount: Int) = {
    logger.info(s"Found a new redis node to join redis cluster with cluster IP address '$ip'")

    cluster ! Join(ip)

    if (currentReplicaCount < newReplicaCount) {
      system.scheduler.scheduleOnce(pollingPeriod, self, Poll(newRedisIps.toList, currentReplicaCount, newReplicaCount))
    } else {
      logger.info("Successfully polled all new redis nodes to join cluster: we are done here.")
    }
  }

}

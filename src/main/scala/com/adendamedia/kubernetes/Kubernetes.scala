package com.adendamedia.kubernetes

import akka.actor._
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import skuber._
import skuber.apps.{StatefulSet, _}
import skuber.json.apps.format._
import skuber.json.format._

import scala.concurrent.Future

object Kubernetes {
  def props = Props(new Kubernetes)

  case object ScaleUp

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
}

class Kubernetes extends Actor {
  import Conductor._
  import Kubernetes._

  import scala.concurrent.ExecutionContext.Implicits.global
  private val k8s = k8sInit

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val conductor = context.system.actorOf(Conductor.props)

  def receive = {
    case ScaleUp => scaleUp
  }

  def scaleUp = {
    // First get the number of replicas for the stateful set. Second, get the list of current pods in the statefulset.
    // Third, message the Conductor child actor with the current number of pods and the list of the current pod cluster
    // IP addresses, and the expected new number of pods. The Conductor actor is responsible for awaiting the addition
    // of these new Redis nodes and then joining them to the Redis cluster.
    val resource: Future[StatefulSet] = k8s get[StatefulSet] statefulSetName
    val pods: Future[PodList] = k8s list[PodList] LabelSelector(LabelSelector.IsEqualRequirement("app", statefulSetName))

    val result: Future[(List[String], Int, StatefulSet)] = for {
      r <- resource
      p <- pods
      currentReplicas = r.spec.get.replicas
    } yield {
      val currentRedisIps = p.items map {
        pod => pod.status.get.podIP.get
      }
      (currentRedisIps, currentReplicas, r)
    }

    result recoverWith {
      case ex: Throwable =>
        logger.error(s"Could not get resource '$statefulSetName' to scale: ${ex.toString}")
        Future(Unit)
    }

    val updateResult = for {
      (currentRedisIps, currentReplicas, ss) <- result
      newReplicas = currentReplicas + 2
      newSS = ss.copy(spec = Some(ss.spec.get.copy(replicas = newReplicas)))
      _ <- k8s update newSS
    } yield {
      conductor ! Update(currentRedisIps, currentReplicas, newReplicas)
    }

    updateResult recoverWith {
      case ex: Throwable =>
        logger.error(s"Could not update resource '$statefulSetName': ${ex.toString}")
        Future(Unit)
    }

  }

}

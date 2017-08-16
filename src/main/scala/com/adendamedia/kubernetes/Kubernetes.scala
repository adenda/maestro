package com.adendamedia.kubernetes

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import skuber._
import skuber.apps.{StatefulSet, _}
import skuber.json.apps.format._
import skuber.json.format._
import com.adendamedia.EventBus

import scala.concurrent.Future
import scala.concurrent.duration._

object Kubernetes {
  def props(eventBus: ActorRef): Props = Props(new Kubernetes(eventBus))

  case object ScaleUp
  case class ScaleUpSuccess(taskKey: String, uri: String)

  case object GetRedisURIs

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
  private val newNodesNumber = k8sConfig.getInt("new-nodes-number")
}

class Kubernetes(eventBus: ActorRef) extends Actor {
  import Conductor._
  import Kubernetes._
  import EventBus._

  private val replicaCounter = new StatefulsetReplicaCounterAgent(context.system)

  private val scaleMagnitudeCounter = new ScaleCounterAgent(context.system)

  import scala.concurrent.ExecutionContext.Implicits.global // TODO: use actor system context?

  private val k8s = k8sInit

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val conductor = context.system.actorOf(Conductor.props(self, replicaCounter))

  private var scaleCounter = 0

  implicit val timeout = Timeout(60 seconds) // TODO: parameterize in application.conf

  def receive = {
    case ScaleUp =>
      if (scaleMagnitudeCounter.getScaleMagnitude().counter == 0) scaleUp
      else logger.warn(s"Kubernetes actor asked to scale, but scale is already underway, so ignoring scale up request for now")
    case ScaleUpSuccess(taskKey, uri) =>
      logger.info(s"Received scale up success for task $taskKey node with uri $uri")
      handleScaleUpSuccess(uri)
    case GetRedisURIs =>
      logger.debug("Asked for redis URIs")
      val ref = sender
      getRedisUris map(ref ! _)
  }

  private def handleScaleUpSuccess(uri: String) = {
    // We should first add the new uri to the list of connections that are sampled, using an ask so we can wait for it
    // to be added using a future. Then we should reset the memory scale sampler. Then we should finally decrement the
    // scale counter in this class
    val f: Future[String] = eventBus.ask(AddRedisNode(uri)).mapTo[String]

    f map { uri: String =>
      logger.info(s"Succcessfully added new Redis node with uri '$uri' to list of sampled nodes")
      scaleMagnitudeCounter.adjustScale(-1)
    }
  }

  private def getRedisUris: Future[List[String]] = {
    // TODO: parameterize label selector in application.conf
    val pods: Future[PodList] = k8s list[PodList] LabelSelector(LabelSelector.IsEqualRequirement("app", statefulSetName))
    val result: Future[List[String]] = pods map { podList =>
      for(pod <- podList) yield pod.status.get.podIP.get
    }
    result
  }

  private def scaleUp = {
    scaleMagnitudeCounter.adjustScale(newNodesNumber)

    // First get the number of replicas for the stateful set. Second, get the list of current pods in the statefulset.
    // Third, message the Conductor child actor with the list of the current Redis pod cluster IP addresses, and the
    // expected new number of pods. The Conductor actor is responsible for awaiting the addition of these new Redis
    // nodes and then joining them to the Redis cluster.
    val resource: Future[StatefulSet] = k8s get[StatefulSet] statefulSetName
    // TODO: parameterize label selector in application.conf
    val pods: Future[PodList] = k8s list[PodList] LabelSelector(LabelSelector.IsEqualRequirement("app", statefulSetName))

    val result: Future[(List[String], Int, StatefulSet)] = for {
      r <- resource
      p <- pods
      currentReplicas = r.spec.get.replicas.get
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

    // Scale up the stateful set by configured number of nodes (default will be 2 nodes: 1 master and 1 slave)
    val updateResult = for {
      (currentRedisIps, currentReplicas, ss) <- result
      newReplicas = currentReplicas + newNodesNumber
      newSS = ss.copy(spec = Some(ss.spec.get.copy(replicas = Some(newReplicas))))
      _ <- k8s update newSS
    } yield {
      replicaCounter.setReplicaNumber(currentReplicas)

      // ask Conductor child actor to add expected new redis nodes to cluster. Scale up messages will be ignored until
      // this actor is informed by its descendants that resharding was successful using `ScaleUpSuccess` message.
      conductor ! Update(currentRedisIps, newReplicas)
    }

    updateResult recoverWith {
      case ex: Throwable =>
        logger.error(s"Could not update resource '$statefulSetName': ${ex.toString}")
        Future(Unit)
    }

  }

}

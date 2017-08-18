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

  val name = "kubernetes"

  case object ScaleUp
  case object ScaleDown
  case class ScaleUpSuccess(taskKey: String, uri: String)
  case class ScaleDownSuccess(taskKey: String, uri: String)
  case object ResetKubernetes

  case object GetRedisURIs

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
  private val newNodesNumber = k8sConfig.getInt("new-nodes-number")
  private val retiredNodesNumber = k8sConfig.getInt("retired-nodes-number")
  private val minimumClusterSize = k8sConfig.getInt("minimum-cluster-size")
  private val scaleDownBackoffTime = k8sConfig.getInt("scale-down-backoff-time")
}

class Kubernetes(eventBus: ActorRef) extends Actor with ActorLogging {
  import Conductor._
  import Kubernetes._
  import EventBus._

  private val replicaCounter = new StatefulsetReplicaCounterAgent(context.system)

  private val scaleMagnitudeCounter = new ScaleCounterAgent(context.system)

  import scala.concurrent.ExecutionContext.Implicits.global // TODO: use actor system context?

  private val k8s = k8sInit

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val conductor = context.system.actorOf(Conductor.props(self, replicaCounter), Conductor.name)

  private var isScalingDown = false

  implicit val timeout = Timeout(60 seconds) // TODO: parameterize in application.conf

  def receive = {
    case ScaleUp =>
      if (scaleMagnitudeCounter.getScaleMagnitude().counter == 0) scaleUp
      else log.warning(s"Kubernetes actor asked to scale up, but scale is already underway, so ignoring scale up request for now")
    case ScaleUpSuccess(taskKey, uri) =>
      log.info(s"Received scale up success for task $taskKey node with uri $uri")
      handleScaleUpSuccess(uri)
    case ScaleDown =>
      if (scaleMagnitudeCounter.getScaleMagnitude().counter == 0 && !isScalingDown) scaleDown
      else log.warning(s"Kubernetes actor asked to scale down, but scale is already underway, so ignoring scale down request for now")
    case ScaleDownSuccess(taskKey, uri) =>
      log.info(s"Received scale down success for task $taskKey node with uri $uri")
      handleScaleDownSuccess(uri)
    case GetRedisURIs =>
      log.debug("Asked for redis URIs")
      val ref = sender
      getRedisUris map(ref ! _)
    case ResetKubernetes =>
      log.info(s"Resetting controller")
      isScalingDown = false
      scaleMagnitudeCounter.reset()
  }

  private def handleScaleUpSuccess(uri: String) = {
    // We should first add the new uri to the list of connections that are sampled, using an ask so we can wait for it
    // to be added using a future. Then we should reset the memory scale sampler. Then we should finally decrement the
    // scale counter in this class
    val f: Future[String] = eventBus.ask(AddRedisNode(uri)).mapTo[String]

    f map { uri: String =>
      log.info(s"Succcessfully added new Redis node with uri '$uri' to list of sampled nodes")
      scaleMagnitudeCounter.adjustScale(-1)
    }
  }

  private def handleScaleDownSuccess(uri: String) = {
    scaleMagnitudeCounter.adjustScale(-1)
    val f: Future[String] = eventBus.ask(RemoveRedisNode(uri)).mapTo[String]

    f map { uri: String =>
      log.info(s"Successfully removed retired Redis node with uri '$uri' from list of sampled nodes")
      log.info(s"Scale magnitude = ${scaleMagnitudeCounter.getScaleMagnitude().counter}")

      if (scaleMagnitudeCounter.getScaleMagnitude().counter == 0) {
        log.info(s"Scaling down statefulset $statefulSetName now")
        scaleDownStatefulSet
      }
    }

  }

  private def scaleDownStatefulSet = {
    val resource: Future[StatefulSet] = k8s get[StatefulSet] statefulSetName

    resource.flatMap { ss =>
      val currentReplicas = ss.spec.get.replicas.get
      val newReplicas = currentReplicas - retiredNodesNumber
      val newSS = ss.copy(spec = Some(ss.spec.get.copy(replicas = Some(newReplicas))))
      k8s update newSS map { _ =>
        log.info(s"Statefulset is scaled down from $currentReplicas replicas to $newReplicas replicas")
        context.system.scheduler.scheduleOnce(scaleDownBackoffTime.seconds)(self ! ResetKubernetes)
      }
    } recover {
      case e =>
        log.error(s"There was an error trying to scale down statefulset $statefulSetName: {}", e)
        throw e
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

  private def scaleDown = {
    scaleMagnitudeCounter.adjustScale(retiredNodesNumber)
    isScalingDown = true

    // First get a list of all pods in the statefulset. Filter out the `retiredNodesNumber`-th largest pods that have
    // the highest pod numbers. These pods should be removed from the cluster using Cornucopia. When they are success-
    // fully forgotten from the cluster, then the `ScaleDownSuccess` message is sent back to this actor. Once
    // `retiredNodesNumber` scaleDownSuccess messages are received in this actor, scale down the statefulset by
    // `retiredNodesNumber` pods.

    val pods: Future[PodList] =
      k8s list[PodList] LabelSelector(LabelSelector.IsEqualRequirement("app", statefulSetName))

    val podsWithNumber: Future[List[(Pod, Int)]] = pods map { podList =>
      podList map { pod =>
        val generateName = pod.metadata.generateName
        val name = pod.metadata.name
        val number = name.split(generateName)(1).toInt
        (pod, number)
      }
    } recover {
      case e: java.lang.ArrayIndexOutOfBoundsException =>
        log.error(s"Error getting pod number to scale down with: {}", e)
        throw e
    }

    podsWithNumber map { podList =>
      if (podList.size > minimumClusterSize) {
        val podsToRemove = podList.sortBy(- _._2).take(retiredNodesNumber)
        val uris = podsToRemove.map { case (pod: Pod, _: Int) =>
          pod.status.get.podIP.get
        }
        log.info(s"Sending message to conductor to remove nodes from cluster: ${uris.mkString(", ")}")
        conductor ! RemoveNodes(uris)
      } else {
        log.warning(s"Not scaling down cluster because it is currently at minimum size of $minimumClusterSize nodes")
        self ! ResetKubernetes
        eventBus ! ResetMemoryScale
      }
    }

  }

}

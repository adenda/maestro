package com.adendamedia.kubernetes

import akka.actor._
import org.slf4j.LoggerFactory
import com.lambdaworks.redis.RedisURI
import com.adendamedia.cornucopia.actors.Gatekeeper.{Task, TaskAccepted, TaskDenied}

object CornucopiaTask {
  def props(cornucopiaRef: ActorRef, k8sController: ActorRef, cluster: ActorRef) =
    Props(new CornucopiaTask(cornucopiaRef, k8sController, cluster))

  trait Task
  case class AddMasterTask(ip: String) extends Task
  case class AddSlaveTask(ip: String) extends Task
  case class RemoveMasterTask(ip: String) extends Task
  case class RemoveSlaveTask(ip: String) extends Task
}

class CornucopiaTask(cornucopiaRef: ActorRef, k8sController: ActorRef, cluster: ActorRef) extends Actor with ActorLogging {
  import CornucopiaTask._
  import Kubernetes._

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def buildRedisUri(ip: String) = "redis://" + ip

  def receive = {
    case AddMasterTask(ip) =>
      log.info(s"Telling Cornucopia to add master node with IP '$ip'")
      cornucopiaRef ! Task("+master", buildRedisUri(ip))
    case AddSlaveTask(ip) =>
      log.info(s"Telling Cornucopia to add slave node with IP '$ip'")
      cornucopiaRef ! Task("+slave", buildRedisUri(ip))
    case RemoveMasterTask(ip) =>
      log.info(s"Telling Cornucopia to remove master node with IP '$ip'")
      cornucopiaRef ! Task("-master", buildRedisUri(ip))
    case RemoveSlaveTask(ip) =>
      log.info(s"Telling Cornucopia to remove slave node with IP '$ip'")
      cornucopiaRef ! Task("-slave", buildRedisUri(ip))
    case Right((taskKey: String, uri: RedisURI)) =>
      log.info(s"Task '$taskKey' success, processing redis node $uri, telling Kubernetes controller")
      taskKey.head match {
        case '+' =>
          k8sController ! ScaleUpSuccess(taskKey, uri.toURI.toString)
        case '-' =>
          k8sController ! ScaleDownSuccess(taskKey, uri.toURI.toString)
      }
    case Left(e: String) =>
      log.error(s"Failed trying to add redis node to cluster: $e")
      // TO-DO: throw exception and implement some type of supervision strategy
    case TaskAccepted =>
      cluster ! TaskAccepted
    case TaskDenied =>
      cluster ! TaskDenied
  }
}

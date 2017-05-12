package com.adendamedia.kubernetes

import akka.actor._
import org.slf4j.LoggerFactory

/**
  * This Actor integrates with cornucopia
  */
object Cluster {
  def props(cornucopiaRef: ActorRef, k8sController: ActorRef) = Props(new Cluster(cornucopiaRef, k8sController))

  case class Join(ip: String)
}

class Cluster(cornucopiaRef: ActorRef, k8sController: ActorRef) extends Actor with ActorLogging {
  import context._
  import Cluster._
  import CornucopiaTask._

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val cornucopiaTask = context.system.actorOf(CornucopiaTask.props(cornucopiaRef, k8sController))

  def receive = {
    case Join(ip: String) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      cornucopiaTask ! AddMasterTask(ip)
      become({
        case Join(ip: String) =>
          log.info(s"Joining new Redis cluster node with IP address '$ip' as a slave node")
          cornucopiaTask ! AddSlaveTask(ip)
          unbecome()
      }, discardOld = false)
  }
}


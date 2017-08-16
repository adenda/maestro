package com.adendamedia.kubernetes

import akka.actor._
import org.slf4j.LoggerFactory
import com.adendamedia.cornucopia.actors.Gatekeeper.{Task, TaskAccepted, TaskDenied}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * This Actor integrates with cornucopia
  */
object Cluster {
  def props(cornucopiaRef: ActorRef, k8sController: ActorRef) = Props(new Cluster(cornucopiaRef, k8sController))

  case class Join(ip: String)
}

class Cluster(cornucopiaRef: ActorRef, k8sController: ActorRef) extends Actor with ActorLogging {
  import Cluster._
  import CornucopiaTask._

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val cornucopiaTask = context.system.actorOf(CornucopiaTask.props(cornucopiaRef, k8sController, self))

  private val addNodeBackoffTime = 10 // seconds

  implicit val ec: ExecutionContext = context.dispatcher

  def receive: Receive = joinMaster

  def joinMaster: Receive = {
    case Join(ip: String) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      val addMaster = AddMasterTask(ip)
      cornucopiaTask ! addMaster
      context.become(joiningMaster(addMaster))
  }

  def joiningMaster(task: AddMasterTask): Receive = {
    case TaskAccepted =>
      log.info(s"Add master task at IP ${task.ip} accepted")
      context.unbecome()
      context.become(joinSlave)
    case TaskDenied =>
      log.info(s"Task Denied, will reschedule the master node to join ${task.ip} in $addNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(addNodeBackoffTime.seconds) {
        cornucopiaTask ! task
      }
    case join: Join =>
      log.info(s"Cluster busy, will reschedule the node to join ${join.ip} in $addNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(addNodeBackoffTime.seconds) {
        self ! join
      }
  }

  def joinSlave: Receive = {
    case Join(ip: String) =>
      log.info(s"Joining new Redis cluster node with IP address '$ip' as a slave node")
      val addSlave = AddSlaveTask(ip)
      cornucopiaTask ! addSlave
      context.become(joiningSlave(addSlave))
  }

  def joiningSlave(task: AddSlaveTask): Receive = {
    case TaskAccepted =>
      log.info(s"Add slave task at IP ${task.ip} accepted")
      context.unbecome()
      context.become(joinMaster)
    case TaskDenied =>
      log.info(s"Task Denied, will reschedule the slave node to join ${task.ip} in $addNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(addNodeBackoffTime.seconds) {
        cornucopiaTask ! task
      }
    case join: Join =>
      log.info(s"Cluster busy, will reschedule the node to join ${join.ip} in $addNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(addNodeBackoffTime.seconds) {
        self ! join
      }
  }


}


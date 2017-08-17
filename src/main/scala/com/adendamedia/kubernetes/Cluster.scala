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

  val name = "cluster"

  case class Join(ip: String)
  case class Remove(ip: String)
}

class Cluster(cornucopiaRef: ActorRef, k8sController: ActorRef) extends Actor with ActorLogging {
  import Cluster._
  import CornucopiaTask._

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val cornucopiaTask = context.system.actorOf(CornucopiaTask.props(cornucopiaRef, k8sController, self))

  private val addNodeBackoffTime = 10 // seconds

  private val removeNodeBackoffTime = 10 // seconds

  implicit val ec: ExecutionContext = context.dispatcher

  def receive: Receive = accepting

  def accepting: Receive = {
    case join: Join =>
      context.become(joinMaster)
      self forward join
    case remove: Remove =>
      context.become(removeMaster)
      self forward remove
  }

  def removeMaster: Receive = {
    case Remove(ip: String) =>
      log.info(s"Removing retired Redis cluster node with IP address '$ip' as a master node")
      val removeMaster = RemoveMasterTask(ip)
      cornucopiaTask ! removeMaster
      context.unbecome()
      context.become(removingMaster(removeMaster))
  }

  def removingMaster(task: RemoveMasterTask): Receive = {
    case TaskAccepted =>
      log.info(s"Remove master task at IP ${task.ip} accepted")
      context.unbecome()
      context.become(removeSlave)
    case TaskDenied =>
      log.info(s"Task denied, will reschedule the master node to remove ${task.ip} in $removeNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(removeNodeBackoffTime.seconds) {
        cornucopiaTask ! task
      }
    case remove: Remove =>
      log.info(s"Cluster busy, will reschedule the node to remove ${remove.ip} in $removeNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(removeNodeBackoffTime.seconds) {
        self ! remove
      }
  }

  def removeSlave: Receive = {
    case Remove(ip: String) =>
      log.info(s"Removing retired Redis cluster node with IP address '$ip' as a slave node")
      val removeSlave = RemoveSlaveTask(ip)
      cornucopiaTask ! removeSlave
      context.unbecome()
      context.become(removingSlave(removeSlave))
  }

  def removingSlave(task: RemoveSlaveTask): Receive = {
    case TaskAccepted =>
      log.info(s"Remove slave task at IP ${task.ip} accepted")
      context.unbecome()
      context.become(accepting)
    case TaskDenied =>
      log.info(s"Task denied, will reschedule the slave node to remove ${task.ip} in $removeNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(removeNodeBackoffTime.seconds) {
        cornucopiaTask ! task
      }
    case remove: Remove =>
      log.info(s"Cluster busy, will reschedule the node to remove ${remove.ip} in $removeNodeBackoffTime seconds")
      context.system.scheduler.scheduleOnce(removeNodeBackoffTime.seconds) {
        self ! remove
      }
  }

  def joinMaster: Receive = {
    case Join(ip: String) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      val addMaster = AddMasterTask(ip)
      cornucopiaTask ! addMaster
      context.unbecome()
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
      context.unbecome()
      context.become(joiningSlave(addSlave))
  }

  def joiningSlave(task: AddSlaveTask): Receive = {
    case TaskAccepted =>
      log.info(s"Add slave task at IP ${task.ip} accepted")
      context.unbecome() // reset to accepting state
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


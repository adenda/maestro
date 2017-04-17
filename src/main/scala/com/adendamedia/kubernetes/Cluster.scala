package com.adendamedia.kubernetes

import akka.actor._
import org.slf4j.LoggerFactory

/**
  * This Actor integrates with cornucopia
  */
object Cluster {
  def props = Props(new Cluster)

  case class Join(ip: String)
}

class Cluster extends Actor {
  import context._
  import Cluster._

  private val logger = LoggerFactory.getLogger(this.getClass)

  def receive = {
    case Join(ip: String) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      become({
        case Join(ip: String) =>
          logger.info(s"Joining new Redis cluster node with IP address '$ip' as a slave node")
          unbecome()
      }, discardOld = false)
  }
}


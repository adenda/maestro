package com.adendamedia.kubernetes

import akka.actor._
import org.slf4j.LoggerFactory
import com.github.kliewkliew.cornucopia.Library
import com.github.kliewkliew.cornucopia.actors.CornucopiaSource.Task

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

  private def buildRedisUri(ip: String) = "redis://" + ip

  def receive = {
    case Join(ip: String) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      Library.ref ! Task("+master", buildRedisUri(ip))
      become({
        case Join(ip: String) =>
          logger.info(s"Joining new Redis cluster node with IP address '$ip' as a slave node")
          Library.ref ! Task("+slave", buildRedisUri(ip))
          unbecome()
      }, discardOld = false)
  }
}


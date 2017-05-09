package com.adendamedia.kubernetes

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.Future
import com.github.kliewkliew.cornucopia.Library
//import com.github.kliewkliew.cornucopia.actors.CornucopiaSource
import com.github.kliewkliew.cornucopia.actors.CornucopiaSource._

/**
  * This Actor integrates with cornucopia
  */
object Cluster {
  def props(cornucopiaRef: ActorRef) = Props(new Cluster(cornucopiaRef))

  case class Join(ip: String)
}

class Cluster(cornucopiaRef: ActorRef) extends Actor with ActorLogging {
  import context._
  import Cluster._

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  private def buildRedisUri(ip: String) = "redis://" + ip

  def receive = {
    case Join(ip: String) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      val result = ask(cornucopiaRef, Task("+master", buildRedisUri(ip))).mapTo[Either[String, String]]
      result map {
        case Right(success) =>
          log.info(s"Successfully added master")
        case Left(error) =>
          log.error(s"Error adding master: $error")
      }
      become({
        case Join(ip: String) =>
          log.info(s"Joining new Redis cluster node with IP address '$ip' as a slave node")
          cornucopiaRef ! Task("+slave", buildRedisUri(ip))
          unbecome()
      }, discardOld = false)
  }
}


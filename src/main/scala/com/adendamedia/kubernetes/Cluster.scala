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

  case class Join(ip: String, k8sController: ActorRef)
}

class Cluster(cornucopiaRef: ActorRef) extends Actor with ActorLogging {
  import context._
  import Cluster._
  import Kubernetes._

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  private def buildRedisUri(ip: String) = "redis://" + ip

  def receive = {
    case Join(ip: String, k8sController: ActorRef) =>
      logger.info(s"Joining new Redis cluster node with IP address '$ip' as a master node")
      val result = ask(cornucopiaRef, Task("+master", buildRedisUri(ip))).mapTo[Either[String, String]]
      result map {
        case Right(_) =>
          k8sController ! ScaleUpSuccess("Successfully added master redis node and resharded cluster")
        case Left(error) =>
          // TO-DO: Throw exception and implement some type of supervision strategy in parent
          log.error(s"Error adding master: $error")
      }
      become({
        case Join(ip: String, k8sController: ActorRef) =>
          log.info(s"Joining new Redis cluster node with IP address '$ip' as a slave node")
          val result = ask(cornucopiaRef, Task("+slave", buildRedisUri(ip))).mapTo[Either[String, String]]
          result map {
            case Right(_) =>
              k8sController ! ScaleUpSuccess("Successfully added slave redis node")
            case _ =>
              // TO-DO: Throw exception and implement some type of supervision strategy in parent
              log.error(s"Error adding slave")
          }
          unbecome()
      }, discardOld = false)
  }
}


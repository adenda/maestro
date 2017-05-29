package com.adendamedia.metrics

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.slf4j.LoggerFactory
import scala.collection.mutable

import com.adendamedia.EventBus

object RedisServerInfo {
  def props(eventBus: ActorRef): Props = Props(new RedisServerInfo(eventBus))

  case object GetRedisServerInfo
  case object InitializeConnections
}

class RedisServerInfo(eventBus: ActorRef) extends Actor {
  import RedisServerInfo._
  import RedisSample._
  import EventBus._
  import context.dispatcher

  // store connections to all known redis nodes
  private val connections = mutable.Map.empty[String, Int]

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  def receive = {
    case GetRedisServerInfo =>
      // TODO: implement me, integrate with redis
      logger.debug("Called get redis server info")
      sender ! (List(1,2,3), 3)
    case InitializeConnections => initializeConnections
  }

  // TODO: Separate this to a child actor (error kernel pattern)
  private def initializeConnections = {
    logger.info("Initializing connections to Redis nodes")
    val f: Future[List[String]] = ask(eventBus, GetRedisURIsFromKubernetes).mapTo[List[String]]
    f map { uris =>
      logger.info(s"Initialize connections for uris: $uris")
    }
  }
}

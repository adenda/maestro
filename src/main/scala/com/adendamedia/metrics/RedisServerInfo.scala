package com.adendamedia.metrics

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Try
import org.slf4j.LoggerFactory

import scala.collection.mutable
import com.adendamedia.EventBus
import java.util.concurrent.TimeUnit

import com.lambdaworks.redis.{ClientOptions, RedisClient}
import com.lambdaworks.redis.codec.ByteArrayCodec
import com.lambdaworks.redis.api.StatefulRedisConnection
import com.github.kliewkliew.salad.SaladAPI
import com.github.kliewkliew.salad.dressing.SaladServerCommandsAPI

import com.adendamedia.RedisConnection._

object RedisServerInfo {
  def props(eventBus: ActorRef, memorySampler: ActorRef): Props = Props(new RedisServerInfo(eventBus, memorySampler))

  case object GetRedisServerInfo
  case object InitializeConnections
}

class RedisServerInfo(eventBus: ActorRef, memorySampler: ActorRef) extends Actor {
  import RedisServerInfo._
  import RedisSample._
  import EventBus._
  import MemorySampler._
  import context.dispatcher

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")

  private val period = k8sConfig.getInt("period")

  // store connections to all known redis nodes
  // TODO: This should be stored in a state agent for thread safety
  private val connections = mutable.Map.empty[String, SaladServerCommandsAPI[_,_]]

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  def receive = {
    case GetRedisServerInfo =>
      // TODO: implement me, integrate with redis
      logger.debug("Called get redis server info")
      val ref = sender
      getRedisServerInfo map { memoryList: List[Int] =>
        logger.debug(s"Got redis server info (memory for each node): $memoryList")
        ref ! (memoryList, memoryList.size)
      }
    case InitializeConnections => initializeConnections
  }

  private def getRedisServerInfo: Future[List[Int]] = {
    import SaladServerCommandsAPI.ServerInfo
    import com.github.kliewkliew.salad.serde.StringSerdes._

    val memory: List[Future[Int]] = connections.toList.map { case (uri, conn) =>
      logger.debug(s"Getting server info for redis node with uri '$uri'")
      conn.info(Some("memory")) map(serverInfo => serverInfo("memory")("used_memory").toInt)
    }

    Future.sequence(memory)
  }

  // TODO: Separate this to a child actor (error kernel pattern)
  private def initializeConnections: Future[Unit] = {
    logger.info("Initializing connections to Redis nodes")
    val f: Future[List[String]] = ask(eventBus, GetRedisURIsFromKubernetes).mapTo[List[String]]

    f map { uris =>
      uris map { uri =>
        logger.info(s"Initialize connection for uri: $uri")
        initializeConnectionForUri(uri)
      }
    }

    f map { _ =>
      // TODO: Schedule this from event bus, simply send a message to event bus from here to do it
      val cancellable = context.system.scheduler.schedule(0 milliseconds,
        period seconds,
        memorySampler,
        SampleMemory
      )
    }

  }

  // TODO: Separate this to a child actor (error kernel)
  private def initializeConnectionForUri(uri: String) = {
    // TODO: handle better errors or error kernel pattern this and let exceptions happen
    val api = createConnection(uri)

    if (api.isFailure)
      logger.error("REDIS IS DOWN")
    else {
      logger.debug(s"Created connection to '$uri'")
      api map { c =>
        logger.debug(s"Adding connection to connections collection for '$uri'")
        connections put(uri, c)
      }
    }
  }
}

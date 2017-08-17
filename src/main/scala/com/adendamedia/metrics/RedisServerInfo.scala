package com.adendamedia.metrics

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Future.fromTry
import scala.util.Try
import org.slf4j.LoggerFactory

import scala.collection.mutable
import com.adendamedia.EventBus
import com.adendamedia.salad.dressing.SaladServerCommandsAPI
import com.adendamedia.RedisConnection._
import com.lambdaworks.redis.RedisClient
import com.adendamedia.RedisConnection.Redis

object RedisServerInfo {
  def props(eventBus: ActorRef, memorySampler: ActorRef): Props = Props(new RedisServerInfo(eventBus, memorySampler))

  case object GetRedisServerInfo
  case object InitializeConnections
  case class InitializeConnection(uri: String)
  case class RemoveConnection(uri: String)

  val name = "redisServerInfo"
}

class RedisServerInfo(eventBus: ActorRef, memorySampler: ActorRef) extends Actor with ActorLogging {
  import RedisServerInfo._
  import EventBus._
  import ConnectionInitializer._
  import context.dispatcher

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")

  private val period = k8sConfig.getInt("period")

  // store connections to all known redis nodes
  private val connections = mutable.Map.empty[String, Redis]

  private val connectionInitializer = context.system.actorOf(ConnectionInitializer.props(eventBus))

  // TODO: parameterize in application.conf
  implicit val timeout = Timeout(60 seconds)

  def receive: Receive = {
    case GetRedisServerInfo =>
      log.debug("Called get redis server info")
      val ref = sender
      getRedisServerInfo map { memoryList: List[Int] =>
        log.debug(s"Got redis server info (memory for each node): $memoryList")
        ref ! (memoryList, memoryList.size)
      }
    case InitializeConnections => initializeConnections
    case InitializeConnection(uri) => initializeConnection(uri, sender)
    case RemoveConnection(uri) => removeConnection(uri, sender)
  }

  private def getRedisUris: String = {
    connections.keys.mkString(", ")
  }

  private def removeConnection(redisUri: String, ref: ActorRef) = {
    val uri = redisUri.split("redis://")(1)
    log.info(s"Removing connection for redis node $uri")
    connections.get(uri).flatMap  { conn: Redis =>
      log.info(s"Shuting down connection for removed redis node $uri")
      conn.client.shutdown()
      connections.remove(uri) map { x =>
        log.info(s"Removed connection for removed redis node $uri")
        ref ! uri
        x
      }
    }
  }

  private def initializeConnection(uri: String, ref: ActorRef) = {
    val f: Future[(String, Redis)] = ask(connectionInitializer, InitializeOne(uri)).mapTo[(String, Redis)]

    f map { case (uri: String, conn: Redis) =>
      log.debug(s"Adding initialized connections to the list of connections for the memory sampler")
      connections.put(uri, conn)
      log.info(s"Memory will now be sampled from the following redis nodes: $getRedisUris")
      ref ! uri
    }
  }

  private def initializeConnections = {
    val f: Future[Map[String, Redis]] = ask(connectionInitializer, Initialize).mapTo[Map[String, Redis]]

    f map { conns =>
      log.debug(s"Adding initialized connections to the list of connections for the memory sampler")
      // TODO: again, put this in state agent for thread safety
      conns.foreach { case (uri, conn) => connections.put(uri, conn) }
      log.info(s"Memory will now be sampled from the following redis nodes: $getRedisUris")
      eventBus ! HasInitializedConnections
    }
  }

  private def getRedisServerInfo: Future[List[Int]] = {
    import com.adendamedia.salad.serde.StringSerdes._

    val memory: List[Future[Int]] = connections.toList.map { case (uri, conn) =>
      log.debug(s"Getting server info for redis node with uri '$uri'")
      conn.salad.info(Some("memory")) map(serverInfo => serverInfo("memory")("used_memory").toInt)
    }

    Future.sequence(memory)
  }

}

object ConnectionInitializer {
  def props(eventBus: ActorRef): Props = Props(new ConnectionInitializer(eventBus))

  case object Initialize
  case class InitializeOne(uri: String)
  case class Abort(error: Throwable)
}

class ConnectionInitializer(eventBus: ActorRef) extends Actor with ActorLogging {
  import ConnectionInitializer._
  import EventBus._
  import context.dispatcher


  implicit val timeout = Timeout(20 seconds)

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")

  private val period = k8sConfig.getInt("period")

  def receive = {
    case Initialize => initializeConnections(sender)
    case InitializeOne(uri) => initializeConnection(uri, sender)
    case Abort(e) =>
      throw e
  }

  private def initializeConnections(ref: ActorRef): Future[Unit] = {
    log.info("Initializing connections to Redis nodes")
    val f: Future[List[String]] = ask(eventBus, GetRedisURIsFromKubernetes).mapTo[List[String]]

    val connections = Map.empty[String, Redis]

    f map { uris =>
      uris.foldLeft(connections)((conn, uri) => conn + (uri -> initializeConnectionForUri("redis://" + uri).get))
    } map(ref ! _)
  }

  private def initializeConnection(redisUri: String, ref: ActorRef): Future[Unit] = {
    log.info(s"Initializing connection to Redis node with uri $redisUri")

    val init = initializeConnectionForUri(redisUri) map { conn: Redis =>
      log.debug(s"Initialized new Redis connection for uri $redisUri")
      val uri = redisUri.split("redis://")(1)
      ref ! (uri, conn)
    } recover {
      case e => self ! Abort(e)
    }

    fromTry(init)
  }

  private def initializeConnectionForUri(uri: String): Try[Redis] = {
    createConnection(uri) map { conn: Redis =>
      log.debug(s"Adding connection to connections collection for '$uri'")
      conn
    } recover { case e: Throwable =>
      log.error(s"Failed to create connection to uri $uri: {}", e)
      throw e
    }
  }

}

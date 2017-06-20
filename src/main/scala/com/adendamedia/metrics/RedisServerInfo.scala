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

object RedisServerInfo {
  def props(eventBus: ActorRef, memorySampler: ActorRef): Props = Props(new RedisServerInfo(eventBus, memorySampler))

  case object GetRedisServerInfo
  case object InitializeConnections
  case class InitializeConnection(uri: String)
}

class RedisServerInfo(eventBus: ActorRef, memorySampler: ActorRef) extends Actor {
  import RedisServerInfo._
  import EventBus._
  import ConnectionInitializer._
  import context.dispatcher

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")

  private val period = k8sConfig.getInt("period")

  // store connections to all known redis nodes
  // TODO: This should be stored in a state agent for thread safety
  private val connections = mutable.Map.empty[String, SaladServerCommandsAPI[_,_]]

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val connectionInitializer = context.system.actorOf(ConnectionInitializer.props(eventBus))

  // TODO: parameterize in application.conf
  implicit val timeout = Timeout(60 seconds)

  def receive = {
    case GetRedisServerInfo =>
      logger.debug("Called get redis server info")
      val ref = sender
      getRedisServerInfo map { memoryList: List[Int] =>
        logger.debug(s"Got redis server info (memory for each node): $memoryList")
        ref ! (memoryList, memoryList.size)
      }
    case InitializeConnections => initializeConnections
    case InitializeConnection(uri) => initializeConnection(uri, sender)
  }

  private def getRedisUris: String = {
    connections.keys.mkString(", ")
  }

  private def initializeConnection(uri: String, ref: ActorRef) = {
    val f: Future[(String, SaladServerCommandsAPI[_,_])] =
      ask(connectionInitializer, InitializeOne(uri)).mapTo[(String, SaladServerCommandsAPI[_,_])]

    f map { case (uri: String, conn: SaladServerCommandsAPI[_,_]) =>
      logger.debug(s"Adding initialized connections to the list of connections for the memory sampler")
      // TODO: Put in state agent
      connections.put(uri, conn)
      logger.info(s"Memory will now be sampled from the following redis nodes: $getRedisUris")
      ref ! uri
    }
  }

  private def initializeConnections = {
    val f: Future[Map[String, SaladServerCommandsAPI[_,_]]] =
      ask(connectionInitializer, Initialize).mapTo[Map[String, SaladServerCommandsAPI[_,_]]]

    f map { conns =>
      logger.debug(s"Adding initialized connections to the list of connections for the memory sampler")
      // TODO: again, put this in state agent for thread safety
      conns.foreach { case (uri, conn) => connections.put(uri, conn) }
      logger.info(s"Memory will now be sampled from the following redis nodes: $getRedisUris")
      eventBus ! HasInitializedConnections
    }
  }

  private def getRedisServerInfo: Future[List[Int]] = {
    import com.adendamedia.salad.serde.StringSerdes._

    val memory: List[Future[Int]] = connections.toList.map { case (uri, conn) =>
      logger.debug(s"Getting server info for redis node with uri '$uri'")
      conn.info(Some("memory")) map(serverInfo => serverInfo("memory")("used_memory").toInt)
    }

    Future.sequence(memory)
  }

}

object ConnectionInitializer {
  def props(eventBus: ActorRef): Props = Props(new ConnectionInitializer(eventBus))

  case object Initialize
  case class InitializeOne(uri: String)
}

class ConnectionInitializer(eventBus: ActorRef) extends Actor {
  import ConnectionInitializer._
  import EventBus._
  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")

  private val period = k8sConfig.getInt("period")

  def receive = {
    case Initialize => initializeConnections(sender)
    case InitializeOne(uri) => initializeConnection(uri, sender)
  }

  private def initializeConnections(ref: ActorRef): Future[Unit] = {
    logger.info("Initializing connections to Redis nodes")
    val f: Future[List[String]] = ask(eventBus, GetRedisURIsFromKubernetes).mapTo[List[String]]

    val connections = Map.empty[String, SaladServerCommandsAPI[_,_]]

    f map { uris =>
      uris.foldLeft(connections)((conn, uri) => conn + (uri -> initializeConnectionForUri(uri).get))
    } map(ref ! _)
  }

  private def initializeConnection(uri: String, ref: ActorRef): Future[Unit] = {
    logger.info(s"Initializing connection to Redis node with uri $uri")

    val init = initializeConnectionForUri(uri) map { conn: SaladServerCommandsAPI[_,_] =>
      logger.debug(s"Initialized new Redis connection for uri $uri")
      ref ! (uri, conn)
    }

    fromTry(init)
  }

  private def initializeConnectionForUri(uri: String): Try[SaladServerCommandsAPI[_,_]] = {
    createConnection(uri) map { conn: SaladServerCommandsAPI[_,_] =>
      logger.debug(s"Adding connection to connections collection for '$uri'")
      conn
    } recover { case e: Throwable =>
      logger.error(s"Failed to create connection to uri $uri")
      throw e
    }
  }

}

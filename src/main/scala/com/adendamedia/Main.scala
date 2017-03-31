package com.adendamedia

import com.lambdaworks.redis.RedisURI
import com.typesafe.config.ConfigFactory
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands
import com.lambdaworks.redis.cluster.RedisClusterClient
import com.lambdaworks.redis.RedisClient
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter
import org.slf4j.LoggerFactory

import akka.stream.scaladsl.{Source, Flow, Sink}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

object Main extends App {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val clientType = redisConfig.getString("client-type").toLowerCase

  logger.info(s"Redis client-type: $clientType")

  private object Cluster {
    private val config = redisConfig.getConfig("cluster")
    private val seedServer = config.getString("seed-server.host")
    private val port = config.getInt("seed-server.port")
    val node = RedisURI.create(Cluster.seedServer, Cluster.port)
  }

  // for local testing
  private object Standalone {
    private val config = redisConfig.getConfig("standalone")
    val uri = config.getString("uri")
  }

  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val redisPubSubPatternSource = Source.actorPublisher[PubSubEvent](PubSubEvent.props)
  val ref = Flow[PubSubEvent]
    .to(Sink.ignore)
    .runWith(redisPubSubPatternSource)

  val connection: StatefulRedisPubSubConnection[String, String] = clientType match {
    case "cluster" =>
      val client = RedisClusterClient.create(Cluster.node)
      client.connectPubSub()
    case "standalone" =>
      val client = RedisClient.create(Standalone.uri)
      client.connectPubSub()
  }

  val listener = new RedisPubSubAdapter[String, String]() {
    override def message(channel: String, message: String): Unit = {
      ref ! PubSubEvent.Channel(channel, message)
    }

    override def psubscribed(pattern: String, count: Long): Unit = {
      ref ! PubSubEvent.Pattern(pattern, count)
    }
  }

  connection.addListener(listener)

  // TO-DO: Use async api
  val sync: RedisPubSubCommands[String, String] = connection.sync()

  val channel = redisConfig.getString("channel")
  val pattern = redisConfig.getString("pattern")

  // TO-DO: Does this not work?
  sync.psubscribe(pattern)

  sync.subscribe(channel)
}

package com.adendamedia

import com.lambdaworks.redis.RedisURI
import com.typesafe.config.ConfigFactory
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.cluster.RedisClusterClient
import com.lambdaworks.redis.RedisClient
import org.slf4j.LoggerFactory
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext

class Redis(system: ActorSystem)(implicit val mat: ActorMaterializer) {
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

  private val redisPubSubPatternSource = Source.actorPublisher[PubSubEvent](PubSubEvent.props)
  implicit val ref = Flow[PubSubEvent]
    .to(Sink.ignore)
    .runWith(redisPubSubPatternSource)


  implicit val redisConnection: StatefulRedisPubSubConnection[String, String] = clientType match {
    case "cluster" =>
      val client = RedisClusterClient.create(Cluster.node)
      client.connectPubSub()
    case "standalone" =>
      val client = RedisClient.create(Standalone.uri)
      client.connectPubSub()
  }

  private val eventBus = system.actorOf(EventBus.props)
}


package com.adendamedia

import com.lambdaworks.redis.RedisURI
import com.typesafe.config.ConfigFactory
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.cluster.RedisClusterClient
import com.lambdaworks.redis.RedisClient
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

class Redis(system: ActorSystem)(implicit val mat: ActorMaterializer) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val clientType = redisConfig.getString("client-type").toLowerCase

  logger.info(s"Redis client-type: $clientType")

  private val memoryScale = new MemoryScale(system)

  private val eventBus = system.actorOf(EventBus.props(memoryScale))
}


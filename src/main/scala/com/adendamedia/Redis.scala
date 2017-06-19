package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

class Redis(system: ActorSystem)(implicit val mat: ActorMaterializer) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")

  private val memoryScale = new MemoryScale(system)

  private val eventBus = system.actorOf(EventBus.props(memoryScale))
}


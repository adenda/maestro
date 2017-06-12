package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import com.adendamedia.metrics.MemoryScaleSampler

import scala.concurrent.duration._

import com.adendamedia.metrics.{MemorySampler, RedisServerInfo}
import com.adendamedia.kubernetes.Kubernetes

object EventBus {
  def props(memoryScale: MemoryScale) = Props(new EventBus(memoryScale))

  case object GetRedisMemoryUsage
  case object GetRedisURIsFromKubernetes
}

class EventBus(memoryScale: MemoryScale) extends Actor {
  import EventBus._
  import RedisServerInfo._
  import Kubernetes._
  import MemoryScaleSampler._

  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val kubernetesConfig = ConfigFactory.load().getConfig("kubernetes")

  private val eventBus: ActorRef = context.self

  private val memorySampler = context.system.actorOf(MemorySampler.props(eventBus, memoryScale))

  private val redisServerInfo = context.system.actorOf(RedisServerInfo.props(eventBus, memorySampler))

  private val k8s = context.system.actorOf(Kubernetes.props)

  def receive = {
    case GetRedisMemoryUsage =>
      logger.debug("Event Bus getting redis server info")
      redisServerInfo.tell(GetRedisServerInfo, sender)
    case GetRedisURIsFromKubernetes =>
      logger.debug("Event Bus getting redis URIs")
      k8s.tell(GetRedisURIs, sender)
  }

  private val samplingInverval = redisConfig.getInt("sampler.interval")

  private val k8sMaker = (f: ActorRefFactory) => f.actorOf(Props[Kubernetes])

  private val memoryScaleSampler = context.system.actorOf(MemoryScaleSampler.props(memoryScale, k8sMaker))

  logger.debug("Scheduling to initialize connections")

  // Initialize connections to redis nodes
  context.system.scheduler.scheduleOnce(1 seconds, redisServerInfo, InitializeConnections)

  // Initialize memory scale sampler
  val cancellable = context.system.scheduler.schedule(samplingInverval seconds,
    samplingInverval seconds,
    memoryScaleSampler,
    Sample
  )
}

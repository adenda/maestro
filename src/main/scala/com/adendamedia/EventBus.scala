package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.adendamedia.kubernetes.Kubernetes

import scala.concurrent.duration._
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands

import com.adendamedia.metrics.{MemorySampler, RedisSample, RedisServerInfo}
import com.adendamedia.kubernetes.Kubernetes

object EventBus {
  def props(implicit redisConnection: StatefulRedisPubSubConnection[String, String],
            mat: ActorMaterializer,
            channelCounter: ChannelEventCounter,
            patternCounter: PatternEventCounter) = Props(new EventBus)

  case object IncrementChannelCounter
  case object IncrementPatternCounter
  case object GetChannelSample
  case object GetPatternSample

  case object GetRedisMemoryUsage
  case object GetRedisURIsFromKubernetes
}

class EventBus(implicit val redisConnection: StatefulRedisPubSubConnection[String, String],
               implicit val mat: ActorMaterializer,
               implicit val channelCounter: ChannelEventCounter,
               implicit val patternCounter: PatternEventCounter) extends Actor {
  import EventBus._
  import Sampler._
  import RedisSample._
  import RedisServerInfo._
  import MemorySampler._
  import Kubernetes._

  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val kubernetesConfig = ConfigFactory.load().getConfig("kubernetes")

  private val eventBus: ActorRef = context.self

  private val redisServerInfo = context.system.actorOf(RedisServerInfo.props(eventBus))

  private val k8s = context.system.actorOf(Kubernetes.props)

  def receive = {
    case IncrementChannelCounter =>
      logger.debug("Event Bus incrementing channel counter")
      channelCounter.incrementCounter
    case GetChannelSample =>
      logger.debug("Event Bus getting channel sample")
      sender ! channelCounter.getEventCounterNumber().counter
    case IncrementPatternCounter =>
      logger.debug("Event Bus incrementing pattern counter")
      patternCounter.incrementCounter
    case GetPatternSample =>
      logger.debug("Event Bus getting pattern sample")
      sender ! patternCounter.getEventCounterNumber().counter
    case GetRedisMemoryUsage =>
      logger.debug("Event Bus getting redis server info")
      redisServerInfo.tell(GetRedisServerInfo, sender)
    case GetRedisURIsFromKubernetes =>
      logger.debug("Event Bus getting redis URIs")
      k8s.tell(GetRedisURIs, sender)
  }

  def getRedisMemoryUsage(ref: ActorRef) = {

  }

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")

//  private val k8sMaker = (f: ActorRefFactory) => f.actorOf(Props[Kubernetes])

  private val memorySampler = context.system.actorOf(MemorySampler.props(eventBus))

  private val period = kubernetesConfig.getInt("period")

  // TODO: this should be called after initializing connections
  val cancellable = context.system.scheduler.schedule(0 milliseconds,
    period seconds,
    memorySampler,
    SampleMemory
  )

  // Initialize connections to redis nodes
  context.system.scheduler.scheduleOnce(1 seconds, k8s, InitializeConnections)

}

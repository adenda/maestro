package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.adendamedia.kubernetes.Kubernetes

import scala.concurrent.duration._
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands

import com.adendamedia.metrics.{MemorySampler, RedisSample, RedisServerInfo}

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

  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val kubernetesConfig = ConfigFactory.load().getConfig("kubernetes")

  private val eventBus: ActorRef = context.self

//  private val redisPubSubPatternSource = Source.actorPublisher[PubSubEvent](PubSubEvent.props(eventBus))
//  private val ref = Flow[PubSubEvent]
//    .to(Sink.ignore)
//    .runWith(redisPubSubPatternSource)

  private val redisServerInfo = context.system.actorOf(RedisServerInfo.props)

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
  }

  def getRedisMemoryUsage(ref: ActorRef) = {

  }

//  val listener = new RedisPubSubAdapter[String, String]() {
//    override def message(channel: String, message: String): Unit = {
//      ref ! PubSubEvent.Channel(channel, message)
//    }
//
//    override def message(pattern: String, channel: String, message: String): Unit = {
//      ref ! PubSubEvent.Pattern(pattern, channel, message)
//    }
//
//  }

//  redisConnection.addListener(listener)

  // TO-DO: Use async api
//  val sync: RedisPubSubCommands[String, String] = redisConnection.sync()

  private val channel = redisConfig.getString("channel")
  private val pattern = redisConfig.getString("pattern")

//  sync.psubscribe(pattern)
//
//  sync.subscribe(channel)

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
//  private val threshold: Int = k8sConfig.getInt("threshold")
//  private val maxValue: Int = redisConfig.getInt("pub-sub.max-value")

  private val k8sMaker = (f: ActorRefFactory) => f.actorOf(Props[Kubernetes])

  private val memorySampler = context.system.actorOf(MemorySampler.props(eventBus))

//  private val sampler = context.system.actorOf(Sampler.props(eventBus, k8sMaker, threshold, maxValue))

  private val period = kubernetesConfig.getInt("period")

//  private val samplerType: Sample = ConfigFactory.load().getConfig("redis").getString("sampler.type") match {
//    case "channel" => SampleChannel
//    case "pattern" => SamplePattern
//  }

  val cancellable = context.system.scheduler.schedule(0 milliseconds,
    period seconds,
    memorySampler,
    SampleMemory
  )

  // scheduler
//  val cancellable = context.system.scheduler.schedule(0 milliseconds,
//    period seconds,
//    sampler,
//    samplerType
//  )

}

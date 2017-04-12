package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import scala.concurrent.duration._
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands

object EventBus {
  def props(implicit redisConnection: StatefulRedisPubSubConnection[String, String],
            mat: ActorMaterializer,
            counter: ChannelEventCounter) = Props(new EventBus)

  case object IncrementCounter // TO-DO: Handle other names for counter with case class
  case object GetSample
}

class EventBus(implicit val redisConnection: StatefulRedisPubSubConnection[String, String],
               implicit val mat: ActorMaterializer,
               implicit val counter: ChannelEventCounter) extends Actor {
  import EventBus._
  import Sampler._
  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val kubernetesConfig = ConfigFactory.load().getConfig("kubernetes")

  private val eventBus: ActorRef = context.self

  private val redisPubSubPatternSource = Source.actorPublisher[PubSubEvent](PubSubEvent.props(eventBus))
  private val ref = Flow[PubSubEvent]
    .to(Sink.ignore)
    .runWith(redisPubSubPatternSource)

  def receive = {
    case IncrementCounter =>
      println("Event Bus Incrementing counter")
      counter.incrementCounter
    case GetSample =>
      println("Called GetSample")
      sender ! counter.getEventCounterNumber().counter
  }

  val listener = new RedisPubSubAdapter[String, String]() {
    override def message(channel: String, message: String): Unit = {
      ref ! PubSubEvent.Channel(channel, message)
    }

    override def message(pattern: String, channel: String, message: String): Unit = {
      ref ! PubSubEvent.Pattern(pattern, channel, message)
    }

  }

  redisConnection.addListener(listener)

  // TO-DO: Use async api
  val sync: RedisPubSubCommands[String, String] = redisConnection.sync()

  private val channel = redisConfig.getString("channel")
  private val pattern = redisConfig.getString("pattern")

  sync.psubscribe(pattern)

  sync.subscribe(channel)

  private val sampler = context.system.actorOf(Sampler.props(eventBus))

  private val period = kubernetesConfig.getInt("period")

  // scheduler
  val cancellable = context.system.scheduler.schedule(0 milliseconds,
    period seconds,
    sampler,
    Sample
  )

}

package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands

object EventBus {
  def props(implicit redisConnection: StatefulRedisPubSubConnection[String, String],
            mat: ActorMaterializer,
            counter: ChannelEventCounter) = Props(new EventBus)

  case object IncrementCounter // TO-DO: Handle other names for counter with case class
}

class EventBus(implicit val redisConnection: StatefulRedisPubSubConnection[String, String],
               implicit val mat: ActorMaterializer,
               implicit val counter: ChannelEventCounter) extends Actor {
  import EventBus._

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")

  private val eventBus: ActorRef = context.self

  private val redisPubSubPatternSource = Source.actorPublisher[PubSubEvent](PubSubEvent.props(eventBus))
  private val ref = Flow[PubSubEvent]
    .to(Sink.ignore)
    .runWith(redisPubSubPatternSource)

  def receive = {
    case IncrementCounter =>
      println("Incrementing counter")
      counter.incrementCounter
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
}

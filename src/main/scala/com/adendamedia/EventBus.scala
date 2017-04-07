package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands

object EventBus {
  def props(implicit redisConnection: StatefulRedisPubSubConnection[String, String], ref: ActorRef) = Props(new EventBus)

  case class IncrementCounter(name: String) // TO-DO: Handle other names for counter
}

class EventBus(implicit val redisConnection: StatefulRedisPubSubConnection[String, String],
               implicit val ref: ActorRef) extends Actor {
  import EventBus._
  import context._

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val redisConfig = ConfigFactory.load().getConfig("redis")

  implicit val max_val: Int = redisConfig.getInt("pub-sub.max-value")
  val counter = new ChannelEventCounter(system)

  private val pubSubEvents = system.actorOf(PubSubEvent.props)

  def receive = {
    case IncrementCounter(_) => counter.incrementCounter
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

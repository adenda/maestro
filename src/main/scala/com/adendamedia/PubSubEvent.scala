package com.adendamedia

import akka.actor.Props
import akka.actor.ActorRef
import akka.stream.actor.ActorPublisher
import org.slf4j.LoggerFactory
import com.adendamedia.EventBus.IncrementCounter

object PubSubEvent {
  def props(eventBus: ActorRef): Props = Props(new PubSubEvent(eventBus))

  final case class Pattern(pattern: String, channel: String, message: String)
  final case class Channel(channel: String, message: String)
}

class PubSubEvent(eventBus: ActorRef) extends ActorPublisher[PubSubEvent] {
  import PubSubEvent._
  private val logger = LoggerFactory.getLogger(this.getClass)

  def receive = {
    case pat: Pattern =>
      logger.debug(s"Received key pattern '${pat.pattern}' on channel '${pat.channel}' with message '${pat.message}'")
      eventBus ! IncrementCounter
    case channel: Channel =>
      logger.debug(s"Received published message '${channel.message}' on channel '${channel.channel}'")
      eventBus ! IncrementCounter
  }
}

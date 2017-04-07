package com.adendamedia

import akka.actor.Props
import akka.actor.ActorRef
import akka.stream.actor.ActorPublisher
import com.adendamedia.EventBus.IncrementCounter

object PubSubEvent {
  def props(eventBus: ActorRef): Props = Props(new PubSubEvent(eventBus))

  final case class Pattern(pattern: String, channel: String, message: String)
  final case class Channel(channel: String, message: String)
}

class PubSubEvent(eventBus: ActorRef) extends ActorPublisher[PubSubEvent] {
  import PubSubEvent._

  def receive = {
    case pat: Pattern => println(s"Received key pattern ${pat.pattern}, channel: ${pat.channel}, message: ${pat.message}")
    case channel: Channel =>
      println(s"Received published message ${channel.message} on channel ${channel.channel}")
      eventBus ! IncrementCounter
  }
}

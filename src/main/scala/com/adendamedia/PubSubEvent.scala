package com.adendamedia

import akka.actor.Props
import akka.stream.actor.ActorPublisher

object PubSubEvent {
  def props: Props = Props[PubSubEvent]

  final case class Pattern(pattern: String, channel: String, message: String)
  final case class Channel(channel: String, message: String)
}

class PubSubEvent extends ActorPublisher[PubSubEvent] {
  import PubSubEvent._

  def receive = {
    case pat: Pattern => println(s"Received key pattern ${pat.pattern}, channel: ${pat.channel}, message: ${pat.message}")
    case channel: Channel => println(s"Received published message ${channel.message} on channel ${channel.channel}")
  }
}

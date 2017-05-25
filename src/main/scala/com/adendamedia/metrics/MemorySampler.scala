package com.adendamedia.metrics

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.slf4j.LoggerFactory

import com.adendamedia.EventBus

object MemorySampler {
  def props(eventBus: ActorRef): Props = Props(new MemorySampler(eventBus: ActorRef))

  final case class Result(sample: Int)

  case object SampleMemory
}

class MemorySampler(eventBus: ActorRef) extends Actor {
  import MemorySampler._
  import RedisSample._
  import EventBus._

  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  def receive = {
    case SampleMemory => sampleMemory
  }

  private def sampleMemory = {
    logger.debug("Called Channel Sample")
    val f: Future[RedisSample] = ask(eventBus, GetRedisMemoryUsage).mapTo[RedisSample]
    f map handleResponse
  }

  private def handleResponse(sample: RedisSample) = {
    // TODO: implement me
    logger.info(s"got sample: $sample")
  }

}


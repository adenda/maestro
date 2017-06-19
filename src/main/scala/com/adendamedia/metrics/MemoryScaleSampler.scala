package com.adendamedia.metrics

import com.adendamedia.MemoryScale
import org.slf4j.LoggerFactory
import com.typesafe.config.ConfigFactory
import com.adendamedia.EventBus
import akka.actor._

object MemoryScaleSampler {
  def props(memoryScale: MemoryScale, eventBus: ActorRef): Props =
    Props(new MemoryScaleSampler(memoryScale, eventBus: ActorRef))

  case object Sample
  case object Reset
}

class MemoryScaleSampler(memoryScale: MemoryScale, eventBus: ActorRef) extends Actor {
  import MemoryScaleSampler._
  import EventBus._

  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val scaleUpThreshold = redisConfig.getInt("sampler.scaleup.threshold")

  private val logger = LoggerFactory.getLogger(this.getClass)

  def receive = {
    case Sample => sample
    case Reset =>
      memoryScale.resetCounter
      logger.debug(s"Reset memory scale counter")
      sender ! "OK"
  }

  private def sample = {
    val count = memoryScale.getEventCounterNumber().counter
    logger.debug(s"Got memory scale count: $count")

    if (count >= scaleUpThreshold) {
      logger.info(s"Memory scale value is $count, which is greater than or equal to scale-up threshold=$scaleUpThreshold: Scaling up cluster now")
      eventBus ! ScaleUpCluster
    } else {
      logger.info(s"Memory scale value is $count, which is less than scale-up threshold=$scaleUpThreshold: Do nothing")
    }
  }

}

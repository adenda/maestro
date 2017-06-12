package com.adendamedia.metrics

import com.adendamedia.MemoryScale
import org.slf4j.LoggerFactory
import com.typesafe.config.ConfigFactory
import com.adendamedia.kubernetes.Kubernetes
import akka.actor._

object MemoryScaleSampler {
  def props(memoryScale: MemoryScale, k8sMaker: ActorRefFactory => ActorRef): Props =
    Props(new MemoryScaleSampler(memoryScale, k8sMaker: ActorRefFactory => ActorRef))

  case object Sample
}

class MemoryScaleSampler(memoryScale: MemoryScale, k8sMaker: ActorRefFactory => ActorRef) extends Actor {
  import MemoryScaleSampler._
  import Kubernetes._

  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val scaleUpThreshold = redisConfig.getInt("sampler.scaleup.threshold")

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val k8s = k8sMaker(context)

  def receive = {
    case Sample => sample
  }

  private def sample = {
    val count = memoryScale.getEventCounterNumber().counter
    logger.debug(s"Got memory scale count: $count")

    if (count >= scaleUpThreshold) {
      logger.info(s"Memory scale value is $count, which is greater than or equal to scale-up threshold=$scaleUpThreshold: Scaling up cluster now")
      k8s ! ScaleUp
    } else {
      logger.info(s"Memory scale value is $count, which is less than scale-up threshold=$scaleUpThreshold: Do nothing")
    }
  }

}

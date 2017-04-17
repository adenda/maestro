package com.adendamedia

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.slf4j.LoggerFactory
import com.typesafe.config.ConfigFactory

object Sampler {
  def props(eventBus: ActorRef, k8sMaker: ActorRefFactory => ActorRef, threshold: Int, maxValue: Int) =
    Props(new Sampler(eventBus, k8sMaker: ActorRefFactory => ActorRef, threshold, maxValue))

  final case class Result(sample: Int)

  case object Sample
}

class Sampler(eventBus: ActorRef, k8sMaker: ActorRefFactory => ActorRef, threshold: Int, maxValue: Int) extends Actor {
  import Sampler._
  import EventBus._
  import Kubernetes._
  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  var previousSample: Result = Result(0)

  private val k8s = k8sMaker(context)

  def receive = {
    case Sample =>
      logger.debug("Called Sample")
      val f: Future[Result] = for {
        x <- ask(eventBus, GetSample).mapTo[Int]
      } yield Result(x)
      f map handleResponse
  }

  def handleResponse(res: Result): Unit = {
    logger.debug(s"Got response. Old sample is '${previousSample.sample}'. New sample is '${res.sample}'.")
    if ((res.sample - previousSample.sample) % maxValue >= threshold) {
      logger.info(s"Sampling Threshold reached, so scaling up now.")
      k8s ! ScaleUp
    }
    if (previousSample.sample != res.sample) previousSample = res
  }

}

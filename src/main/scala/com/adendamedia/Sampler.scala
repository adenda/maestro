package com.adendamedia

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.slf4j.LoggerFactory

object Sampler {
  def props(eventBus: ActorRef, k8sMaker: ActorRefFactory => ActorRef, threshold: Int, maxValue: Int) =
    Props(new Sampler(eventBus, k8sMaker: ActorRefFactory => ActorRef, threshold, maxValue))

  final case class Result(sample: Int)

  trait Sample
  case object SampleChannel extends Sample
  case object SamplePattern extends Sample
}

class Sampler(eventBus: ActorRef, k8sMaker: ActorRefFactory => ActorRef, threshold: Int, maxValue: Int) extends Actor {
  import Sampler._
  import EventBus._
  import com.adendamedia.kubernetes.Kubernetes._
  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit val timeout = Timeout(20 seconds)

  var previousSample: Result = Result(0)

  private val k8s = k8sMaker(context)

  def receive = {
    case SampleChannel =>
      logger.debug("Called Channel Sample")
      val f: Future[Result] = for {
        x <- ask(eventBus, GetChannelSample).mapTo[Int]
      } yield Result(x)
      f map handleResponse
    case SamplePattern =>
      logger.debug("Called Pattern Sample")
      val f: Future[Result] = for {
        x <- ask(eventBus, GetPatternSample).mapTo[Int]
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

package com.adendamedia

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory

object Sampler {
  def props(eventBus: ActorRef) = Props(new Sampler(eventBus))

  final case class Result(sample: Int)

  case object Sample
}

class Sampler(eventBus: ActorRef) extends Actor {
  import Sampler._
  import EventBus._
  import Kubernetes._
  import context.dispatcher

  implicit val timeout = Timeout(20 seconds)

  var previousSample: Result = Result(0)

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val threshold = k8sConfig.getInt("threshold")
  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val maxValue = redisConfig.getInt("pub-sub.max-value")

  private val k8s = context.system.actorOf(Kubernetes.props)

  def receive = {
    case Sample =>
      println("Called Sample")
      val f: Future[Result] = for {
        x <- ask(eventBus, GetSample).mapTo[Int]
      } yield Result(x)
      f map handleResponse
  }

  def handleResponse(res: Result): Unit = {
    println(s"Got response. Old sample is '${previousSample.sample}'. New sample is '${res.sample}'.")
    if ((res.sample - previousSample.sample) % maxValue >= threshold) {
      println(s"Threshold reached, so scaling up now.")
      k8s ! ScaleUp
    }
    if (previousSample.sample != res.sample) previousSample = res
  }

}

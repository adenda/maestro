package com.adendamedia

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future

object Sampler {
  def props(eventBus: ActorRef) = Props(new Sampler(eventBus))

  final case class Result(sample: Int)

  case object Sample
}

class Sampler(eventBus: ActorRef) extends Actor {
  import Sampler._
  import EventBus._
  import context.dispatcher

  implicit val timeout = Timeout(20 seconds)

  var previousSample: Result = Result(0)

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
    previousSample = res
  }
}

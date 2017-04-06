package com.adendamedia

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

object Main extends App {
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val redis = new Redis(system)
}

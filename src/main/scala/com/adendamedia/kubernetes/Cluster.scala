package com.adendamedia.kubernetes

import akka.actor._


/**
  * This Actor integrates with cornucopia
  */
object Cluster {
  def props = Props(new Cluster)

  case class Join(ip: String)
}

class Cluster extends Actor {
  import context._
  import Cluster._

  def receive = {
    case Join(ip: String) =>
      println(s"Time to join '$ip' as a master")
      become({
        case Join(ip: String) =>
          println(s"Time to join '$ip' as a slave")
          unbecome()
      }, discardOld = false)
  }
}


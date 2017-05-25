package com.adendamedia.metrics

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.slf4j.LoggerFactory

object RedisServerInfo {
  def props: Props = Props(new RedisServerInfo)

  case object GetRedisServerInfo
}

class RedisServerInfo extends Actor {
  import RedisServerInfo._
  import RedisSample._

  def receive = {
    case GetRedisServerInfo =>
      // TODO: implement me
      sender ! (List(1,2,3), 3)
  }
}

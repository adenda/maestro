package com.adendamedia

import scala.util.Try
import java.util.concurrent.TimeUnit

import com.lambdaworks.redis.{ClientOptions, RedisClient}
import com.lambdaworks.redis.codec.ByteArrayCodec
import com.lambdaworks.redis.api.StatefulRedisConnection
import com.adendamedia.salad.SaladAPI
import com.adendamedia.salad.dressing.SaladServerCommandsAPI

object RedisConnection {
  type Connection = StatefulRedisConnection[String,String]
  val codec = ByteArrayCodec.INSTANCE

  case class Redis(salad: SaladServerCommandsAPI[_,_], client: RedisClient)

  def createConnection(uri: String): Try[Redis] = {
    val client = RedisClient.create(uri)
    val connection = Try {
      client.setDefaultTimeout(1000, TimeUnit.MILLISECONDS)
      client.setOptions(ClientOptions.builder()
        .cancelCommandsOnReconnectFailure(true)
        .pingBeforeActivateConnection(true)
        .build())
      client.connect(codec)
    }
    val lettuceAPI = connection map { c =>
      c.asInstanceOf[Connection].async()
    }
    val serverCommandsApi = lettuceAPI.map { lettuce =>
      val salad = new SaladServerCommandsAPI(
        new SaladAPI(lettuce)
      )
      Redis(salad, client)
    }
    serverCommandsApi
  }
}

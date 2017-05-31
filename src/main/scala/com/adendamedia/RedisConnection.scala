package com.adendamedia

import scala.util.Try
import java.util.concurrent.TimeUnit

import com.lambdaworks.redis.{ClientOptions, RedisClient}
import com.lambdaworks.redis.codec.ByteArrayCodec
import com.lambdaworks.redis.api.StatefulRedisConnection
import com.github.kliewkliew.salad.SaladAPI
import com.github.kliewkliew.salad.dressing.SaladServerCommandsAPI
import com.lambdaworks.redis.api.async.RedisAsyncCommands

object RedisConnection {
  type Connection = StatefulRedisConnection[String,String]
  val codec = ByteArrayCodec.INSTANCE

  def createConnection(uri: String) = {
    val redisUri = "redis://" + uri
    val connection = Try {
      val client = RedisClient.create(redisUri)
      client.setDefaultTimeout(1000, TimeUnit.MILLISECONDS)
      client.setOptions(ClientOptions.builder()
        .cancelCommandsOnReconnectFailure(true)
        .pingBeforeActivateConnection(true)
        .build())
      client.connect(codec)
    }
    val lettuceAPI = connection map {
      case c => c.asInstanceOf[Connection].async()
    }
    val serverCommandsApi = lettuceAPI.map { lettuce =>
      new SaladServerCommandsAPI(
        new SaladAPI(lettuce)
      )
    }
    serverCommandsApi
  }
}

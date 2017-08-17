package com.adendamedia

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import com.adendamedia.metrics.MemoryScaleSampler

import scala.concurrent.duration._
import scala.concurrent.Future

import com.adendamedia.metrics.{MemorySampler, RedisServerInfo}
import com.adendamedia.kubernetes.Kubernetes

object EventBus {
  def props(memoryScale: MemoryScale) = Props(new EventBus(memoryScale))

  case object GetRedisMemoryUsage
  case object GetRedisURIsFromKubernetes
  case object HasInitializedConnections
  case class AddRedisNode(uri: String)
  case class RemoveRedisNode(uri: String)
  case object ScaleUpCluster
  case object ScaleDownCluster
  case object ResetMemoryScale
}

class EventBus(memoryScale: MemoryScale) extends Actor {
  import EventBus._
  import RedisServerInfo._
  import Kubernetes._
  import MemoryScaleSampler._
  import MemorySampler._

  import context.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val redisConfig = ConfigFactory.load().getConfig("redis")
  private val samplingInverval = redisConfig.getInt("sampler.interval")

  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val period = k8sConfig.getInt("period")

  private val eventBus: ActorRef = context.self

  private val memorySampler = context.system.actorOf(MemorySampler.props(eventBus, memoryScale))

  private val redisServerInfo = context.system.actorOf(RedisServerInfo.props(eventBus, memorySampler),
    RedisServerInfo.name)

  private val k8s = context.system.actorOf(Kubernetes.props(eventBus), Kubernetes.name)

  implicit val timeout = Timeout(60 seconds)

  def receive = {
    case GetRedisMemoryUsage =>
      logger.debug("Getting redis server info")
      redisServerInfo.tell(GetRedisServerInfo, sender)
    case GetRedisURIsFromKubernetes =>
      logger.debug("Getting redis URIs")
      k8s.tell(GetRedisURIs, sender)
    case HasInitializedConnections =>
      logger.debug("Received message that connections have been made, now scheduling memory sampler")
      scheduleMemorySampler
    case AddRedisNode(uri: String) =>
      logger.debug(s"Received message to add redis node to list of nodes")
      initializeConnection(uri, sender)
    case RemoveRedisNode(uri: String) =>
      logger.debug(s"Received message to remove redis node from list of nodes")
      removeConnection(uri, sender)
    case ScaleUpCluster =>
      logger.debug(s"Received message to scale up the Redis cluster")
      k8s ! ScaleUp
    case ScaleDownCluster =>
      logger.debug(s"Received message to scale down the Redis cluster")
      k8s ! ScaleDown
    case ResetMemoryScale =>
      logger.debug(s"Received message to reset memory scale")
      memorySampler ! ResetMemorySampler
  }

  private val memoryScaleSampler = context.system.actorOf(MemoryScaleSampler.props(memoryScale, eventBus))

  private def initializeConnection(uri: String, ref: ActorRef) = {
    val f1: Future[String] = redisServerInfo.ask(InitializeConnection(uri)).mapTo[String]
    val f2: Future[String] = memoryScaleSampler.ask(Reset).mapTo[String]

    for {
      uri: String <- f1
      ok: String <- f2
    } yield {
      logger.debug(s"Added new redis node with uri $uri, and reset memory scale counter")
      ref ! uri
    }
  }

  private def removeConnection(uri: String, ref: ActorRef) = {
    val f1: Future[String] = redisServerInfo.ask(RemoveConnection(uri)).mapTo[String]
    val f2: Future[String] = memoryScaleSampler.ask(Reset).mapTo[String]

    for {
      uri: String <- f1
      ok: String <- f2
    } yield {
      logger.debug(s"Removed redis node with uri $uri, and reset memory scale counter")
      ref ! uri
    }
  }

  private def scheduleMemorySampler = {
    val cancellable = context.system.scheduler.schedule(0 milliseconds,
      period seconds,
      memorySampler,
      SampleMemory
    )
  }

  logger.debug("Scheduling to initialize connections")

  // Initialize connections to redis nodes
  context.system.scheduler.scheduleOnce(1 seconds, redisServerInfo, InitializeConnections)

  // Initialize memory scale sampler
  // It's OK to schedule this early since it will just return 0
  val cancellable = context.system.scheduler.schedule(samplingInverval seconds,
    samplingInverval seconds,
    memoryScaleSampler,
    Sample
  )
}

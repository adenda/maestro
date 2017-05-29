package com.adendamedia

import akka.agent.Agent
import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor

trait RedisEventCounter

case class StateChannelEventCounter(counter: Int) extends RedisEventCounter

case class StatePatternEventCounter(counter: Int) extends RedisEventCounter

case class StateMemoryScale(counter: Int) extends RedisEventCounter

trait EventCounter {
  implicit val ex: ExecutionContextExecutor

  implicit def eventCounter(num: Int): EventCounterNumber

  val stateAgent: RedisEventCounter

  def incrementCounter: Unit

  def getEventCounterNumber(): RedisEventCounter
}

class ChannelEventCounter(system: ActorSystem)(implicit val max_val: Int) {
  implicit val ex = system.dispatcher
  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit def eventCounter(num: Int): EventCounterNumber = new EventCounterNumber(num)

  val stateAgent = Agent(new StateChannelEventCounter(0))

  def incrementCounter: Unit = {
    logger.debug("Incrementing channel event counter")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter.nextEventNumber)
    })
  }

  def getEventCounterNumber(): StateChannelEventCounter = stateAgent.get()
}

class PatternEventCounter(system: ActorSystem)(implicit val max_val: Int) {
  implicit val ex = system.dispatcher
  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit def eventCounter(num: Int): EventCounterNumber = new EventCounterNumber(num)

  val stateAgent = Agent(new StatePatternEventCounter(0))

  def incrementCounter: Unit = {
    logger.debug("Incrementing pattern event counter")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter.nextEventNumber)
    })
  }

  def getEventCounterNumber(): StatePatternEventCounter = stateAgent.get()
}

class MemoryScale(system: ActorSystem) {
  implicit val ex = system.dispatcher
  private val logger = LoggerFactory.getLogger(this.getClass)

  val stateAgent = Agent(new StatePatternEventCounter(0))

  def incrementCounter: Unit = {
    logger.debug("Incrementing memory scale counter")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter + 1)
    })
  }

  def decrementCounter: Unit = {
    logger.debug("Decrementing memory scale counter")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter - 1)
    })
  }

  def getEventCounterNumber(): StatePatternEventCounter = stateAgent.get()
}


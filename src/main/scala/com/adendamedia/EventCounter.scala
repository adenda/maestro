package com.adendamedia

import akka.agent.Agent
import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

trait RedisEventCounter

case class StateMemoryScale(counter: Int) extends RedisEventCounter

class MemoryScale(system: ActorSystem) {
  implicit val ex = system.dispatcher
  private val logger = LoggerFactory.getLogger(this.getClass)

  val stateAgent = Agent(StateMemoryScale(0))

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

  def resetCounter: Unit = {
    logger.debug("Resetting memory scale counter")
    stateAgent send StateMemoryScale(0)
  }

  def getEventCounterNumber(): StateMemoryScale = stateAgent.get()
}


package com.adendamedia.kubernetes

import akka.agent.Agent
import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

case class ReplicaCount(counter: Int)

class StatefulsetReplicaCounterAgent(system: ActorSystem) {
  implicit val ex = system.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  val stateAgent = Agent(ReplicaCount(0))

  def setReplicaNumber(count: Int): Unit = {
    logger.debug("Setting statefulset replica counter")
    stateAgent send (oldState => {
      oldState.copy(count)
    })
  }

  def incrementReplicaNumber: Unit = {
    logger.debug("Incrementing statefulset replica counter")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter + 1)
    })
  }

  def decrementReplicaNumber: Unit = {
    logger.debug("Decrementing statefulset replica counter")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter - 1)
    })
  }

  def getReplicaCount(): ReplicaCount = stateAgent.get()
}

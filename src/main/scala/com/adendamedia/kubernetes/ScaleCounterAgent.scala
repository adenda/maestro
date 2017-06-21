package com.adendamedia.kubernetes

import akka.agent.Agent
import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

case class ScaleCounter(counter: Int)

/**
  * Scale counter can be positive or negative. When zero, there is nothing to scale (i.e., steady state)
  */
class ScaleCounterAgent(system: ActorSystem) {
  implicit val ex = system.dispatcher

  private val logger = LoggerFactory.getLogger(this.getClass)

  val stateAgent = Agent(ScaleCounter(0))

  /**
    * Adjust the scale amount
    * @param magnitude positive or negative number indicating whether to add or remove nodes, respectively.
    */
  def adjustScale(magnitude: Int): Unit = {
    logger.debug(s"Adjusting Kubernetes scale magnitude by $magnitude.")
    stateAgent send (oldState => {
      oldState.copy(oldState.counter + magnitude)
    })
  }

  def getScaleMagnitude(): ScaleCounter = stateAgent.get()
}

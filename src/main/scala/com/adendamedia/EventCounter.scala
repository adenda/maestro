package com.adendamedia

import akka.agent.Agent
import akka.actor.ActorSystem

case class StateChannelEventCounter(counter: Int)

class EventCounter(system: ActorSystem)(implicit val max_val: Int) {
  implicit val ex = system.dispatcher

  implicit def eventCounter(num: Int): EventCounterNumber = new EventCounterNumber(num)

  val stateAgent = Agent(new StateChannelEventCounter(0))

  def incrementCounter: Unit = {
    stateAgent send (oldState => {
      oldState.copy(oldState.counter.nextEventNumber)
    })
  }

  def getEventCounterNumber(): StateChannelEventCounter = stateAgent.get()
}

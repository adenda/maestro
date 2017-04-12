package com.adendamedia

import akka.testkit.TestKit
import akka.testkit.TestActorRef
import akka.testkit.TestProbe
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRefFactory
import com.adendamedia.EventBus.GetSample
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}

class SamplerTest extends TestKit(ActorSystem("SamplerTest"))
  with WordSpecLike with BeforeAndAfterAll with MustMatchers {
  implicit val ec = system.dispatcher

  override def afterAll(): Unit = {
    system.terminate()
  }

  "Sampler" must {
    "ask Kubernetes to scale up if it reaches the minimum threshold for the configured sample period" in {
      import Sampler._
      import Kubernetes._

      val threshold: Int = 10
      val maxValue: Int = 20

      val eventBus = TestActorRef(new Actor{
        def receive = {
          case GetSample =>
            sender ! threshold
        }
      })

      val probe = TestProbe()
      val k8sMaker = (_: ActorRefFactory) => probe.ref

      val sampler = system.actorOf(Sampler.props(eventBus, k8sMaker, threshold, maxValue))

      sampler ! Sample

      probe.expectMsg(ScaleUp)
    }
  }

}

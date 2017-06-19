//package com.adendamedia
//
//import akka.testkit.{TestActorRef, TestKit}
//import akka.actor.ActorSystem
//import akka.stream.ActorMaterializer
//import com.adendamedia.EventBus._
//import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
//import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}
//import org.mockito.Mockito._
//import org.scalatest.mockito.MockitoSugar._
//
//class EventBusTest extends TestKit(ActorSystem("EventBusTest"))
//  with WordSpecLike with BeforeAndAfterAll with MustMatchers {
//  implicit val ec = system.dispatcher
//
//  override def afterAll(): Unit = {
//    system.terminate()
//  }

//  "ChannelEventCounter" must {
//    "roll over to zero after passing max_val" in {
//      implicit val max_val: Int = 5
//      val cntr = new ChannelEventCounter(system)
//      for (_ <- 1 to max_val + 1) {
//        cntr.incrementCounter
//      }
//      cntr.getEventCounterNumber() must be(new StateChannelEventCounter(0))
//    }
//  }

//  "EventBus" must {
//    "increment channel counter" in {
//      import EventBus._
//      implicit val max_val: Int = 5
//      implicit val mat: ActorMaterializer = ActorMaterializer.create(system)
//      implicit val counter = new ChannelEventCounter(system)
//      implicit val redisConnection: StatefulRedisPubSubConnection[String, String] = mock[StatefulRedisPubSubConnection[String, String]]

//      val props = EventBus.props(testActor)
//      val props = EventBus.props
//      val bus = system.actorOf(EventBus.props, "event-bus")
//      val bus = TestActorRef(new EventBus)
//      val bus = EventBus.props
//      bus ! IncrementCounter
//      expectMsg(IncrementCounter)
//      counter.getEventCounterNumber() must be (new StateChannelEventCounter(1))
//      bus.underlyingActor.counter.getEventCounterNumber() must be (new StateChannelEventCounter(1))
//      1 must be (1)
//    }
//  }
//
//}
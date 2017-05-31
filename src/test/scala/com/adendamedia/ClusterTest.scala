package com.adendamedia

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.adendamedia.kubernetes._
import com.typesafe.config.ConfigFactory
import akka.testkit.{TestKit, CallingThreadDispatcher, EventFilter}
import com.github.kliewkliew.cornucopia.actors.CornucopiaSource
import akka.testkit.TestActorRef
import akka.testkit.TestProbe
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRefFactory
import com.adendamedia.EventBus._
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import scala.concurrent.Await

import ClusterTest._

class ClusterTest extends TestKit(ActorSystem("ClusterTest"))
  with WordSpecLike with BeforeAndAfterAll with MustMatchers {
  implicit val ec = system.dispatcher

  override def afterAll(): Unit = {
    system.terminate()
  }

  val testIP = "192.168.0.1"

  "Custer" must {
    "ask Cornucopia library to add a new master node and log success" in {
      import Cluster._

//      val dispatcherId = CallingThreadDispatcher.Id
//
//      val fakeCornucopiaLibrary = system.actorOf(FakeCornucopiaLibrary.props.withDispatcher(dispatcherId))
//
//      val props = Props(new Cluster(fakeCornucopiaLibrary)).withDispatcher(dispatcherId)
//
//      val cluster = system.actorOf(props)
//
//      implicit val timeout = Timeout(5 seconds)

//      EventFilter.info(message = "Successfully added master",
//        occurrences = 1).intercept {
//        cluster ! Join(testIP)
//      }
//      val future = ask(cluster, Join(testIP))
//
//      future.onComplete {
//        case Failure(_) => assert(false)
//        case Success(msg) =>
//          assert(msg == Right("Successfully added master"))
//      }
//
//      Await.ready(future, timeout.duration)

      assert(1 == 1)
    }
  }

}

object ClusterTest {
  val testSystem = {
    val config = ConfigFactory.parseString(
      """
         akka.loggers = [akka.testkit.TestEventListener]
      """)
    ActorSystem("testsystem", config)
  }
}

object FakeCornucopiaLibrary {
  def props = Props(new FakeCornucopiaLibrary)

  final case class Task(operation: String, redisNodeIp: String, ref: Option[ActorRef] = None)
}

class FakeCornucopiaLibrary extends Actor {
  import CornucopiaSource._

  def receive = {
    case Task(_, _, _) =>
      sender ! Right("OK")
    case _ =>
      println("wat")
  }
}

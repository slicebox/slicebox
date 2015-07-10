package se.nimsa.sbx.util

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.driver.H2Driver
import se.nimsa.sbx.app.DbProps
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Props
import akka.pattern.pipe
import se.nimsa.sbx.log.LogProtocol._
import java.util.Date
import scala.concurrent.Future
import akka.actor.Stash
import akka.actor.Actor

class SequentialPipeToSupportTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("SequentialPipeToSupportTestSystem"))

  implicit val ec = system.dispatcher

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  case class ProcessSequentially(input: Double)
  case class ProcessInParallel(input: Double)

  class StatefulActor extends Actor with Stash with SequentialPipeToSupport {

    var state = 0.5 // a state variable that must be protected

    def futureStateChange(input: Double) = Future {
      state = 1 + input
      Thread.sleep(2000)
      state
    }

    def receive = {
      case ProcessSequentially(input) =>
        futureStateChange(input).pipeSequentiallyTo(sender)
      case ProcessInParallel(input) =>
        futureStateChange(input).pipeTo(sender)
    }
  }
  
  val statefulActor = system.actorOf(Props(new StatefulActor()))

  "A syncronous processing actor" should {

    "execute a process when asked to and return a result" in {
      statefulActor ! ProcessSequentially(1.0)
      expectMsg(2.0)
    }

    "execute multiple processes synchronously to protect state" in {
      statefulActor ! ProcessSequentially(2.0)
      statefulActor ! ProcessSequentially(3.0)
      statefulActor ! ProcessSequentially(4.0)
      statefulActor ! ProcessSequentially(5.0)
      expectMsg(3.0)
      expectMsg(4.0)
      expectMsg(5.0)
      expectMsg(6.0)
    }
    
    "fail to proctect state when processes are executed in parallel" in {
      statefulActor ! ProcessInParallel(6.0)
      statefulActor ! ProcessInParallel(7.0)
      expectMsg(8.0)
      expectMsg(8.0)
    }
  }
}
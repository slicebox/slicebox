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

class SynchronousProcessingActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("SynchronousProcessingActorTestSystem"))

  implicit val ec = system.dispatcher

  override def afterAll = {
    TestKit.shutdownActorSystem(_system)
  }

  case class ProcessSynchronously(input: Double)
  case class ProcessInParallel(input: Double)

  val spActor = system.actorOf(Props(new SynchronousProcessingActor() {

    var state = 0.5 // a state variable that must be protected

    def futureStateChange(input: Double) = Future {
      state = 1 + input
      Thread.sleep(500)
      state
    }

    def receive = {
      case ProcessSynchronously(input) =>
        processSynchronously(futureStateChange(input), sender)
      case ProcessInParallel(input) =>
        futureStateChange(input).pipeTo(sender)
    }
  }))

  "A syncronous processing actor" should {

    "execute a process when asked to and return a result" in {
      spActor ! ProcessSynchronously(1.0)
      expectMsg(2.0)
    }

    "execute multiple processes synchronously to protect state" in {
      spActor ! ProcessSynchronously(2.0)
      spActor ! ProcessSynchronously(3.0)
      spActor ! ProcessSynchronously(4.0)
      spActor ! ProcessSynchronously(5.0)
      expectMsg(3.0)
      expectMsg(4.0)
      expectMsg(5.0)
      expectMsg(6.0)
    }
    
    "fail to proctect state when processes are executed in parallel" in {
      spActor ! ProcessInParallel(6.0)
      spActor ! ProcessInParallel(7.0)
      expectMsg(8.0)
      expectMsg(8.0)
      
    }
  }
}
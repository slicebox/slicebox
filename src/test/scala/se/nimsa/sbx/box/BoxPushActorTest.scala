package se.nimsa.sbx.box

import java.util.concurrent.Executors

import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.{ActorMaterializer, KillSwitch}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class BoxPushActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxPushActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newWorkStealingPool())
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val testBox = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = true)
  val storage = new RuntimeStorage()

  var nPolls = 0
  var nPushed = 0

  val boxService: ActorRef = system.actorOf(Props(new Actor() {
    def receive: Receive = {
      case GetOutgoingTransactionsForBox(_) =>
        nPolls += 1
        sender ! Seq(OutgoingTransaction(1, testBox.id, testBox.name, 0, 10, 1000, 1000, TransactionStatus.WAITING))
    }
  }), name = "BoxService")

  val metaService: ActorRef = system.actorOf(Props(new Actor() {
    override def receive: Receive = { case _ => }
  }), name = "MetaService")

  val anonService: ActorRef = system.actorOf(Props(new Actor() {
    override def receive: Receive = { case _ => }
  }), name = "AnonService")

  case object Clear
  case object GetNumberOfKillSwitches
  val pushActor: ActorRef = system.actorOf(Props(new BoxPushActor(testBox, storage, 200.milliseconds, "../BoxService", "../MetaService", "../AnonService") {
    override def receive: Receive = {
      case Clear =>
        transactionKillSwitches.clear()
        sender ! Unit
      case GetNumberOfKillSwitches =>
        sender ! transactionKillSwitches.size
      case m => super.receive(m)
    }
    override def pushTransaction(transaction: BoxProtocol.OutgoingTransaction): (Future[Done], KillSwitch) = {
      nPushed += 1
      super.pushTransaction(transaction)
    }
  }), name = "PushBox")

  override def beforeEach(): Unit = {
    nPolls = 0
    nPushed = 0
    await(pushActor.ask(Clear))
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A BoxPushActor" should {
    "poll the database for new outgoing transactions to push" in {
      pushActor ! PollOutgoing
      expectNoMessage(1.second)
      nPolls shouldBe 1
    }

    "push pending transactions" in {
      pushActor ! PollOutgoing
      expectNoMessage(1.second)
      nPushed shouldBe 1
    }

    "add a kill switch for each started transaction and remove it when transaction finishes" in {
      pushActor ! PollOutgoing
      expectNoMessage(1.second)
      await(pushActor.ask(GetNumberOfKillSwitches).mapTo[Int]) shouldBe 1
      pushActor ! RemoveTransaction(1)
      expectNoMessage(1.second)
      await(pushActor.ask(GetNumberOfKillSwitches).mapTo[Int]) shouldBe 0
    }

    "should parse box urls into protocol, host and port" in {
      val pattern = BoxPushActor.pattern
      "http://example.com:8080/path" match {
        case pattern(pr, ho, po) =>
          pr shouldBe "http"
          ho shouldBe "example.com"
          po shouldBe "8080"
      }

      "https://1.2.3.4:8080/path" match {
        case pattern(pr, ho, po) =>
          pr shouldBe "https"
          ho shouldBe "1.2.3.4"
          po shouldBe "8080"
      }

      "localhost" match {
        case pattern(pr, ho, po) =>
          pr shouldBe null
          ho shouldBe "localhost"
          po shouldBe null
      }

      "ftp://localhost:1234" match {
        case pattern(pr, ho, po) =>
          pr shouldBe "ftp"
          ho shouldBe "localhost"
          po shouldBe "1234"
      }

      "a/b/http://8080/abc:56://" match {
        case pattern(pr, ho, po) =>
          pr shouldBe null
          ho shouldBe "a"
          po shouldBe null
      }
    }
  }
}
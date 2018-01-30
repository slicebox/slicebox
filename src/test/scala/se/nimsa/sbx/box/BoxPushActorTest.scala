package se.nimsa.sbx.box

import java.util.concurrent.Executors

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.RuntimeStorage

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class BoxPushActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxPushActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newWorkStealingPool())
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val testBox = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = true)
  val storage = new RuntimeStorage()

  val boxService: ActorRef = system.actorOf(Props(new Actor() {
    def receive: Receive = { case _ => }
  }), name = "BoxService")

  val metaService: ActorRef = system.actorOf(Props(new Actor() {
    override def receive: Receive = { case _ => }
  }), name = "MetaService")

  val anonService: ActorRef = system.actorOf(Props(new Actor() {
    override def receive: Receive = { case _ => }
  }), name = "AnonService")

  val pushActor: ActorRef = system.actorOf(Props(new BoxPushActor(testBox, storage, 200.milliseconds, "../BoxService", "../MetaService", "../AnonService") {}), name = "PushBox")

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
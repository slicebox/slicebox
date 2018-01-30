package se.nimsa.sbx.box

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.app.JsonFormats
import se.nimsa.sbx.storage.RuntimeStorage

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

class BoxPollActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with JsonFormats with PlayJsonSupport {

  def this() = this(ActorSystem("BoxPollActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val remoteBoxBaseUrl = "https://someurl.com"

  val storage = new RuntimeStorage()

  override def beforeEach(): Unit = {}

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "A BoxPollActor" should {


  }
}
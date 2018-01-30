package se.nimsa.sbx.box

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.{ByteString, Timeout}
import org.scalatest._
import se.nimsa.sbx.box.BoxProtocol._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Success, Try}
import scala.collection.immutable.Seq

class BoxStreamOpsTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import BoxStreamOps._

  def this() = this(ActorSystem("BoxStreamOpsTestSystem"))

  implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newWorkStealingPool())
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val okResponse = HttpResponse()
  val noContentResponse = HttpResponse(status = StatusCodes.NoContent)
  val dataResponse = HttpResponse(entity = ByteString(1, 2, 3, 4))

  val box: BoxProtocol.Box = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = false)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, 0, 1, 1000, 1000, TransactionStatus.WAITING)

  class BoxStreamOpsImpl extends BoxStreamOps {
    override val box: BoxProtocol.Box = BoxStreamOpsTest.this.box
    override val transferType: String = "test"
    override val retryInterval: FiniteDuration = 50.millis
    override implicit val system: ActorSystem = BoxStreamOpsTest.this.system
    override implicit val materializer: Materializer = BoxStreamOpsTest.this.materializer
    override implicit val ec: ExecutionContext = BoxStreamOpsTest.this.ec
    // TODO remove?
    override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
      Flow.fromFunction((rt: (HttpRequest, T)) => rt._1.method match {
        case HttpMethods.GET => (Success(dataResponse), rt._2)
        case HttpMethods.POST => (Success(noContentResponse), rt._2)
        case _ => (Success(okResponse), rt._2)
      })
    override def singleRequest(request: HttpRequest): Future[HttpResponse] =
      Future(okResponse)(ec)
  }

  "The box url regular expression" should "parse box urls into protocol, host and port" in {
    Future {
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

  "The transfer graph" should "transfer available files" in {
    var capturedImages = Seq.empty[OutgoingTransactionImage]
    val transferBatch: () => Future[Seq[OutgoingTransactionImage]] = {
      val n = 100
      var index = 0
      () => {
        val futureImages = if (index < 3)
          Future((1 to n)
            .map(id => OutgoingImage(id, transaction.id, index * n + id, index * n + id, sent = false))
            .map(image => OutgoingTransactionImage(transaction, image)))
        else
          Future(Seq.empty)
        futureImages.foreach(images => capturedImages = capturedImages ++ images)
        index += 1
        futureImages
      }
    }
    val impl = new BoxStreamOpsImpl()
    val switch = impl.pollAndTransfer(transferBatch).run()
    akka.pattern.after(1.second, system.scheduler) {
      Future {
        switch.shutdown()
        capturedImages.length shouldBe 300
      }
    }
  }

  it should "retry periodically when batch fails" in {
    var index = 0
    val transferBatch: () => Future[Seq[OutgoingTransactionImage]] =
      () => { index += 1; Future.failed(new Exception("failed batch")) }
    val impl = new BoxStreamOpsImpl()
    val switch = impl.pollAndTransfer(transferBatch).run()
    akka.pattern.after(impl.retryInterval * 10, system.scheduler) {
      Future {
        switch.shutdown()
        index should be >= 8
        index should be <= 10
      }
    }
  }

  it should "throttle retries wheb no images are available" in {
    var index = 0
    val transferBatch: () => Future[Seq[OutgoingTransactionImage]] =
      () => { index += 1; Future(Seq.empty[OutgoingTransactionImage]) }
    val impl = new BoxStreamOpsImpl()
    val switch = impl.pollAndTransfer(transferBatch).run()
    akka.pattern.after(impl.retryInterval * 10, system.scheduler) {
      Future {
        switch.shutdown()
        index should be >= 8
        index should be <= 10
      }
    }
  }
}
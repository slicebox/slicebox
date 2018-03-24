package se.nimsa.sbx.box

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import se.nimsa.sbx.box.BoxProtocol._

import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Success

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

  val box: BoxProtocol.Box = Box(1, "Test Box", "abc123", "testbox2.com", BoxSendMethod.PUSH, online = false)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, 0, 1, 1000, 1000, TransactionStatus.WAITING)

  class BoxStreamOpsImpl extends BoxStreamOps {
    override val box: BoxProtocol.Box = BoxStreamOpsTest.this.box
    override val transferType: String = "test"
    override val retryInterval: FiniteDuration = 50.millis
    override val batchSize: Int = 200
    override val parallelism: Int = 8
    override implicit val system: ActorSystem = BoxStreamOpsTest.this.system
    override implicit val materializer: Materializer = BoxStreamOpsTest.this.materializer
    override implicit val ec: ExecutionContext = BoxStreamOpsTest.this.ec
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
    akka.pattern.after(2.seconds, system.scheduler) {
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

  it should "throttle retries when no images are available" in {
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

  "Checking a response" should "pass responses ini the [200,300) range" in {
    val impl = new BoxStreamOpsImpl()

    val nReponsesChecked =
      (200 until 300)
      .map(status => HttpResponse(status = StatusCodes.custom(status, "")))
      .map(response => impl.checkResponse((Success(response), null)))
      .map(_ => 1).sum

    Future(nReponsesChecked shouldBe 100)
  }

  it should "pass 400 and 404 responses with a warning" in {
    val impl = new BoxStreamOpsImpl()
    val transactionImage = OutgoingTransactionImage(transaction, OutgoingImage(-1, transaction.id, 345, 4, sent = false))

    impl.checkResponse((Success(HttpResponse(status = StatusCodes.BadRequest)), transactionImage))
    impl.checkResponse((Success(HttpResponse(status = StatusCodes.NotFound)), transactionImage))

    Future(succeed) // if there are no exception, test is success
  }

  it should "fail with exception for any other responses" in {
    val impl = new BoxStreamOpsImpl()
    val transactionImage = OutgoingTransactionImage(transaction, OutgoingImage(-1, transaction.id, 345, 4, sent = false))

    recoverToSucceededIf[TransactionException] {
      Future(impl.checkResponse((Success(HttpResponse(status = StatusCodes.InternalServerError)), transactionImage)))
    }
  }

  "Counting transmitted files per transaction" should "increment index for each transmitted file grouped by transaction" in {
    val f = BoxStreamOps.indexInTransaction()

    Future {
      f(OutgoingTransactionImage(transaction.copy(id = 1), OutgoingImage(-1, 1, 345, 4, sent = false))).head._2 shouldBe 1
      f(OutgoingTransactionImage(transaction.copy(id = 1), OutgoingImage(-1, 1, 345, 4, sent = false))).head._2 shouldBe 2
      f(OutgoingTransactionImage(transaction.copy(id = 2), OutgoingImage(-1, 2, 345, 4, sent = false))).head._2 shouldBe 1
      f(OutgoingTransactionImage(transaction.copy(id = 3), OutgoingImage(-1, 3, 345, 4, sent = false))).head._2 shouldBe 1
      f(OutgoingTransactionImage(transaction.copy(id = 2), OutgoingImage(-1, 2, 345, 4, sent = false))).head._2 shouldBe 2
      f(OutgoingTransactionImage(transaction.copy(id = 1), OutgoingImage(-1, 1, 345, 4, sent = false))).head._2 shouldBe 3
    }
  }
}
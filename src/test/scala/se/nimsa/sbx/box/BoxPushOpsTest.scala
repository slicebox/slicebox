package se.nimsa.sbx.box

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{BadRequest, InternalServerError, ServiceUnavailable}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import se.nimsa.sbx.box.BoxProtocol._

import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Success, Try}

class BoxPushOpsTest extends TestKit(ActorSystem("BoxPushOpsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val profile = AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE))
  val box = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, profile, online = false)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, profile, 0, 1, 1000, 1000, TransactionStatus.WAITING)

  val okResponse = HttpResponse()
  val noResponse = HttpResponse(status = ServiceUnavailable)
  val rejectResponse = HttpResponse(status = BadRequest)
  val errorResponse = HttpResponse(status = InternalServerError)

  class BoxPushOpsImpl() extends BoxPushOps {
    override val box: Box = BoxPushOpsTest.this.box
    override implicit val system: ActorSystem = BoxPushOpsTest.this.system
    override implicit val ec: ExecutionContextExecutor = BoxPushOpsTest.this.ec
    override implicit val materializer: Materializer = BoxPushOpsTest.this.materializer
    override val retryInterval: FiniteDuration = 50.milliseconds
    override val batchSize: Int = 200
    override val parallelism: Int = 8
    override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
      Flow.fromFunction((requestT: (HttpRequest, T)) => (Try(okResponse), requestT._2))
    override def poll(n: Int): Future[Seq[OutgoingTransactionImage]] =
      Future(
        (1 to n)
          .map(id => OutgoingImage(id, transaction.id, 1000 + id, id, sent = false))
          .map(image => OutgoingTransactionImage(transaction, image)))
    override def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]] =
      Future(Seq.empty)
    override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] =
      Future(transactionImage.update(sentImageCount))
    override def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed] =
      Source.single(ByteString(1, 2, 3))
    override def singleRequest(request: HttpRequest): Future[HttpResponse] = Future(okResponse)
  }

  "Sending images via PUSH" should "post file to correct URL" in {
    var capturedUri = ""
    var capturedTransactionImage: OutgoingTransactionImage = null
    val impl = new BoxPushOpsImpl() {
      override def createPushRequest(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): (HttpRequest, OutgoingTransactionImage) = {
        val request = super.createPushRequest(box, transactionImage, tagValues)
        capturedUri = request._1.uri.toString
        capturedTransactionImage = transactionImage
        request
      }
    }
    impl.pushBatch().map { _ =>
      capturedTransactionImage should not be null
      val t = capturedTransactionImage.transaction
      val i = capturedTransactionImage.image
      capturedUri shouldBe s"${impl.box.baseUrl}/image?transactionid=${t.id}&sequencenumber=${i.sequenceNumber}&totalimagecount=${t.totalImageCount}"
    }
  }

  it should "update outgoing transaction as images are sent" in {
    var updatedTransaction: OutgoingTransaction = null

    val impl = new BoxPushOpsImpl() {
      override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        super.updateOutgoingTransaction(transactionImage, sentImageCount).map { t =>
          updatedTransaction = t.transaction
          t
        }
      }
    }

    impl.pushBatch().map { _ =>
      updatedTransaction should not be null
    }
  }

  it should "process many files" in {
    val n = 10000
    val nFilesPushed = new AtomicInteger()

    val impl = new BoxPushOpsImpl() {
      override val batchSize: Int = n
      override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        super.updateOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    impl.pushBatch().map { _ =>
      nFilesPushed.intValue() shouldBe n
    }
  }

  it should "update sent image count in order" in {
    val n = 10
    val sentImageCounts = new CopyOnWriteArrayList[Long]()
    val impl = new BoxPushOpsImpl() {
      override val batchSize: Int = n
      override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        sentImageCounts.add(sentImageCount)
        super.updateOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    impl.pushBatch().map { _ =>
      sentImageCounts.toArray.toSeq.map(_.asInstanceOf[Long]) shouldBe (1 to 10)
    }
  }

  it should "keep pushing files when one or more files are rejected on remote (HTTP status 400)" in {
    val n = 10
    val nFilesPushed = new AtomicInteger()

    val impl = new BoxPushOpsImpl() {
      override val batchSize: Int = n
      override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
        Flow.fromFunction((requestImage: (HttpRequest, T)) =>
          if (nFilesPushed.intValue() == 5)
            (Success(rejectResponse), requestImage._2)
          else
            (Success(okResponse), requestImage._2))
      override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        super.updateOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    impl.pushBatch().map { _ =>
      nFilesPushed.intValue() shouldBe n
    }
  }
}

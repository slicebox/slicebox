package se.nimsa.sbx.box

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model.StatusCodes.{BadRequest, InternalServerError, ServiceUnavailable}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, KillSwitch, Materializer}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.sbx.box.BoxProtocol._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

class BoxPushOpsTest extends TestKit(ActorSystem("BoxPushOpsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val box = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = false)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, 0, 1, 1000, 1000, TransactionStatus.WAITING)

  val okResponse = HttpResponse()
  val noResponse = HttpResponse(status = ServiceUnavailable)
  val rejectResponse = HttpResponse(status = BadRequest)
  val errorResponse = HttpResponse(status = InternalServerError)

  class BoxPushOpsImpl() extends BoxPushOps {
    override val box: Box = BoxPushOpsTest.this.box
    override implicit val system: ActorSystem = BoxPushOpsTest.this.system
    override implicit val ec: ExecutionContextExecutor = BoxPushOpsTest.this.ec
    override implicit val scheduler: Scheduler = system.scheduler
    override implicit val materializer: Materializer = BoxPushOpsTest.this.materializer
    override protected val retryInterval: FiniteDuration = 50.milliseconds
    override protected val streamChunkSize: Long = 1000000
    override protected def pool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] =
      Flow.fromFunction((requestImage: (HttpRequest, OutgoingTransactionImage)) => (Try(okResponse), requestImage._2))
    override protected def pendingOutgoingImagesForTransaction(transaction: BoxProtocol.OutgoingTransaction): Future[Source[BoxProtocol.OutgoingTransactionImage, NotUsed]] =
      Future(
        Source.fromIterator(() => (1 to transaction.totalImageCount.toInt).iterator)
          .map(id => OutgoingImage(id, transaction.id, 1000 + id, id, sent = false))
          .map(image => OutgoingTransactionImage(transaction, image)))
    override protected def outgoingTagValuesForImage(transactionImage: BoxProtocol.OutgoingTransactionImage): Future[Seq[BoxProtocol.OutgoingTagValue]] =
      Future(Seq.empty)
    override protected def updateOutgoingTransaction(transactionImage: BoxProtocol.OutgoingTransactionImage, sentImageCount: Long): Future[BoxProtocol.OutgoingTransactionImage] =
      Future(transactionImage.update(sentImageCount))
    override protected def setOutgoingTransactionStatus(transaction: BoxProtocol.OutgoingTransaction, status: BoxProtocol.TransactionStatus): Future[Unit] =
      Future(Unit)
    override protected def anonymizedDicomData(transactionImage: BoxProtocol.OutgoingTransactionImage, tagValues: Seq[BoxProtocol.OutgoingTagValue]): Source[ByteString, NotUsed] =
      Source.single(ByteString(1, 2, 3))
    override protected def singleRequest(request: HttpRequest): Future[HttpResponse] = Future(okResponse)
  }

  "Sending images via PUSH" should "post file to correct URL" in {
    var capturedUri = ""
    var capturedTransactionImage: OutgoingTransactionImage = null
    val impl = new BoxPushOpsImpl() {
      override def createRequest(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): (HttpRequest, OutgoingTransactionImage) = {
        val request = super.createPushRequest(box, transactionImage, tagValues)
        capturedUri = request._1.uri.toString
        capturedTransactionImage = transactionImage
        request
      }
    }
    val (futureResult, _) = impl
      .pushTransaction(transaction)
    futureResult
      .map { _ =>
        capturedTransactionImage should not be null
        val t = capturedTransactionImage.transaction
        val i = capturedTransactionImage.image
        capturedUri shouldBe s"${impl.box.baseUrl}/image?transactionid=${t.id}&sequencenumber=${i.sequenceNumber}&totalimagecount=${t.totalImageCount}"
      }
  }

  it should "deflate data before sending" in {
    val data = ByteString((1 to 10000).map(_.toByte): _*)
    var outgoingData = data
    val impl = new BoxPushOpsImpl() {
      override def anonymizedDicomData(transactionImage: BoxProtocol.OutgoingTransactionImage, tagValues: Seq[BoxProtocol.OutgoingTagValue]): Source[ByteString, NotUsed] =
        Source.single(data)
      override val pool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] =
        Flow[(HttpRequest, OutgoingTransactionImage)]
          .mapAsync(1) { requestImage =>
            requestImage._1.entity.dataBytes.runWith(Sink.fold(ByteString.empty)(_ ++ _)).map { d =>
              outgoingData = d
              requestImage
            }
          }
          .via(super.pool)
    }

    val (futureResult, _) = impl
      .pushTransaction(transaction)
    futureResult
      .map { _ =>
        outgoingData should not be data
        outgoingData.length should be < data.length
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

    val (futureResult, _) = impl.pushTransaction(transaction)
    futureResult.map { _ =>
      updatedTransaction should not be null
    }
  }

  it should "mark outgoing transaction as finished when all images have been sent" in {
    var transactionFinished = false

    val impl = new BoxPushOpsImpl() {
      override def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] = {
        transactionFinished = status == TransactionStatus.FINISHED
        super.setOutgoingTransactionStatus(transaction, status)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction)
    futureResult.map { _ =>
      transactionFinished shouldBe true
    }
  }

  it should "process many files" in {
    val n = 10000
    val nFilesPushed = new AtomicInteger()

    val impl = new BoxPushOpsImpl() {
      override def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    futureResult.map { _ =>
      nFilesPushed.intValue() shouldBe n
    }
  }

  it should "finalize transaction when finished" in {
    val n = 10
    val nFilesPushed = new AtomicInteger()
    val nRemoteStatusRequests = new AtomicInteger()
    var nFilesPushedWhenFinished = 0

    val impl = new BoxPushOpsImpl() {
      override def setRemoteIncomingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] = {
        nRemoteStatusRequests.incrementAndGet()
        super.setRemoteIncomingTransactionStatus(transaction, status)
      }
      override def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
      override def handleTransactionFinished(box: Box, transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] = {
        nFilesPushedWhenFinished = nFilesPushed.intValue()
        super.handleTransactionFinished(box, transaction, imageIds)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    futureResult.map { _ =>
      nRemoteStatusRequests.intValue() shouldBe 1
      nFilesPushedWhenFinished.intValue() shouldBe n
      nFilesPushed.intValue() shouldBe nFilesPushedWhenFinished.intValue()
    }
  }

  it should "update sent image count in order" in {
    val n = 10
    val sentImageCounts = new CopyOnWriteArrayList[Long]()
    val impl = new BoxPushOpsImpl() {
      override def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        sentImageCounts.add(sentImageCount)
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    futureResult.map { _ =>
      sentImageCounts.toArray.toSeq.map(_.asInstanceOf[Long]) shouldBe (1 to 10)
    }
  }

  it should "retry sending files on failure" in {
    val n = 100
    val failureProbability = 0.1

    var sentImageIds = Seq.empty[Long]

    val impl = new BoxPushOpsImpl() {
      override val pool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] =
        Flow.fromFunction((requestImage: (HttpRequest, OutgoingTransactionImage)) => {
          if (math.random() < failureProbability)
            if (math.random() < 0.5)
              (Failure(new RuntimeException("Some exception")), requestImage._2)
            else
              (Try(errorResponse), requestImage._2)
          else
            (Try(okResponse), requestImage._2)
        })
      override def handleTransactionFinished(box: Box, transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] = {
        sentImageIds = imageIds
        super.handleTransactionFinished(box, transaction, imageIds)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    futureResult.map { _ =>
      sentImageIds.toSet shouldBe (1001L to (1000L + n)).toSet
    }
  }

  it should "not retry sending files that are rejected on remote (HTTP status 400)" in {
    val n = 10
    val nFilesRetried = new AtomicInteger()
    val nFilesPushed = new AtomicInteger()

    val impl = new BoxPushOpsImpl() {
      override val pool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] =
        Flow.fromFunction((requestImage: (HttpRequest, OutgoingTransactionImage)) =>
          if (requestImage._2.image.sequenceNumber == 5)
            (Success(rejectResponse), requestImage._2)
          else
            (Success(okResponse), requestImage._2))
      override def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
      override def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, exception: Throwable): Future[OutgoingTransactionImage] = {
        nFilesRetried.incrementAndGet()
        super.handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    futureResult.map { _ =>
      nFilesPushed.intValue() shouldBe n
      nFilesRetried.intValue() shouldBe 0
    }
  }

  it should "retry also when last file in transaction fails" in {
    val n = 10
    var firstTime = true
    val nFilesRetried = new AtomicInteger()
    val nFilesPushed = new AtomicInteger()

    val impl = new BoxPushOpsImpl() {
      override val pool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] =
        Flow.fromFunction((requestImage: (HttpRequest, OutgoingTransactionImage)) =>
          if (requestImage._2.image.sequenceNumber == n && firstTime) {
            firstTime = false
            (Success(errorResponse), requestImage._2)
          } else
            (Success(okResponse), requestImage._2))
      override def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
      override def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, exception: Throwable): Future[OutgoingTransactionImage] = {
        nFilesRetried.incrementAndGet()
        super.handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    futureResult.map { _ =>
      nFilesPushed.intValue() shouldBe n
      nFilesRetried.intValue() shouldBe 1
    }
  }

  it should "not fail if setting remote status fails" in {
    val impl = new BoxPushOpsImpl() {
      override protected def setRemoteIncomingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] = {
        Future.failed(new RuntimeException("Connection refused"))
      }
    }

    val (futureResult, _) = impl.pushTransaction(transaction)
    futureResult.map { _ =>
      succeed
    }
  }

  it should "stop sending images if pipeline is shut down prematurely" in {
    val n = 1000
    val shutdownIndex = n / 2
    val nFilesPushed = new AtomicInteger()
    var killSwitch: KillSwitch = null

    val impl = new BoxPushOpsImpl() {
      override protected def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        nFilesPushed.incrementAndGet()
        if (nFilesPushed.intValue() == shutdownIndex)
          killSwitch.shutdown()
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    val (futureResult, switch) = impl.pushTransaction(transaction.copy(totalImageCount = n))
    killSwitch = switch

    futureResult.map { _ =>
      nFilesPushed.intValue() should be < n
    }
  }

  "The complete flow" should "complete the flow when decider function returns true" in {
    class Impl() extends BoxPushOpsImpl {
      override def completeFlow[T](flow: Flow[T, T, _], decider: T => Boolean): Flow[T, T, NotUsed] = super.completeFlow(flow, decider)
    }

    Source(1 to 10)
      .via(new Impl().completeFlow(Flow[Int], (i: Int) => i == 10))
      .runWith(Sink.ignore)
      .map(_ => succeed)
  }

  it should "complete the flow also when decider function returns true early" in {
    class Impl() extends BoxPushOpsImpl {
      override def completeFlow[T](flow: Flow[T, T, _], decider: T => Boolean): Flow[T, T, NotUsed] = super.completeFlow(flow, decider)
    }

    Source(1 to 10)
      .via(new Impl().completeFlow(Flow[Int], (i: Int) => i == 3))
      .runWith(Sink.ignore)
      .map(_ => succeed)
  }

}

package se.nimsa.sbx.box

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model.StatusCodes.{BadRequest, ServiceUnavailable}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.sbx.box.BoxProtocol._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}

class BoxPushOpsTest extends TestKit(ActorSystem("BoxPushOpsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val okResponse = HttpResponse()
  val noResponse = HttpResponse(status = ServiceUnavailable)
  val failResponse = HttpResponse(status = BadRequest)

  class BoxPushOpsImpl(val n: Int) extends BoxPushOps {
    val box = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = false)
    val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, 0, n, 1000, 1000, TransactionStatus.WAITING)
    override implicit val system: ActorSystem = BoxPushOpsTest.this.system
    override implicit val ec: ExecutionContextExecutor = BoxPushOpsTest.this.ec
    override implicit val scheduler: Scheduler = system.scheduler
    override implicit val materializer: Materializer = BoxPushOpsTest.this.materializer
    override val parallelism: Int = 10
    override val minBackoff: FiniteDuration = 20.milliseconds
    override val maxBackoff: FiniteDuration = 1.second
    override def pendingOutgoingImagesForTransaction(transaction: BoxProtocol.OutgoingTransaction): Future[Source[BoxProtocol.OutgoingTransactionImage, NotUsed]] =
      Future(
        Source.fromIterator(() => (1 to n).iterator)
          .map(id => OutgoingImage(id, transaction.id, 1000 + id, id, sent = false))
          .map(image => OutgoingTransactionImage(transaction, image)))
    override def outgoingTagValuesForImage(transactionImage: BoxProtocol.OutgoingTransactionImage): Future[Seq[BoxProtocol.OutgoingTagValue]] =
      Future(Seq.empty)
    override def updateOutgoingTransaction(transactionImage: BoxProtocol.OutgoingTransactionImage, sentImageCount: Long): Future[BoxProtocol.OutgoingTransactionImage] =
      Future(transactionImage.update(sentImageCount))
    override def setOutgoingTransactionStatus(transaction: BoxProtocol.OutgoingTransaction, status: BoxProtocol.TransactionStatus): Future[Unit] =
      Future(Unit)
    override def anonymizedDicomData(transactionImage: BoxProtocol.OutgoingTransactionImage, tagValues: Seq[BoxProtocol.OutgoingTagValue]): Source[ByteString, NotUsed] =
      Source.single(ByteString(1, 2, 3))
    override def sliceboxRequest(method: HttpMethod, uri: String, entity: RequestEntity): Future[HttpResponse] =
      Future(okResponse)
  }

  "Sending images via PUSH" should "post file to correct URL" in {
    var capturedUri = ""
    val impl = new BoxPushOpsImpl(1) {
      override def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] = {
        capturedUri = uri
        super.sliceboxRequest(method, uri, entity)
      }
    }
    val t = impl.transaction
    val i = OutgoingImage(1, t.id, 123, 1, sent = false)
    impl
      .pushImage(impl.box, OutgoingTransactionImage(t, i), Seq.empty)
      .map { _ =>
        capturedUri shouldBe s"${impl.box.baseUrl}/image?transactionid=${t.id}&sequencenumber=${i.sequenceNumber}&totalimagecount=${t.totalImageCount}"
      }
  }

  it should "deflate data before sending" in {
    val data = ByteString((1 to 10000).map(_.toByte): _*)
    var outgoingData = data
    val impl = new BoxPushOpsImpl(1) {
      override def anonymizedDicomData(transactionImage: BoxProtocol.OutgoingTransactionImage, tagValues: Seq[BoxProtocol.OutgoingTagValue]): Source[ByteString, NotUsed] =
        Source.single(data)
      override def sliceboxRequest(method: HttpMethod, uri: String, entity: RequestEntity): Future[HttpResponse] = {
        entity.dataBytes.runWith(Sink.fold(ByteString.empty)(_ ++ _)).map { d =>
          outgoingData = d
          okResponse
        }
      }
    }

    impl
      .pushImage(impl.box, OutgoingTransactionImage(impl.transaction, OutgoingImage(1, impl.transaction.id, 123, 1, sent = false)), Seq.empty)
      .map { _ =>
        outgoingData should not be data
        outgoingData.length should be < data.length
      }
  }

  it should "update outgoing transaction as images are sent" in {
    var updatedTransaction: OutgoingTransaction = null

    val impl = new BoxPushOpsImpl(1) {
      override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        super.updateOutgoingTransaction(transactionImage, sentImageCount).map { t =>
          updatedTransaction = t.transaction
          t
        }
      }
    }

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      updatedTransaction should not be null
    }
  }

  it should "mark outgoing transaction as finished when all images have been sent" in {
    var transactionFinished = false

    val impl = new BoxPushOpsImpl(1) {
      override def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] = {
        transactionFinished = status == TransactionStatus.FINISHED
        super.setOutgoingTransactionStatus(transaction, status)
      }
    }

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      transactionFinished shouldBe true
    }
  }

  it should "process many files" in {
    val nFilesPushed = new AtomicInteger()

    val impl = new BoxPushOpsImpl(10000) {
      override def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] = {
        if (method == HttpMethods.POST)
          nFilesPushed.incrementAndGet()
        super.sliceboxRequest(method, uri, entity)
      }
    }

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      nFilesPushed.intValue() shouldBe impl.n
    }
  }

  it should "finalize transaction when finished" in {
    val nFilesPushed = new AtomicInteger()
    val nPutRequests = new AtomicInteger()
    var statusUpdateUri = ""
    var nFilesPushedWhenFinished = 0

    val impl = new BoxPushOpsImpl(10) {
      override def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] = {
        if (method == HttpMethods.PUT) {
          nPutRequests.incrementAndGet()
          statusUpdateUri = uri
        }
        super.sliceboxRequest(method, uri, entity)
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

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      nPutRequests.intValue() shouldBe 1
      statusUpdateUri shouldBe s"${impl.box.baseUrl}/status?transactionid=${impl.transaction.id}"
      nFilesPushedWhenFinished shouldBe impl.n
    }
  }

  it should "update sent image count in order" in {
    val sentImageCounts = new CopyOnWriteArrayList[Long]()
    val impl = new BoxPushOpsImpl(10) {
      override def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
        sentImageCounts.add(sentImageCount)
        super.handleFileSentForOutgoingTransaction(transactionImage, sentImageCount)
      }
    }

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      sentImageCounts.toArray.toSeq.map(_.asInstanceOf[Long]) shouldBe (1 to 10)
    }
  }

  it should "retry sending files on failure" in {
    val failureProbability = 0.1

    var sentImageIds = Seq.empty[Long]

    val impl = new BoxPushOpsImpl(100) {
      override def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] =
        if (method == HttpMethods.POST)
          if (math.random() < failureProbability)
            if (math.random() < 0.5)
              Future.failed(new RuntimeException("Some exception"))
            else
              Future(failResponse)
          else
            super.sliceboxRequest(method, uri, entity)
        else super.sliceboxRequest(method, uri, entity)
      override def handleTransactionFinished(box: Box, transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] = {
        sentImageIds = imageIds
        super.handleTransactionFinished(box, transaction, imageIds)
      }
    }

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      sentImageIds.toSet shouldBe (1001L to (1000L + impl.n)).toSet
    }
  }

  it should "not fail if setting remote status fails" in {
    val impl = new BoxPushOpsImpl(10) {
      override def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] = {
        if (method == HttpMethods.PUT)
          Future.failed(new RuntimeException("Cannot update remote status"))
        else
          super.sliceboxRequest(method, uri, entity)
      }
    }

    val (futureResult, _) = impl.pushTransaction(impl.box, impl.transaction)
    futureResult.map { _ =>
      succeed
    }
  }

}

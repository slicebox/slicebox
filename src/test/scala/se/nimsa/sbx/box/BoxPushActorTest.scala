package se.nimsa.sbx.box

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.{ByteString, Timeout}
import org.scalatest._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageService}

import scala.collection.immutable.Seq
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

class BoxPushActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxPushActorTestSystem"))

  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val box = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = true)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, 0, 1, 1000, 1000, TransactionStatus.WAITING)
  val storage = new RuntimeStorage()

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A BoxPushActor" should "deflate DICOM data before sending" in {
    val data = ByteString((1 to 10000).map(_.toByte): _*)
    val transactionImage = OutgoingTransactionImage(transaction, OutgoingImage(4, transaction.id, 1004, 4, sent = false))
    val pushActorRef: TestActorRef[BoxPushActor] = TestActorRef[BoxPushActor](
      Props(new BoxPushActor(box, storage, 200.milliseconds, 200, 8, "../BoxService", "../MetaService", "../AnonService") {
        override protected def anonymizedDicomData(imageId: Long, tagValues: scala.collection.Seq[TagValue], storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): Source[ByteString, NotUsed] = Source.single(data)
      }), name = "PushBox")
    val pushActor: BoxPushActor = pushActorRef.underlyingActor

    pushActor.anonymizedDicomData(transactionImage, Seq.empty).runWith(Sink.fold(ByteString.empty)(_ ++ _))
      .map { outgoingData =>
        pushActorRef ! PoisonPill
        outgoingData should not be empty
        outgoingData.length should be < data.length
      }
  }

  it should "update outgoing transaction for each transmitted file and finalize when all files are sent" in {
    val n = 200
    val nUpdated = new AtomicInteger()
    var firstBatch = true
    var finalized = false
    val pushActorRef = system.actorOf(Props(
      new BoxPushActor(box, storage, 200.milliseconds, n, 8, "../BoxService", "../MetaService", "../AnonService") {
        override protected def anonymizedDicomData(imageId: Long, tagValues: scala.collection.Seq[TagValue], storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): Source[ByteString, NotUsed] =
          Source.single(ByteString(1, 2, 3, 4))
        override def poll(n: Int): Future[Seq[OutgoingTransactionImage]] =
          if (firstBatch) {
            firstBatch = false
            Future((1 to n)
              .map(id => OutgoingImage(id, transaction.id, 1000 + id, id, sent = false))
              .map(image => OutgoingTransactionImage(transaction, image)))(ec)
          } else
            Future(Seq.empty)(ec)
        override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
          Flow.fromFunction((rt: (HttpRequest, T)) => (Success(HttpResponse()), rt._2))
        override def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]] = Future(Seq.empty)(ec)
        override def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] = Future({})(ec)
        override def getImageIdsForOutgoingTransaction(transaction: OutgoingTransaction): Future[Seq[Long]] = Future(Seq.empty)(ec)
        override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
          nUpdated.getAndIncrement()
          val updatedTransactionImage = transactionImage.copy(
            transaction = transactionImage.transaction.copy(sentImageCount = transactionImage.image.sequenceNumber),
            image = transactionImage.image.copy(sent = true))
          if (updatedTransactionImage.transaction.sentImageCount >= updatedTransactionImage.transaction.totalImageCount)
            finalizeOutgoingTransaction(updatedTransactionImage.transaction, Seq.empty)
              .map(_ => updatedTransactionImage)(ec)
          else
            Future(updatedTransactionImage)(ec)
        }
        override def finalizeOutgoingTransaction(transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] = {
          finalized = true
          Future({})(ec)
        }
      }), name = "PushBox")

    akka.pattern.after(2.seconds, system.scheduler) {
      Future {
        pushActorRef ! PoisonPill
        nUpdated.intValue() shouldBe n
        finalized shouldBe true
      }
    }
  }
}
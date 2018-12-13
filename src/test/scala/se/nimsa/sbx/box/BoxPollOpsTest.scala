package se.nimsa.sbx.box

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.dicom.streams.DicomStreamException
import se.nimsa.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import se.nimsa.sbx.app.GeneralProtocol
import se.nimsa.sbx.app.GeneralProtocol.SourceType
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.box.BoxStreamOps.TransactionException
import se.nimsa.sbx.dicom.DicomHierarchy.{Image, Patient, Series, Study}
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataProtocol
import se.nimsa.sbx.metadata.MetaDataProtocol.MetaDataAdded

import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

class BoxPollOpsTest extends TestKit(ActorSystem("BoxPollOpsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val profile = AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE))
  val remoteBoxBaseUrl = "https://someurl.com"
  val box = Box(1, "Test Box", "abc123", remoteBoxBaseUrl, BoxSendMethod.POLL, profile, online = false)
  val transaction: OutgoingTransaction = OutgoingTransaction(1, box.id, box.name, profile, 0, 1, 1000, 1000, TransactionStatus.WAITING)

  def okResponse[T](entity: T)(implicit m: Marshaller[T, MessageEntity]): Future[HttpResponse] =
    Marshal(entity).to[MessageEntity].map { message =>
      HttpResponse(entity = message)
    }
  def emptyResponse = HttpResponse()
  val notFoundResponse = HttpResponse(status = NotFound)
  val noResponse = HttpResponse(status = ServiceUnavailable)
  val rejectResponse = HttpResponse(status = BadRequest)
  val errorResponse = HttpResponse(status = InternalServerError)

  class BoxPollOpsImpl(n: Int) extends BoxPollOps {
    override val box: Box = BoxPollOpsTest.this.box
    override implicit val system: ActorSystem = BoxPollOpsTest.this.system
    override implicit val ec: ExecutionContextExecutor = BoxPollOpsTest.this.ec
    override implicit val materializer: Materializer = BoxPollOpsTest.this.materializer
    override val retryInterval: FiniteDuration = 50.milliseconds
    override val batchSize: Int = 200
    override val parallelism: Int = 8
    override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
      Flow.fromFunction((requestT: (HttpRequest, T)) => (Try(HttpResponse(entity = ByteString(1, 2, 3, 4))), requestT._2))
    override def singleRequest(request: HttpRequest): Future[HttpResponse] =
      if (n == 0)
        Future(notFoundResponse)
      else if (n == 1)
        okResponse(Seq(OutgoingTransactionImage(transaction, OutgoingImage(1, transaction.id, 1001, 1, sent = false))))
      else
        okResponse((1 to n)
          .map(id => OutgoingImage(id, transaction.id, 1000 + id, id, sent = false))
          .map(image => OutgoingTransactionImage(transaction, image)))
    override def storeDicomData(bytesSource: Source[ByteString, _], source: GeneralProtocol.Source): Future[MetaDataProtocol.MetaDataAdded] =
      Future.successful(MetaDataAdded(Patient(5, PatientName("John Doe"), PatientID("12345678"), PatientBirthDate(""), PatientSex("M")),
        Study(4, 5, StudyInstanceUID("1.2.3.4"), StudyDescription(""), StudyDate(""), StudyID(""), AccessionNumber(""), PatientAge("")),
        Series(3, 4, SeriesInstanceUID("5.6.7.8"), SeriesDescription(""), SeriesDate(""), Modality("CT"), ProtocolName(""), BodyPartExamined(""), Manufacturer(""), StationName(""), FrameOfReferenceUID("2.4.6.8")),
        Image(2, 3, SOPInstanceUID("4.3.2.1"), ImageType(""), InstanceNumber("1")),
        patientAdded = true, studyAdded = true, seriesAdded = true, imageAdded = true, GeneralProtocol.Source(SourceType.BOX, box.name, box.id)))
    override def updateIncoming(transactionImage: OutgoingTransactionImage, imageIdMaybe: Option[Long], added: Boolean): Future[IncomingUpdated] =
      Future.successful(IncomingUpdated(IncomingTransaction(-1, box.id, box.name, transactionImage.transaction.id, transactionImage.transaction.sentImageCount, transactionImage.transaction.sentImageCount, transactionImage.transaction.totalImageCount, 1000, 1000, TransactionStatus.PROCESSING)))
    override def updateBoxOnlineStatus(online: Boolean): Future[Unit] = Future(Unit)
  }

  "Receiving images via POLL" should "call correct poll URL" in {
    var capturedUri = ""
    val n = 7
    val impl = new BoxPollOpsImpl(0) {
      override def singleRequest(request: HttpRequest): Future[HttpResponse] = {
        capturedUri = request.uri.toString
        super.singleRequest(request)
      }
    }
    impl.poll(n).map { _ =>
      capturedUri shouldBe s"$remoteBoxBaseUrl/outgoing/poll?n=$n"
    }
  }

  it should "return a sequence of n transactions when n or more transactions is available" in {
    val n = 10
    val impl = new BoxPollOpsImpl(n)
    impl.poll(n).map { transactionImages =>
      transactionImages should have size n
    }
  }

  it should "return an empty sequence of transactions when response is not found" in {
    val impl = new BoxPollOpsImpl(0)
    impl.poll(10).map { transactionImages =>
      transactionImages shouldBe empty
    }
  }

  it should "fail a batch when storing data throws some exception" in {
    val impl = new BoxPollOpsImpl(10) {
      override def storeDicomData(bytesSource: Source[ByteString, _], source: GeneralProtocol.Source): Future[MetaDataAdded] =
        throw new RuntimeException("Error storing")
    }
    recoverToSucceededIf[RuntimeException] {
      impl.pullBatch()
    }
  }

  it should "fail a batch when response status is not 2xx" in {
    val impl = new BoxPollOpsImpl(10) {
      override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
        Flow.fromFunction((requestT: (HttpRequest, T)) =>
          (Try(errorResponse), requestT._2))
    }
    recoverToSucceededIf[TransactionException] {
      impl.pullBatch()
    }
  }

  it should "ignore DICOM stream exceptions as these are likely due to unsupported presentation context at receiver" in {
    val impl = new BoxPollOpsImpl(1) {
      override def storeDicomData(bytesSource: Source[ByteString, _], source: GeneralProtocol.Source): Future[MetaDataAdded] =
        Future.failed(new DicomStreamException("Error storing"))
    }
    impl.pullBatch().map(_ => succeed)
  }

  it should "ignore files that have been removed on server" in {
    val impl = new BoxPollOpsImpl(10) {
      override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
        Flow.fromFunction((requestT: (HttpRequest, T)) => {
          if (requestT._1.uri.path.toString.endsWith("outgoing") && requestT._2.asInstanceOf[OutgoingTransactionImage].image.sequenceNumber == 5)
            (Try(HttpResponse(status = StatusCodes.NotFound)), requestT._2)
          else
            (Try(HttpResponse(entity = ByteString(1, 2, 3, 4))), requestT._2)
        })
    }
    impl.pullBatch().map(_ => succeed)
  }

  it should "ignore bad requests when fetching files" in {
    val impl = new BoxPollOpsImpl(10) {
      override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
        Flow.fromFunction((requestT: (HttpRequest, T)) => {
          if (requestT._1.uri.path.toString.endsWith("outgoing") && requestT._2.asInstanceOf[OutgoingTransactionImage].image.sequenceNumber == 5)
            (Try(HttpResponse(status = StatusCodes.BadRequest)), requestT._2)
          else
            (Try(HttpResponse(entity = ByteString(1, 2, 3, 4))), requestT._2)
        })
    }
    impl.pullBatch().map(_ => succeed)
  }

  it should "call correct URL when fetching file data" in {
    var capturedUri = ""
    var capturedTi: OutgoingTransactionImage = null
    val impl = new BoxPollOpsImpl(1) {
      override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
        Flow.fromFunction((requestT: (HttpRequest, T)) => {
          if (capturedUri.isEmpty) capturedUri = requestT._1.uri.toString
          if (capturedTi == null) capturedTi = requestT._2.asInstanceOf[OutgoingTransactionImage]
          (Try(HttpResponse(entity = ByteString(1, 2, 3, 4))), requestT._2)
    })
    }
    impl.pullBatch().map { _ =>
      capturedUri shouldBe s"$remoteBoxBaseUrl/outgoing?transactionid=${capturedTi.transaction.id}&imageid=${capturedTi.image.id}"
    }
  }

  it should "call correct URL when sending done message" in {
    var capturedUri = ""
    val impl = new BoxPollOpsImpl(1) {
      override def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
        Flow.fromFunction((requestT: (HttpRequest, T)) => {
          capturedUri = requestT._1.uri.toString
          (Try(HttpResponse(entity = ByteString(1, 2, 3, 4))), requestT._2)
        })
    }
    impl.pullBatch().map { _ =>
      capturedUri shouldBe s"$remoteBoxBaseUrl/outgoing/done"
    }
  }

  it should "update incoming transaction for each received image" in {
    val n = 10
    val nUpdated = new AtomicInteger()
    val impl = new BoxPollOpsImpl(n) {
      override def updateIncoming(transactionImage: OutgoingTransactionImage, imageIdMaybe: Option[Long], added: Boolean): Future[IncomingUpdated] = {
        nUpdated.getAndIncrement()
        super.updateIncoming(transactionImage, imageIdMaybe, added)
      }
    }
    impl.pullBatch().map { _ =>
      nUpdated.intValue() shouldBe n
    }
  }

}

package se.nimsa.sbx.seriestype

import akka.actor.{ActorRef, ActorSelection, ActorSystem}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.dcm4che3.data.Keyword
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.dicom.Tag
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.DicomHierarchy.Series
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageServiceActor}
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class SeriesTypeUpdateActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("SeriesTypesUpdateActorSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val dbConfig = TestUtil.createTestDb("seriestypeupdateactortest")
  val dao = new MetaDataDAO(dbConfig)

  val storage = new RuntimeStorage

  val seriesTypeDao = new SeriesTypeDAO(dbConfig)
  val metaDataDao = new MetaDataDAO(dbConfig)
  val propertiesDao = new PropertiesDAO(dbConfig)

  override def beforeAll(): Unit = {
    await(metaDataDao.create())
    await(propertiesDao.create())
    await(seriesTypeDao.create())
  }

  val storageService: ActorRef = system.actorOf(StorageServiceActor.props(storage), name = "StorageService")
  val metaDataService: ActorRef = system.actorOf(MetaDataServiceActor.props(metaDataDao, propertiesDao), name = "MetaDataService")
  val seriesTypeService: ActorRef = system.actorOf(SeriesTypeServiceActor.props(seriesTypeDao, storage), name = "SeriesTypeService")
  val seriesTypeUpdateService: ActorSelection = system.actorSelection("user/SeriesTypeService/SeriesTypeUpdate")

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  override def afterEach(): Unit = await(Future.sequence(Seq(
    seriesTypeDao.clear(),
    metaDataDao.clear(),
    propertiesDao.clear(),
    Future.successful(storage.clear())
  )))

  "A SeriesTypeUpdateActor" should {

    "be able to add matching series type for series" in {

      val seriesType = addSeriesType()
      addMatchingRuleToSeriesType(seriesType)

      val series = addTestDicomData()

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMessage(3.seconds)

      waitForSeriesTypesUpdateCompletion()

      val seriesSeriesTypes = seriesSeriesTypesForSeries(series)

      seriesSeriesTypes.size should be(1)
      seriesSeriesTypes.head.seriesTypeId should be(seriesType.id)
    }

    "not add series type that do not match series" in {

      val seriesType = addSeriesType()
      addNotMatchingRuleToSeriesType(seriesType)

      val series = addTestDicomData()

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMessage(3.seconds)

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(0)
    }

    "remove old series types" in {

      val seriesType = addSeriesType()

      addNotMatchingRuleToSeriesType(seriesType)

      val series = addTestDicomData()

      await(seriesTypeDao.upsertSeriesSeriesType(SeriesSeriesType(series.id, seriesType.id)))

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMessage(3.seconds)

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(0)
    }

    "be able to update series type for all series" in {
      val series1 = addTestDicomData()
      val series2 = addTestDicomData(sopInstanceUID = "sop id 2", seriesInstanceUID = "series 2")

      val seriesType = addSeriesType()
      addMatchingRuleToSeriesType(seriesType)

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series1.id)
      expectNoMessage(3.seconds)
      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series2.id)
      expectNoMessage(3.seconds)
      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series1).size should be(1)
      seriesSeriesTypesForSeries(series2).size should be(1)
    }

    "be able to match rule with more than one attribute" in {
      val seriesType = addSeriesType()
      val seriesTypeRule = addSeriesTypeRule(seriesType)
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "xyz")
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientSex, "M")

      val series = addTestDicomData(patientName = "xyz", patientSex = "M")

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMessage(3.seconds)

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(1)
    }

    "only match rule where all attributes matches" in {
      val seriesType = addSeriesType()
      val seriesTypeRule = addSeriesTypeRule(seriesType)
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "xyz")
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientSex, "M")

      val series = addTestDicomData(patientName = "xyz")

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMessage(3.seconds)

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(0)
    }
  }

  def addTestDicomData(
                        sopInstanceUID: String = "sop id 1",
                        seriesInstanceUID: String = "series 1",
                        patientName: String = "abc",
                        patientSex: String = "F"): Series = {

    val elements = TestUtil.createElements(
      sopInstanceUID = sopInstanceUID,
      seriesInstanceUID = seriesInstanceUID,
      patientName = patientName,
      patientSex = patientSex)

    val source = Source(SourceType.BOX, "remote box", 1)

    Await.result(
      metaDataService.ask(AddMetaData(elements, source))
        .mapTo[MetaDataAdded]
        .flatMap { metaData =>
          StreamSource.single(TestUtil.toBytes(elements))
            .runWith(storage.fileSink(metaData.image.id.toString))
            .map(_ => metaData.series)
        }, 30.seconds)
  }

  def addSeriesType(): SeriesType =
    await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

  def addMatchingRuleToSeriesType(seriesType: SeriesType): Unit = {
    val seriesTypeRule = addSeriesTypeRule(seriesType)

    addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "abc")
  }

  def addNotMatchingRuleToSeriesType(seriesType: SeriesType): Unit = {
    val seriesTypeRule = addSeriesTypeRule(seriesType)

    addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "123")
  }

  def addSeriesTypeRule(seriesType: SeriesType): SeriesTypeRule =
    await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, seriesType.id)))

  def addSeriesTypeRuleAttribute(seriesTypeRule: SeriesTypeRule,
                                 tag: Int,
                                 values: String): SeriesTypeRuleAttribute =
    await(seriesTypeDao.insertSeriesTypeRuleAttribute(
      SeriesTypeRuleAttribute(-1,
        seriesTypeRule.id,
        tag,
        Keyword.valueOf(tag),
        None,
        None,
        values)))

  def waitForSeriesTypesUpdateCompletion(): Unit = {
    val nAttempts = 10
    var attempt = 1
    var statusUpdateRunning = true
    while (statusUpdateRunning && attempt <= nAttempts) {
      seriesTypeUpdateService ! GetUpdateSeriesTypesRunningStatus

      expectMsgPF() {
        case UpdateSeriesTypesRunningStatus(running) => statusUpdateRunning = running
      }

      Thread.sleep(1000)
      attempt += 1
    }
  }

  def seriesSeriesTypesForSeries(series: Series): Seq[SeriesSeriesType] =
    await(seriesTypeDao.listSeriesSeriesTypesForSeriesId(series.id))
}
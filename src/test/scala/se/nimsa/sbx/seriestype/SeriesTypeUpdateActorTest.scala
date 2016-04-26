package se.nimsa.sbx.seriestype

import java.nio.file.Files

import akka.actor.ActorSelection.toScala
import akka.actor.{Props, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout.durationToTimeout
import akka.pattern.ask
import akka.util.Timeout
import org.dcm4che3.data.{Keyword, Tag}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.DicomHierarchy.Series
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.StorageProtocol.AddDataset
import se.nimsa.sbx.storage.{FileStorage, StorageServiceActor}
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class SeriesTypeUpdateActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("SeriesTypesUpdateActorSystem"))

  val db = Database.forURL("jdbc:h2:mem:seriestypeserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val fileStorage = Files.createTempDirectory("slicebox-test-storage-")

  val seriesTypeDao = new SeriesTypeDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)
  val propertiesDao = new PropertiesDAO(H2Driver)

  implicit val timeout = Timeout(30.seconds)
  implicit val ec = system.dispatcher

  db.withSession { implicit session =>
    seriesTypeDao.create
    metaDataDao.create
    propertiesDao.create
  }

  val storageService = system.actorOf(StorageServiceActor.props(new FileStorage(fileStorage.toString)), name = "StorageService")
  val metaDataService = system.actorOf(MetaDataServiceActor.props(dbProps), name = "MetaDataService")
  val seriesTypeService = system.actorOf(SeriesTypeServiceActor.props(dbProps, timeout), name = "SeriesTypeService")
  val seriesTypeUpdateService = system.actorSelection("user/SeriesTypeService/SeriesTypeUpdate")

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(fileStorage)
  }

  override def afterEach() {
    db.withSession { implicit session =>
      seriesTypeDao.clear
      metaDataDao.clear
      propertiesDao.clear
    }
  }

  "A SeriesTypeUpdateActor" should {

    "be able to add matching series type for series" in {

      val seriesType = addSeriesType()
      addMatchingRuleToSeriesType(seriesType)

      val series = addTestDataset()

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMsg

      waitForSeriesTypesUpdateCompletion()

      val seriesSeriesTypes = seriesSeriesTypesForSeries(series)

      seriesSeriesTypes.size should be(1)
      seriesSeriesTypes.head.seriesTypeId should be(seriesType.id)
    }

    "not add series type that do not match series" in {

      val seriesType = addSeriesType()
      addNotMatchingRuleToSeriesType(seriesType)

      val series = addTestDataset()

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMsg

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(0)
    }

    "remove old series types" in {

      val seriesType = addSeriesType()

      addNotMatchingRuleToSeriesType(seriesType)

      val series = addTestDataset()

      db.withSession { implicit session =>
        seriesTypeDao.insertSeriesSeriesType(SeriesSeriesType(series.id, seriesType.id))
      }

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMsg

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(0)
    }

    "be able to update series type for all series" in {
      val series1 = addTestDataset(sopInstanceUID = "sop id 1", seriesInstanceUID = "series 1")
      val series2 = addTestDataset(sopInstanceUID = "sop id 2", seriesInstanceUID = "series 2")

      val seriesType = addSeriesType()
      addMatchingRuleToSeriesType(seriesType)

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series1.id)
      expectNoMsg
      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series2.id)
      expectNoMsg
      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series1).size should be(1)
      seriesSeriesTypesForSeries(series2).size should be(1)
    }

    "be able to match rule with more than one attribute" in {
      val seriesType = addSeriesType()
      val seriesTypeRule = addSeriesTypeRule(seriesType)
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "xyz")
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientSex, "M")

      val series = addTestDataset(patientName = "xyz", patientSex = "M")

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMsg

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(1)
    }

    "only match rule where all attributes matches" in {
      val seriesType = addSeriesType()
      val seriesTypeRule = addSeriesTypeRule(seriesType)
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "xyz")
      addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientSex, "M")

      val series = addTestDataset(patientName = "xyz", patientSex = "F")

      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      expectNoMsg

      waitForSeriesTypesUpdateCompletion()

      seriesSeriesTypesForSeries(series).size should be(0)
    }
  }

  def addTestDataset(
                      sopInstanceUID: String = "sop id 1",
                      seriesInstanceUID: String = "series 1",
                      patientName: String = "abc",
                      patientSex: String = "F"): Series = {

    val dataset = TestUtil.createDataset(
      sopInstanceUID = sopInstanceUID,
      seriesInstanceUID = seriesInstanceUID,
      patientName = patientName,
      patientSex = patientSex)

    val source = Source(SourceType.BOX, "remote box", 1)

    Await.result(
      metaDataService.ask(AddMetaData(dataset, source))
        .mapTo[MetaDataAdded]
        .flatMap { metaData =>
          storageService.ask(AddDataset(dataset, source, metaData.image))
        }.map { _ =>
        val series = db.withSession { implicit session =>
          metaDataDao.series
        }
        series.last
      }, 30.seconds)
  }

  def addSeriesType(): SeriesType =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
    }

  def addMatchingRuleToSeriesType(seriesType: SeriesType): Unit = {
    val seriesTypeRule = addSeriesTypeRule(seriesType)

    addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "abc")
  }

  def addNotMatchingRuleToSeriesType(seriesType: SeriesType): Unit = {
    val seriesTypeRule = addSeriesTypeRule(seriesType)

    addSeriesTypeRuleAttribute(seriesTypeRule, Tag.PatientName, "123")
  }

  def addSeriesTypeRule(seriesType: SeriesType): SeriesTypeRule =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, seriesType.id))
    }

  def addSeriesTypeRuleAttribute(
                                  seriesTypeRule: SeriesTypeRule,
                                  tag: Int,
                                  values: String): SeriesTypeRuleAttribute =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRuleAttribute(
        SeriesTypeRuleAttribute(-1,
          seriesTypeRule.id,
          tag,
          Keyword.valueOf(tag),
          None,
          None,
          values))
    }

  def waitForSeriesTypesUpdateCompletion(): Unit = {
    val nAttempts = 10
    var attempt = 1
    var statusUpdateRunning = true
    while (statusUpdateRunning && attempt <= nAttempts) {
      seriesTypeUpdateService ! GetUpdateSeriesTypesRunningStatus

      expectMsgPF() { case UpdateSeriesTypesRunningStatus(running) => statusUpdateRunning = running }

      Thread.sleep(500)
      attempt += 1
    }
  }

  def seriesSeriesTypesForSeries(series: Series): Seq[SeriesSeriesType] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesSeriesTypesForSeriesId(series.id)
    }
}
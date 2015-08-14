package se.nimsa.sbx.seriestype

import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.StorageServiceActor
import java.nio.file.Files
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import org.dcm4che3.util.TagUtils
import org.dcm4che3.data.Tag
import com.sun.istack.internal.NotNull
import scala.concurrent.duration.DurationInt


class SeriesTypeUpdateActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("SeriesTypesUpdateActorSystem"))

  val db = Database.forURL("jdbc:h2:mem:seriestypeserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)
  
  val storage = Files.createTempDirectory("slicebox-test-storage-")
  
  val seriesTypeDao = new SeriesTypeDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)
  
  db.withSession { implicit session =>
    seriesTypeDao.create
    metaDataDao.create
  }
  
  val seriesTypeUpdateService = system.actorOf(SeriesTypeUpdateActor.props(dbProps), name = "SeriesTypeUpdateService")
  val storageService = system.actorOf(StorageServiceActor.props(dbProps, storage), name = "StorageService")
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }
  
  override def beforeEach() {
    db.withSession { implicit session =>
      seriesTypeDao.create
    }
  }
  
  override def afterEach() {
    db.withSession { implicit session =>
      seriesTypeDao.drop
      metaDataDao.clear
    }
  }
  
  
  "A SeriesTypeUpdateActor" should {
    
    "be able to add matching series type for series" in {
      
      val seriesType = addSeriesType()
      addMatchingRuleToSeriesType(seriesType)
      
      val series = addTestDataset()
      
      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      
      waitForSeriesTypesUpdateCompletion()
      
      val seriesSeriesTypes = seriesSeriesTypesForSeries(series)
      
      seriesSeriesTypes.size should be(1)
      seriesSeriesTypes(0).seriesTypeId should be(seriesType.id)
    }
    
    "not add series type that do not match series" in {
      
      val seriesType = addSeriesType()
      addNotMatchingRuleToSeriesType(seriesType)
      
      val series = addTestDataset()
      
      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(series.id)
      
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
      
      waitForSeriesTypesUpdateCompletion()
      
      seriesSeriesTypesForSeries(series).size should be(0)
    }
    
    "be able to update series type for all series" in {
      val series1 = addTestDataset(sopInstanceUID = "sop id 1", seriesInstanceUID = "series 1")
      val series2 = addTestDataset(sopInstanceUID = "sop id 2", seriesInstanceUID = "series 2")
      
      val seriesType = addSeriesType()
      addMatchingRuleToSeriesType(seriesType)
      
      seriesTypeUpdateService ! UpdateSeriesTypesForAllSeries
      
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
      
    storageService ! AddDataset(dataset, SourceType.UNKNOWN, -1)
    expectMsgPF() {
      case ImageAdded(image) => true
    }
    
    val series = db.withSession { implicit session =>
        metaDataDao.series
      }
    
    series.last
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
      value: String): SeriesTypeRuleAttribute =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRuleAttribute(
          SeriesTypeRuleAttribute(-1,
              seriesTypeRule.id,
              TagUtils.groupNumber(tag),
              TagUtils.elementNumber(tag),
              None,
              value))
    }
  
  def waitForSeriesTypesUpdateCompletion(): Unit = {
    var statusUpdateRunning = true
    
    while (statusUpdateRunning) {
      seriesTypeUpdateService ! GetUpdateSeriesTypesRunningStatus
      
      expectMsgPF() {
        case UpdateSeriesTypesRunningStatus(running) => statusUpdateRunning = running
      }
    }
  }
  
  def seriesSeriesTypesForSeries(series: Series): Seq[SeriesSeriesType] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesSeriesTypesForSeriesId(series.id)
    }
}
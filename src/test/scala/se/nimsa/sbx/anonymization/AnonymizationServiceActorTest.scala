package se.nimsa.sbx.box

import java.util.Date

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import se.nimsa.sbx.anonymization.AnonymizationDAO
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.app.DbProps

class AnonymizationServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("AnonymizationServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:anonymizationserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val anonymizationDao = new AnonymizationDAO(H2Driver)

  db.withSession { implicit session =>
    anonymizationDao.create
  }

  val anonymizationService = system.actorOf(Props(new AnonymizationServiceActor(dbProps)), name = "AnonymizationService")

  override def afterEach() =
    db.withSession { implicit session =>
      anonymizationDao.clear
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An AnonymizationServiceActor" should {

    "harmonize anonymization with respect to relevant anonymization keys when sending a file" in {
      db.withSession { implicit session =>
        val dataset = createDataset
        val key = insertAnonymizationKey
        anonymizationService ! Anonymize(dataset, Seq.empty)
        expectMsgPF() {
          case harmonized: Attributes =>
            harmonized.getString(Tag.PatientID) should be(key.anonPatientID)
            harmonized.getString(Tag.StudyInstanceUID) should be(key.anonStudyInstanceUID)
            harmonized.getString(Tag.SeriesInstanceUID) should be(key.anonSeriesInstanceUID)
            harmonized.getString(Tag.FrameOfReferenceUID) should be(key.anonFrameOfReferenceUID)
        }
      }
    }

    "reverse anonymization in an anonymous dataset based on anonymization keys" in {
      db.withSession { implicit session =>
        val key = insertAnonymizationKey
        val dataset = createDataset
        val anonymizedDataset = anonymizeDataset(dataset)
        anonymizedDataset.setString(Tag.PatientName, VR.PN, key.anonPatientName)
        anonymizedDataset.setString(Tag.PatientID, VR.SH, key.anonPatientID)
        anonymizedDataset.setString(Tag.StudyInstanceUID, VR.SH, key.anonStudyInstanceUID)
        anonymizationService ! ReverseAnonymization(anonymizedDataset)

        expectMsgPF() {
          case reversed: Attributes =>
            reversed.getString(Tag.PatientName) should be(key.patientName)
            reversed.getString(Tag.PatientID) should be(key.patientID)
            reversed.getString(Tag.StudyInstanceUID) should be(key.studyInstanceUID)
            reversed.getString(Tag.StudyDescription) should be(key.studyDescription)
            reversed.getString(Tag.StudyID) should be(key.studyID)
            reversed.getString(Tag.AccessionNumber) should be(key.accessionNumber)

        }
      }
    }
  }

  def createDataset = {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientName, VR.LO, "p1")
    dataset.setString(Tag.PatientID, VR.LO, "s1")
    dataset.setString(Tag.StudyInstanceUID, VR.LO, "stuid1")
    dataset.setString(Tag.SeriesInstanceUID, VR.LO, "seuid1")
    dataset.setString(Tag.FrameOfReferenceUID, VR.LO, "frid1")
    dataset
  }

  def insertAnonymizationKey(implicit session: H2Driver.simple.Session) = {
    val key = AnonymizationKey(-1, new Date().getTime,
      "p1", "anon p1",
      "s1", "anon s1",
      "2000-01-01",
      "stuid1", "anon stuid1",
      "stdesc1", "stid1", "acc1",
      "seuid1", "anon seuid1",
      "frid1", "anon frid1")
    anonymizationDao.insertAnonymizationKey(key)
    key
  }

}
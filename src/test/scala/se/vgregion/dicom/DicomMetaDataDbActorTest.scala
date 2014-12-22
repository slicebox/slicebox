package se.vgregion.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import se.vgregion.app.DbProps
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import DicomDispatchProtocol._
import DicomHierarchy._
import DicomMetaDataProtocol._
import DicomPropertyValue._

class DicomMetaDataDbActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("DicomMetaDataDbActorTestSystem"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  private val db = Database.forURL("jdbc:h2:mem:dbtest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  
  val dbProps = DbProps(db, H2Driver)
  
  val dbActor = system.actorOf(DicomMetaDataDbActor.props(dbProps), "DbActor")

  val pat1 = Patient(PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val pat2 = Patient(PatientName("p2"), PatientID("s2"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  
  val study1 = Study(pat1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"))
  val study2 = Study(pat2, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("20000101"), StudyID("stid2"), AccessionNumber("acc2"))

  val series1 = Series(study1, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val series2 = Series(study2, Equipment(Manufacturer("manu2"), StationName("station2")), FrameOfReference(FrameOfReferenceUID("frid2")), SeriesInstanceUID("souid2"), SeriesDescription("sedesc2"), SeriesDate("20000101"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"))
  val series3 = Series(study1, Equipment(Manufacturer("manu3"), StationName("station3")), FrameOfReference(FrameOfReferenceUID("frid3")), SeriesInstanceUID("souid3"), SeriesDescription("sedesc3"), SeriesDate("20010101"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart3"))

  val image1 = Image(series1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"))
  val image2 = Image(series2, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"))
  val image3 = Image(series3, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"))
  
  val imageFile1 = ImageFile(image1, FileName("file1"), Owner("Owner1"))
  val imageFile2 = ImageFile(image2, FileName("file2"), Owner("Owner1"))
  val imageFile3 = ImageFile(image3, FileName("file3"), Owner("Owner1"))
  
  dbActor ! Initialize
  
  "A Dicom MetaData Db-Actor" must {

    "return an empty list when no metadata exists" in {
      dbActor ! GetImageFiles
      expectMsg(ImageFiles(Seq.empty[ImageFile]))
    }

    "return a list of size three when three s are added" in {
      dbActor ! AddImageFile(imageFile1)
      dbActor ! AddImageFile(imageFile2)
      dbActor ! AddImageFile(imageFile3)
      expectNoMsg
      dbActor ! GetImageFiles
      expectMsgPF() {
        case ImageFiles(imageFiles) if imageFiles.size == 3 => true
      }
    }
    
    "produce properly grouped entries" in {
      dbActor ! GetPatients
      expectMsg(Patients(Seq(pat1, pat2)))

      dbActor ! GetStudies(pat1)
      expectMsg(Studies(Seq(study1)))      
      dbActor ! GetStudies(pat2)
      expectMsg(Studies(Seq(study2)))
      
      dbActor ! GetSeries(study1)
      expectMsg(SeriesCollection(Seq(series1, series3)))
      dbActor ! GetSeries(study2)
      expectMsg(SeriesCollection(Seq(series2)))
      
      dbActor ! GetImages(series1)
      expectMsg(Images(Seq(image1)))
      dbActor ! GetImages(series2)
      expectMsg(Images(Seq(image2)))
      dbActor ! GetImages(series3)
      expectMsg(Images(Seq(image3)))
      
      dbActor ! GetImageFiles(image1)
      expectMsg(ImageFiles(Seq(imageFile1)))
      dbActor ! GetImageFiles(image2)
      expectMsg(ImageFiles(Seq(imageFile2)))
      dbActor ! GetImageFiles(image3)
      expectMsg(ImageFiles(Seq(imageFile3)))
    }
  }

}

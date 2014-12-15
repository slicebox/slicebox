package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.http.StatusCodes._
import spray.http.ContentTypes._
import java.nio.file.Files
import java.nio.file.Paths
import se.vgregion.dicom.DicomPropertyValue._
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.dicom.directory.DirectoryWatchProtocol._
import spray.httpx.SprayJsonSupport._

class FileRoutesTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The system" should "respond with file data when asked to deliver a file" in {

    val tempDir = Files.createTempDirectory("akka-dcm-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")
    val monitorDir = MonitorDir(tempDirName)
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    val pat = Patient(PatientName(""), PatientID(""), PatientBirthDate(""), PatientSex(""))
    val study = Study(pat, StudyInstanceUID(""), StudyDescription(""), StudyDate(""), StudyID(""), AccessionNumber(""))
    val series = Series(study, Equipment(Manufacturer(""), StationName("")), FrameOfReference(FrameOfReferenceUID("")), SeriesInstanceUID(""), SeriesDescription(""), SeriesDate(""), Modality(""), ProtocolName(""), BodyPartExamined(""))
    val image = Image(series, SOPInstanceUID(""), ImageType(""))
    val imageFile = ImageFile(image, FileName(tempDir.resolve(fileName).toString), Owner("TestOwner"))

    Get("/api/file/image", imageFile) ~> routes ~> check {
      status should be(OK)
      contentType should be(`application/octet-stream`)
    }
  }
  
  override def setupDevelopmentEnvironment() = {
  }

}
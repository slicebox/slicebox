package se.nimsa.sbx.app

import java.io.File
import spray.http.BodyPart
import spray.http.MultipartFormData
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.ContentTypes
import se.nimsa.sbx.dicom.DicomUtil
import spray.http.StatusCodes._
import se.nimsa.sbx.dicom.DicomHierarchy._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import java.nio.file.Path
import java.nio.file.Paths
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import spray.http.HttpData
import se.nimsa.sbx.storage.StorageProtocol.ImageAttribute
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationDAO
import java.util.Date
import scala.slick.driver.H2Driver

class ImageRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:datasetroutestest;DB_CLOSE_DELAY=-1"

  val dao = new AnonymizationDAO(H2Driver)
  
  override def afterEach() {
    db.withSession { implicit session =>
      dao.clear
    }
  }

  "Image routes" should "return a success message when adding an image" in {
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be (Created)
      val image = responseAs[Image]
      image.id should be(1)
    }
  }

  it should "allow fetching the image again" in {
    GetAsUser("/api/images/1") ~> routes ~> check {
      contentType should be (ContentTypes.`application/octet-stream`)      
      val dataset = DicomUtil.loadDataset(responseAs[Array[Byte]], true)
      dataset should not be (null)
    }
  }
  
  it should "return NotFound when requesting an image that does not exist" in {
    GetAsUser("/api/images/2") ~> routes ~> check {
      status should be (NotFound)
    }
  }
  
  it should "return a BadRequest when adding an invalid image" in {
    val file = new File("some file that does not exist")
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be (BadRequest)
    }    
  }

  it should "return a BadRequest when adding a file leads to an ill-defined DICOM hierarchy" in {
    // file already added once. Change some DICOM attributes in order to create an invalid hierarchy
    val fileName = "anon270.dcm"
    val dataset = DicomUtil.loadDataset(Paths.get(getClass().getResource(fileName).toURI()), true)
    dataset.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4") // leads to a new db study
    dataset.setString(Tag.SOPInstanceUID, VR.UI, "5.6.7.8") // leads to a new db image
    val bytes = DicomUtil.toByteArray(dataset)
    PostAsUser("/api/images", HttpData(bytes)) ~> routes ~> check {
      status should be (BadRequest)
    }
  }
  
  it should "return a non-empty list of attributes when listing attributes for an image" in {
    GetAsUser("/api/images/1/attributes") ~> routes ~> check {
      status should be (OK)
      responseAs[List[ImageAttribute]].size should be > (0)
    }
  }
  
  it should "return NotFound when listing attributes for an image that does not exist" in {
    GetAsUser("/api/images/2/attributes") ~> routes ~> check {
      status should be (NotFound)
    }
  }
  
  it should "return a non-empty array of bytes when requesting a png represenation of an image" in {
    GetAsUser("/api/images/1/png") ~> routes ~> check {
      status should be (OK)
      responseAs[Array[Byte]].size should be > (0)
    }
  }
  
  it should "return NotFound when requesting a png for an image that does not exist" in {
    GetAsUser("/api/images/2/png") ~> routes ~> check {
      status should be (NotFound)
    }
  }
    
  it should "anonymize the data by removing the old data and inserting new anonymized data upon manual anonymization" in {
    val patient = GetAsUser("/api/metadata/patients/1") ~> routes ~> check {
      status should be (OK)
      responseAs[Patient]
    }
    PutAsUser("/api/images/1/anonymize") ~> routes ~> check {
      status should be (NoContent)
    }
    GetAsUser("/api/metadata/patients/1") ~> routes ~> check {
      status should be (NotFound)      
    }
    GetAsUser("/api/metadata/patients/2") ~> routes ~> check {
      status should be (OK)
      val anonPatient = responseAs[Patient]
      anonPatient.patientName should not be (patient.patientName)
      anonPatient.patientID should not be (patient.patientID)
      anonPatient.patientSex should be (patient.patientSex)
    }
    GetAsUser("/api/metadata/studies/1") ~> routes ~> check {
      status should be (NotFound)            
    }
    GetAsUser("/api/metadata/studies/2") ~> routes ~> check {
      status should be (OK)            
    }
    GetAsUser("/api/metadata/series/1") ~> routes ~> check {
      status should be (NotFound)            
    }
    GetAsUser("/api/metadata/series/2") ~> routes ~> check {
      status should be (OK)            
    }
    GetAsUser("/api/metadata/images/1") ~> routes ~> check {
      status should be (NotFound)            
    }
    GetAsUser("/api/metadata/images/2") ~> routes ~> check {
      status should be (OK)            
    }
  }
  
  it should "return NotFound when manually anonymizing an image that does not exist" in {
    PutAsUser("/api/images/3/anonymize") ~> routes ~> check {
      status should be (NotFound)
    }
  }

  it should "support deleting and image" in {
    GetAsUser("/api/metadata/images/2") ~> routes ~> check {
      status should be (OK)            
    }
    DeleteAsUser("/api/images/2") ~> routes ~> check {
      status should be (NoContent)
    }
    GetAsUser("/api/metadata/images/2") ~> routes ~> check {
      status should be (NotFound)            
    }
  }
  
  it should "provide a list of anonymization keys" in {
    db.withSession { implicit session =>
      val key1 = AnonymizationKey(-1, new Date().getTime, "pat name", "anon pat name", "pat id", "anon pat id", "19700101", "stuid", "anon stuid", "study desc", "study id", "acc num", "seuid", "anon seuid", "foruid", "anon foruid")
      val key2 = key1.copy(patientName = "pat name 2", anonPatientName = "anon pat name 2")
      val insertedKey1 = dao.insertAnonymizationKey(key1)
      val insertedKey2 = dao.insertAnonymizationKey(key2)
      GetAsUser("/api/images/anonymizationkeys") ~> routes ~> check {
        status should be(OK)
        responseAs[List[AnonymizationKey]] should be(List(insertedKey1, insertedKey2))
      }
    }
  }

  it should "provide a list of sorted anonymization keys supporting startindex and count" in {
    db.withSession { implicit session =>
      val key1 = AnonymizationKey(-1, new Date().getTime, "B", "anon B", "pat id", "anon pat id", "19700101", "stuid", "anon stuid", "study desc", "study id", "acc num", "seuid", "anon seuid", "foruid", "anon foruid")
      val key2 = key1.copy(patientName = "A", anonPatientName = "anon A")
      val insertedKey1 = dao.insertAnonymizationKey(key1)
      val insertedKey2 = dao.insertAnonymizationKey(key2)
      GetAsUser("/api/images/anonymizationkeys?startindex=0&count=1&orderby=patientname&orderascending=true") ~> routes ~> check {
        status should be(OK)
        val keys = responseAs[List[AnonymizationKey]]
        keys.length should be(1)
        keys(0) should be(insertedKey2)
      }
    }
  }

  it should "respond with 400 Bad Request when sorting anonymization keys by a non-existing property" in {
    GetAsUser("/api/images/anonymizationkeys?orderby=xyz") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

}
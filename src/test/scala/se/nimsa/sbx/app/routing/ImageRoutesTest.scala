package se.nimsa.sbx.app.routing

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import org.dcm4che3.data.Tag
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.TagPath
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.metadata.MetaDataProtocol.SeriesTag
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.storage.StorageProtocol.{ExportSetId, ImageInformation, TagMapping}
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class ImageRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("imageroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  override def afterEach() {
    await(metaDataDao.clear())
    storage.asInstanceOf[RuntimeStorage].clear()
  }

  "Image routes" should "return 201 Created when adding an image using multipart form data" in {
    PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id.toInt should be > 0
    }
  }

  it should "return 201 Created when adding an image as a byte array" in {
    PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id.toInt should be > 0
    }
  }

  it should "return 200 OK when adding an image that has already been added" in {
    PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      status should be(Created)
    }
    PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "200 OK and the specified dataset when fetching an added image" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe OK
      contentType should be(ContentTypes.`application/octet-stream`)
      val dicomData = TestUtil.loadDicomData(responseAs[ByteString].toArray, withPixelData = true)
      dicomData should not be null
    }
  }

  it should "return 404 NotFound when requesting an image that does not exist" in {
    GetAsUser("/api/images/2") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 201 Created when adding a secondary capture image" in {
    PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
      status should be(Created)
    }
  }

  it should "return 400 BadRequest when adding an invalid image" in {
    PostAsUser("/api/images", HttpEntity(ByteString(1, 2, 3, 4))) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 200 OK and a non-empty list of attributes when listing attributes for an image" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    GetAsUser(s"/api/images/${image.id}/attributes") ~> routes ~> check {
      status should be(OK)
      responseAs[List[ImageAttribute]].size should be > 0
    }
  }

  it should "return 404 NotFound when listing attributes for an image that does not exist" in {
    GetAsUser("/api/images/666/attributes") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return a 200 OK and the bytes of a PNG image when asking for a PNG rendering of an image frame" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    GetAsUser(s"/api/images/${image.id}/png") ~> routes ~> check {
      status shouldBe OK
      val pngBytes = responseAs[ByteString]
      val bufferedImage = ImageIO.read(new ByteArrayInputStream(pngBytes.toArray))
      bufferedImage.getWidth shouldBe >(0)
      bufferedImage.getHeight shouldBe >(0)
    }
  }

  it should "return a 200 OK and an image of different intensity when asking for PNG rending of an image frame with a specific intensity window" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val png1 =
      GetAsUser(s"/api/images/${image.id}/png") ~> routes ~> check {
        responseAs[ByteString]
      }
    val png2 =
      GetAsUser(s"/api/images/${image.id}/png?windowmin=10&windowmax=100") ~> routes ~> check {
        responseAs[ByteString]
      }
    png1 should not equal png2
  }

  it should "return 200 OK and the bytes of a PNG image of the requested height when asking for a PNG rendering of an image frame with a specific height" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    GetAsUser(s"/api/images/${image.id}/png?imageheight=400") ~> routes ~> check {
      status shouldBe OK
      val pngBytes = responseAs[ByteString]
      val bufferedImage = ImageIO.read(new ByteArrayInputStream(pngBytes.toArray))
      bufferedImage.getHeight shouldBe 400
    }
  }
  it should "return 404 NotFound when requesting a png for an image that does not exist" in {
    GetAsUser("/api/images/666/png") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 201 Created when adding a jpeg image to a study" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check {
      responseAs[Series]
    }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check {
      responseAs[Study]
    }
    PostAsUser(s"/api/images/jpeg?studyid=${study.id}", HttpEntity(TestUtil.jpegByteArray)) ~> routes ~> check {
      status shouldBe Created
      val image = responseAs[Image]
      image shouldNot be(null)
    }
  }

  it should "return 200 OK and the added secondary capture with series description filled out" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check {
      responseAs[Series]
    }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check {
      responseAs[Study]
    }
    val description = "this is the description"
    val sc =
      PostAsUser(Uri(s"/api/images/jpeg").withQuery(Query("studyid" -> study.id.toString, "description" -> description)).toString, HttpEntity(TestUtil.jpegByteArray)) ~> routes ~> check {
        responseAs[Image]
      }
    GetAsUser(s"/api/images/${sc.id}") ~> routes ~> check {
      status shouldBe OK
      val dcmBytes = responseAs[ByteString]
      val dcm = TestUtil.loadDicomData(dcmBytes.toArray, withPixelData = false)
      dcm.attributes.getString(Tag.SeriesDescription) shouldBe description
    }
  }

  it should "return 404 NotFound when adding a jpeg image to a study that does not exist" in {
    PostAsUser("/api/images/jpeg?studyid=666", HttpEntity(TestUtil.jpegByteArray)) ~> routes ~> check {
      status shouldBe NotFound
    }
  }

  it should "return 400 BadRequest when adding an invalid jpeg image" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check {
      responseAs[Series]
    }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check {
      responseAs[Study]
    }
    PostAsUser(s"/api/images/jpeg?studyid=${study.id}", HttpEntity(ByteString(1, 2, 3, 4))) ~> routes ~> check {
      status shouldBe BadRequest
    }
  }

  it should "return 200 OK and a non-empty array of bytes when requesting png data for a secondary capture jpeg image" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check {
      responseAs[Series]
    }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check {
      responseAs[Study]
    }
    val pngImage = PostAsUser(s"/api/images/jpeg?studyid=${study.id}", HttpEntity(TestUtil.jpegByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    GetAsUser(s"/api/images/${pngImage.id}/png") ~> routes ~> check {
      status should be(OK)
      responseAs[ByteString] should not be empty
    }
  }

  it should "support deleting an image" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    GetAsUser(s"/api/metadata/images/${image.id}") ~> routes ~> check {
      status should be(OK)
    }
    DeleteAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser(s"/api/metadata/images/${image.id}") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return 204 NoContent when deleting a sequence of images" in {
    val image =
      PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe OK
    }

    PostAsUser("/api/images/delete", Seq(image.id)) ~> routes ~> check {
      status shouldBe NoContent
    }

    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe NotFound
    }
  }

  it should "return 204 NoContent even though one or more image ids are invalid when deleting a sequence of images (idempotence)" in {
    val image =
      PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    PostAsUser("/api/images/delete", Seq(image.id, 666, 667, 668)) ~> routes ~> check {
      status shouldBe NoContent
    }
  }

  it should "return a 200 OK and a structure of image information on the selected image" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val dicomData = TestUtil.loadDicomData(TestUtil.testImageByteArray, withPixelData = false)
    GetAsUser(s"/api/images/${image.id}/imageinformation") ~> routes ~> check {
      status shouldBe OK
      val info = responseAs[ImageInformation]
      info.minimumPixelValue shouldBe dicomData.attributes.getInt(Tag.SmallestImagePixelValue, 0)
      info.maximumPixelValue shouldBe dicomData.attributes.getInt(Tag.LargestImagePixelValue, 0)
      info.numberOfFrames shouldBe dicomData.attributes.getInt(Tag.NumberOfFrames, 1)
    }
  }

  it should "return 200 OK and an ID which is related to a set of images to export" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    PostAsUser("/api/images/export", Seq(image.id)) ~> routes ~> check {
      status shouldBe OK
      val exportSetId = responseAs[ExportSetId]
      exportSetId.id.toInt should be > 0
    }
  }

  it should "return 200 OK and a valid zip file sent with chunked encoding when exporting" in {
    val image = PostAsUser("/api/images", HttpEntity(TestUtil.testImageByteArray)) ~> routes ~> check {
      responseAs[Image]
    }
    val exportSetId =
      PostAsUser("/api/images/export", Seq(image.id)) ~> routes ~> check {
        responseAs[ExportSetId].id
      }
    val byteChunks =
      GetAsUser(s"/api/images/export?id=$exportSetId") ~> routes ~> check {
        status shouldBe OK
        contentType shouldBe ContentTypes.`application/octet-stream`
        chunks
      }
    byteChunks should not be empty
    val zipBytes = byteChunks.map(_.data.toArray)
    zipBytes should not be empty
    isValidZip(zipBytes.toArray.flatten) shouldBe true
  }

  def isValidZip(zipBytes: Array[Byte]): Boolean = {
    val stream = new ZipInputStream(new ByteArrayInputStream(zipBytes))
    stream.getNextEntry should not be null
    stream.close()
    true
  }

  it should "modify and replace old data and transfer source and series tags when modifying an image" in {
    // create test data and verify original values
    val testData = TestUtil.testImageDicomData()
    testData.attributes.getString(Tag.PatientAge) shouldBe "011Y"
    testData.attributes.getString(Tag.RescaleSlope) shouldBe null
    testData.attributes
      .getNestedDataset(Tag.EnergyWindowInformationSequence)
      .getNestedDataset(Tag.EnergyWindowRangeSequence, 1)
      .getString(Tag.EnergyWindowUpperLimit) shouldBe "147"

    // upload original data
    val testDataArray = TestUtil.toByteArray(testData)
    val image = PostAsUser("/api/images", HttpEntity(testDataArray)) ~> routes ~> check {
      responseAs[Image]
    }

    // get original source
    val originalSource = GetAsUser(s"/api/metadata/series/${image.seriesId}/source") ~> routes ~> check {
      responseAs[Source]
    }

    // add tags to uploaded series
    val tag1 = PostAsUser(s"/api/metadata/series/${image.seriesId}/seriestags", SeriesTag(-1, "Tag1")) ~> routes ~> check {
      responseAs[SeriesTag]
    }
    val tag2 = PostAsUser(s"/api/metadata/series/${image.seriesId}/seriestags", SeriesTag(-1, "Tag2")) ~> routes ~> check {
      responseAs[SeriesTag]
    }

    // define modifications
    val tagMappings = Seq(
      TagMapping(TagPath
        .fromTag(Tag.PatientAge), ByteString("123Y")), // standard replacement
      TagMapping(TagPath
        .fromTag(Tag.RescaleSlope), ByteString("2.5")), // insert new attribute
      TagMapping(TagPath
        .fromSequence(Tag.EnergyWindowInformationSequence)
        .thenSequence(Tag.EnergyWindowRangeSequence, 1)
        .thenTag(Tag.EnergyWindowUpperLimit), ByteString("999")) // modify item in sequence
    )

    // modify
    val modifiedImage = PutAsUser(s"/api/images/${image.id}/modify", tagMappings) ~> routes ~> check {
      status shouldBe Created
      responseAs[Image]
    }

    // verify that original data is deleted
    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe NotFound
    }

    // get modified data
    val modifiedData = GetAsUser(s"/api/images/${modifiedImage.id}") ~> routes ~> check {
      status shouldBe OK
      val bytes = responseAs[ByteString]
      TestUtil.loadDicomData(bytes.toArray, withPixelData = true)
    }

    // verify that modifications have taken effect
    modifiedData.attributes.getString(Tag.PatientAge) shouldBe "123Y"
    modifiedData.attributes.getString(Tag.RescaleSlope) shouldBe "2.5"
    modifiedData.attributes
      .getNestedDataset(Tag.EnergyWindowInformationSequence)
      .getNestedDataset(Tag.EnergyWindowRangeSequence, 1)
      .getString(Tag.EnergyWindowUpperLimit) shouldBe "999"

    // verify that original source has been transferred to modified data
    GetAsUser(s"/api/metadata/series/${modifiedImage.seriesId}/source") ~> routes ~> check {
      responseAs[Source] shouldBe originalSource
    }

    // verify that series tags have been transferred to modified data
    GetAsUser(s"/api/metadata/series/${modifiedImage.seriesId}/seriestags") ~> routes ~> check {
      responseAs[List[SeriesTag]].map(_.name) shouldBe List(tag1, tag2).map(_.name)
    }
  }

}
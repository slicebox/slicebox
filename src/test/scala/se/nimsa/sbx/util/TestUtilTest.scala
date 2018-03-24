package se.nimsa.sbx.util

import java.nio.file.Files

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import se.nimsa.dicom.Tag
import se.nimsa.sbx.dicom.DicomData

class TestUtilTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  "Creating a test database" should "create an in-memory database with the specified name" in {
    val name = "testname"
    val dbConfig = TestUtil.createTestDb(name)
    dbConfig.config.getString("db.url") shouldBe s"jdbc:h2:mem:./$name"
  }

  val tempDir = Files.createTempDirectory("slicebox-temp-dir-")

  "Loading a attributes" should "return a attributes" in {
    val dicomData = TestUtil.testImageDicomData()
    dicomData.isInstanceOf[DicomData] should be(true)
  }

  "Loading a attributes" should "return the same attributes, disregarding pixelData, when loading with and without pixelData" in {
    val dicomData1 = TestUtil.testImageDicomData(withPixelData = false)
    val dicomData2 = TestUtil.testImageDicomData()
    dicomData1 should not equal dicomData2
    dicomData1.attributes.remove(Tag.PixelData)
    dicomData2.attributes.remove(Tag.PixelData)
    dicomData1 should equal (dicomData2)
  }

  "loading and saving a attributes and loading it again" should "produce the same attributes twice" in {
    val attributes1 = TestUtil.testImageDicomData(withPixelData = false)
    val savePath = tempDir.resolve("attributes1.dcm")
    TestUtil.saveDicomData(attributes1, savePath)
    val attributes2 = TestUtil.loadDicomData(savePath, withPixelData = false)
    attributes1 should equal (attributes2)
  }

  override def afterAll() {
    TestUtil.deleteFolder(tempDir)
  }
}

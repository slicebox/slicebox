package se.nimsa.sbx.dicom

import java.nio.file.Files

import org.dcm4che3.data.{Attributes, Tag, VR}
import org.dcm4che3.util.TagUtils
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.util.TestUtil

class DicomUtilTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  "Validating a DICOM file" should "throw an exception for a non-supported context" in {
    val attributes = new Attributes()
    attributes.setString(Tag.MediaStorageSOPClassUID, VR.UI, SopClasses.SecondaryCaptureImageStorage.uid)
    attributes.setString(Tag.TransferSyntaxUID, VR.UI, TransferSyntaxes.ExplicitVrLittleEndian.uid)
    intercept[IllegalArgumentException] {
      checkContext(attributes, Contexts.imageDataContexts)
    }
  }

  it should "pass a supported context" in {
    val attributes = new Attributes()
    attributes.setString(Tag.MediaStorageSOPClassUID, VR.UI, SopClasses.NuclearMedicineImageStorage.uid)
    attributes.setString(Tag.TransferSyntaxUID, VR.UI, TransferSyntaxes.ExplicitVrLittleEndian.uid)
    checkContext(attributes, Contexts.imageDataContexts)
  }

  it should "throw an exception for a context with an unknown SOP Class UID" in {
    val notASopClassUid = "this is now a known UID" // any non-SopClassUID string is fine
    val attributes = new Attributes()
    attributes.setString(Tag.MediaStorageSOPClassUID, VR.UI, notASopClassUid)
    attributes.setString(Tag.TransferSyntaxUID, VR.UI, TransferSyntaxes.ExplicitVrLittleEndian.uid)
    intercept[IllegalArgumentException] {
      checkContext(attributes, Contexts.imageDataContexts)
    }
  }

  it should "throw an exception for a attributes with missing file meta information" in {
    val attributes = new Attributes()
    intercept[IllegalArgumentException] {
      checkContext(attributes, Contexts.imageDataContexts)
    }
  }

  val tempDir = Files.createTempDirectory("slicebox-temp-dir-")

  "Loading a attributes" should "return a attributes" in {
    val dicomData = TestUtil.testImageDicomData()
    dicomData.isInstanceOf[DicomData] should be(true)
  }

  "Loading a attributes" should "return the same attributes, disregarding pixelData, when loading with and without pixelData" in {
    val dicomData1 = TestUtil.testImageDicomData(withPixelData = false)
    val dicomData2 = TestUtil.testImageDicomData(withPixelData = true)
    dicomData1 should not equal dicomData2
    dicomData1.attributes.remove(Tag.PixelData)
    dicomData2.attributes.remove(Tag.PixelData)
    dicomData1 should equal (dicomData2)
  }

  "loading and saving a attributes and loading it again" should "produce the same attributes twice" in {
    val attributes1 = TestUtil.testImageDicomData(withPixelData = false)
    val savePath = tempDir.resolve("attributes1.dcm")
    saveDicomData(attributes1, savePath)
    val attributes2 = loadDicomData(savePath, withPixelData = false)
    attributes1 should equal (attributes2)
  }

  it should "work also in combination with anonymization and loading pixel data" in {
    val dicomData = TestUtil.testImageDicomData(withPixelData = true)
    val anonymizedAttributes = anonymizeAttributes(dicomData.attributes)
    val anonymizedDicomData1 = dicomData.copy(attributes = anonymizedAttributes)
    val savePath = tempDir.resolve("anonymized.dcm")
    saveDicomData(anonymizedDicomData1, savePath)
    val anonymizedDicomData2 = loadDicomData(savePath, withPixelData = true)
    // pixel data is different since URL:s are different
    anonymizedDicomData1.attributes.remove(Tag.PixelData)
    anonymizedDicomData2.attributes.remove(Tag.PixelData)
    anonymizedDicomData2 should equal (anonymizedDicomData1)
  }
  override def afterAll() {
    TestUtil.deleteFolder(tempDir)
  }
}
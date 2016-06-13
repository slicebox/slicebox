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
    val dataset = new Attributes()
    dataset.setString(Tag.MediaStorageSOPClassUID, VR.UI, SopClasses.SecondaryCaptureImageStorage.uid)
    dataset.setString(Tag.TransferSyntaxUID, VR.UI, TransferSyntaxes.ExplicitVrLittleEndian.uid)
    intercept[IllegalArgumentException] {
      checkContext(dataset, Contexts.imageDataContexts)
    }
  }

  it should "pass a supported context" in {
    val dataset = new Attributes()
    dataset.setString(Tag.MediaStorageSOPClassUID, VR.UI, SopClasses.NuclearMedicineImageStorage.uid)
    dataset.setString(Tag.TransferSyntaxUID, VR.UI, TransferSyntaxes.ExplicitVrLittleEndian.uid)
    checkContext(dataset, Contexts.imageDataContexts)
  }

  it should "throw an exception for a context with an unknown SOP Class UID" in {
    val notASopClassUid = "this is now a known UID" // any non-SopClassUID string is fine
    val dataset = new Attributes()
    dataset.setString(Tag.MediaStorageSOPClassUID, VR.UI, notASopClassUid)
    dataset.setString(Tag.TransferSyntaxUID, VR.UI, TransferSyntaxes.ExplicitVrLittleEndian.uid)
    intercept[IllegalArgumentException] {
      checkContext(dataset, Contexts.imageDataContexts)
    }
  }

  it should "throw an exception for a dataset with missing file meta information" in {
    val dataset = new Attributes()
    intercept[IllegalArgumentException] {
      checkContext(dataset, Contexts.imageDataContexts)
    }
  }

  val tempDir = Files.createTempDirectory("slicebox-temp-dir-")

  "Loading a dataset" should "return a dataset" in {
    val dicomData = TestUtil.testImageDicomData()
    dicomData.isInstanceOf[DicomData] should be(true)
  }

  "Loading a dataset" should "return the same dataset, disregarding pixelData, when loading with and without pixelData" in {
    val dicomData1 = TestUtil.testImageDicomData(withPixelData = false)
    val dicomData2 = TestUtil.testImageDicomData(withPixelData = true)
    dicomData1 should not equal dicomData2
    dicomData1.attributes.remove(Tag.PixelData)
    dicomData2.attributes.remove(Tag.PixelData)
    dicomData1 should equal (dicomData2)
  }

  "loading and saving a dataset and loading it again" should "produce the same dataset twice" in {
    val dataset1 = TestUtil.testImageDicomData(withPixelData = false)
    val savePath = tempDir.resolve("dataset1.dcm")
    saveDataset(dataset1, savePath)
    val dataset2 = loadDataset(savePath, withPixelData = false, useBulkDataURI = true)
    dataset1 should equal (dataset2)
  }

  it should "work also in combination with anonymization and loading pixel data" in {
    val dicomData = TestUtil.testImageDicomData(withPixelData = true)
    val anonymizedAttributes = anonymizeDataset(dicomData.attributes)
    val anonymizedDicomData1 = dicomData.copy(attributes = anonymizedAttributes)
    val savePath = tempDir.resolve("anonymized.dcm")
    saveDataset(anonymizedDicomData1, savePath)
    val anonymizedDicomData2 = loadDataset(savePath, withPixelData = true, useBulkDataURI = true)
    // pixel data is different since URL:s are different
    anonymizedDicomData1.attributes.remove(Tag.PixelData)
    anonymizedDicomData2.attributes.remove(Tag.PixelData)
    anonymizedDicomData2 should equal (anonymizedDicomData1)
  }
  override def afterAll() {
    TestUtil.deleteFolder(tempDir)
  }
}
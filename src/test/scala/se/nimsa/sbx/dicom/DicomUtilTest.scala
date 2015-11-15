package se.nimsa.sbx.dicom

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import DicomUtil._
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.BeforeAndAfterAll
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.anonymization.AnonymizationUtil._

class DicomUtilTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  "Validating a non-supported SOP class" should "return false for non-supported UID" in {
    val scSopClassUid = "1.2.840.10008.5.1.4.1.1.7" // secondary capture, not supported
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, scSopClassUid)
    checkSopClass(dataset, false) should be(false)
  }

  it should "return true for a supported UID" in {
    val nmSopClassUid = "1.2.840.10008.5.1.4.1.1.20" // nm image storage, supported
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, nmSopClassUid)
    checkSopClass(dataset, false) should be(true)
  }

  it should "return true and false respectively for a secondary capture depending on whether it is allowed or not" in {
    val scSopClassUid = "1.2.840.10008.5.1.4.1.1.7" // secondary capture
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, scSopClassUid)
    checkSopClass(dataset, true) should be(true)
    checkSopClass(dataset, false) should be(false)
  }
  
  it should "return false for an unknown UID" in {
    val notASopClassUid = "this is now a known UID" // any non-SopClassUID string is fine
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, notASopClassUid)
    checkSopClass(dataset, false) should be(false)
  }

  val tempDir = Files.createTempDirectory("slicebox-temp-dir-")

  "Loading a dataset" should "return a dataset" in {
    val dataset = TestUtil.testImageDataset()
    dataset.isInstanceOf[Attributes] should be(true)
  }

  "Loading a dataset" should "return the same dataset, disregarding pixelData, when loading with and without pixelData" in {
    val dataset1 = TestUtil.testImageDataset(false)
    val dataset2 = TestUtil.testImageDataset(true)
    dataset1 should not equal (dataset2)
    dataset1.remove(Tag.PixelData)
    dataset2.remove(Tag.PixelData)
    dataset1 should equal (dataset2)
  }

  "loading and saving a dataset and loading it again" should "produce the same dataset twice" in {
    val dataset1 = TestUtil.testImageDataset(false)
    val savePath = tempDir.resolve("dataset1.dcm")
    saveDataset(dataset1, savePath)
    val dataset2 = loadDataset(savePath, false)
    dataset1 should equal (dataset2)
  }

  it should "work also in combination with anonymization and loading pixel data" in {
    val dataset = TestUtil.testImageDataset()
    val anonymized1 = anonymizeDataset(dataset)
    val savePath = tempDir.resolve("anonymized.dcm")
    saveDataset(anonymized1, savePath)
    val anonymized2 = loadDataset(savePath, true)
    anonymized2 should equal (anonymized1)
    
  }
  override def afterAll() {
    TestUtil.deleteFolder(tempDir)
  }
}
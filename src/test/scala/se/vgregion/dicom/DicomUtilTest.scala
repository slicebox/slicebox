package se.vgregion.dicom

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import DicomUtil._
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.BeforeAndAfterAll
import se.vgregion.util.TestUtil
import se.vgregion.app.DirectoryRoutesTest
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomPropertyValue._


class DicomUtilTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  "Validating a non-supported SOP class" should "return false for non-supported UID" in {
    val scSopClassUid = "1.2.840.10008.5.1.4.1.1.7" // secondary capture, not supported
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, scSopClassUid)
    checkSopClass(dataset) should be(false)
  }

  it should "return true for a supported UID" in {
    val nmSopClassUid = "1.2.840.10008.5.1.4.1.1.20" // nm image storage, supported
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, nmSopClassUid)
    checkSopClass(dataset) should be(true)

  }

  it should "return false for an unknown UID" in {
    val notASopClassUid = "this is now a known UID" // any non-SopClassUID string is fine
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, notASopClassUid)
    checkSopClass(dataset) should be(false)
  }

  val tempDir = Files.createTempDirectory("slicebox-temp-dir-")

  "Loading a dataset" should "return a dataset" in {
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(classOf[DirectoryRoutesTest].getResource(fileName).toURI())
    val dataset = loadDataset(dcmPath, true)
    dataset.isInstanceOf[Attributes] should be(true)
  }

  "Loading a dataset" should "return the same dataset, disregarding pixelData, when loading with and without pixelData" in {
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(classOf[DirectoryRoutesTest].getResource(fileName).toURI())
    val dataset1 = loadDataset(dcmPath, false)
    val dataset2 = loadDataset(dcmPath, true)
    dataset1 should not equal (dataset2)
    dataset1.remove(Tag.PixelData)
    dataset2.remove(Tag.PixelData)
    dataset1 should equal (dataset2)
  }

  "loading and saving a dataset and loading it again" should "produce the same dataset twice" in {
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(classOf[DirectoryRoutesTest].getResource(fileName).toURI())
    val dataset1 = loadDataset(dcmPath, false)
    val savePath = tempDir.resolve("dataset1.dcm")
    val saveResult = saveDataset(dataset1, savePath)
    saveResult should be (true)
    val dataset2 = loadDataset(savePath, false)
    dataset1 should equal (dataset2)
  }

  it should "work also in combination with anonymization and loading pixel data" in {
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(classOf[DirectoryRoutesTest].getResource(fileName).toURI())
    val dataset = loadDataset(dcmPath, true)
    val anonymized1 = DicomAnonymization.anonymizeDataset(dataset)
    val savePath = tempDir.resolve("anonymized.dcm")
    val saveResult = saveDataset(anonymized1, savePath)
    saveResult should be (true)
    val anonymized2 = loadDataset(savePath, true)
    anonymized2 should equal (anonymized1)
    
  }
  override def afterAll() {
    TestUtil.deleteFolder(tempDir)
  }
}
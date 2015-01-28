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
  
//  val pat1 = Patient(PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
//  val study1 = Study(pat1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"))
//  val series1 = Series(study1, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
//  val image1 = Image(series1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"))
//  
//  "creating an Image from a dataset" should "be equal to the Image the dataset was constructed from" in {
//    val dataset = new Attributes()
//    dataset.setString(Tag.PatientName, VR.LO, pat1.patientName.value)
//    dataset.setString(Tag.PatientID, VR.LO, pat1.patientID.value)
//    dataset.setString(Tag.PatientBirthDate, VR.LO, pat1.patientBirthDate.value)
//    dataset.setString(Tag.PatientSex, VR.LO, pat1.patientSex.value)
//    dataset.setString(Tag.StudyInstanceUID, VR.LO, study1.studyInstanceUID.value)
//    dataset.setString(Tag.StudyDescription, VR.LO, study1.studyDescription.value)
//    dataset.setString(Tag.StudyDate, VR.LO, study1.studyDate.value)
//    dataset.setString(Tag.StudyID, VR.LO, study1.studyID.value)
//    dataset.setString(Tag.AccessionNumber, VR.LO, study1.accessionNumber.value)
//    dataset.setString(Tag.Manufacturer, VR.LO, series1.equipment.manufacturer.value)
//    dataset.setString(Tag.StationName, VR.LO, series1.equipment.stationName.value)
//    dataset.setString(Tag.FrameOfReferenceUID, VR.LO, series1.frameOfReference.frameOfReferenceUID.value)
//    dataset.setString(Tag.SeriesInstanceUID, VR.LO, series1.seriesInstanceUID.value)
//    dataset.setString(Tag.SeriesDescription, VR.LO, series1.seriesDescription.value)
//    dataset.setString(Tag.SeriesDate, VR.LO, series1.seriesDate.value)
//    dataset.setString(Tag.Modality, VR.LO, series1.modality.value)
//    dataset.setString(Tag.BodyPartExamined, VR.LO, series1.bodyPartExamined.value)
//    dataset.setString(Tag.SOPInstanceUID, VR.LO, image1.sopInstanceUID.value)
//    dataset.setString(Tag.ImageType, VR.LO, image1.imageType.value)
//    val image2 = datasetToImage(dataset)
//    image1 should equal (image2)
//  }
  
  override def afterAll() {
    TestUtil.deleteFolder(tempDir)
  }
}
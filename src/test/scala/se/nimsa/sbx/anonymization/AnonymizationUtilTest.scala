package se.nimsa.sbx.anonymization

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.BeforeAndAfterAll
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import java.util.Date
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue

class AnonymizationUtilTest extends FlatSpec with Matchers {

  "Applying transaction tag values to a dataset" should "replace DICOM attributes" in {
    val t1 = TagValue(Tag.PatientName.intValue, "Mapped Patient Name")  
    val t2 = TagValue(Tag.PatientID.intValue, "Mapped Patient ID")  
    val t3 = TagValue(Tag.SeriesDescription.intValue, "Mapped Series Description")
    val dataset = createDataset
    applyTagValues(dataset, Seq(t1, t2, t3))
    dataset.getString(Tag.PatientName) should be ("Mapped Patient Name")
    dataset.getString(Tag.PatientID) should be ("Mapped Patient ID")
    dataset.getString(Tag.SeriesDescription) should be ("Mapped Series Description")
  }
  
  "An anonymized dataset" should "be harmonized with respect to existing anonymization keys" in {
    val dataset = createDataset

    // test identity transform
    val keys1 = List(createAnonymizationKey(dataset, createAnonymousDataset))
    val harmonizedDataset1 = harmonizeAnonymization(keys1, dataset, createAnonymousDataset)
    val harmonizedKey1 = createAnonymizationKey(dataset, harmonizedDataset1)
    isEqual(keys1(0), harmonizedKey1) should be (true)
    
    // test change patient property
    val keys2 = List(keys1(0).copy(anonPatientID = "apid2"))
    val harmonizedDataset2 = harmonizeAnonymization(keys2, dataset, createAnonymousDataset)
    harmonizedDataset2.getString(Tag.PatientID) should equal ("apid2")
    
    // test change patient and study properties
    val keys3 = List(keys1(0).copy(anonPatientID = "apid2", anonStudyInstanceUID = "astuid2"))
    val harmonizedDataset3 = harmonizeAnonymization(keys3, dataset, createAnonymousDataset)
    harmonizedDataset3.getString(Tag.PatientID) should equal ("apid2")
    harmonizedDataset3.getString(Tag.StudyInstanceUID) should equal ("astuid2")
    
    // test change patient property, with changed study property but non-matching study UID
    val keys4 = List(keys1(0).copy(anonPatientID = "apid2", studyInstanceUID = "stuid2", anonStudyInstanceUID = "astuid2"))
    val harmonizedDataset4 = harmonizeAnonymization(keys4, dataset, createAnonymousDataset)
    harmonizedDataset4.getString(Tag.PatientID) should equal ("apid2")
    harmonizedDataset4.getString(Tag.StudyInstanceUID) should not equal ("astuid2")
  }

  it should "be restored with essential patient information" in {
    val dataset = createDataset
    val key1 = createAnonymizationKey(dataset, createAnonymousDataset)
    val reversedDataset1 = AnonymizationUtil.reverseAnonymization(List(key1), createAnonymousDataset)
    reversedDataset1.getString(Tag.PatientName) should equal (key1.patientName)
    reversedDataset1.getString(Tag.PatientID) should equal (key1.patientID)
    reversedDataset1.getString(Tag.StudyInstanceUID) should equal (key1.studyInstanceUID)
    
    val key2 = key1.copy(studyInstanceUID = "seuid2")
    val reversedDataset2 = reverseAnonymization(List(key2), createAnonymousDataset)
    reversedDataset2.getString(Tag.PatientName) should be (dataset.getString(Tag.PatientName)) 
    reversedDataset2.getString(Tag.PatientID) should be (dataset.getString(Tag.PatientID)) 
    reversedDataset2.getString(Tag.StudyInstanceUID) should not be (dataset.getString(Tag.StudyInstanceUID)) 
  }
  
  "The anonymization procedure" should "replace an existing accession number with a named based UID" in {
    val dataset = new Attributes()
    dataset.setString(Tag.AccessionNumber, VR.SH, "ACC001")
    val anonymized = anonymizeDataset(dataset)
    anonymized.getString(Tag.AccessionNumber) should not be (null)
    anonymized.getString(Tag.AccessionNumber).isEmpty() should be (false)
    anonymized.getString(Tag.AccessionNumber) should not equal (dataset.getString(Tag.AccessionNumber))
  }

  it should "leave an empty accession number empty" in {
    val dataset = new Attributes()
    dataset.setString(Tag.AccessionNumber, VR.SH, "")
    val anonymized = anonymizeDataset(dataset)
    anonymized.getString(Tag.AccessionNumber) should be (null)    
  }
  
  it should "create an new UID from and existing UID" in {
    val dataset = new Attributes()
    dataset.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val anonymized = anonymizeDataset(dataset)
    anonymized.getString(Tag.StudyInstanceUID) should not equal (dataset.getString(Tag.StudyInstanceUID))        
  }
  
  it should "create a new UID for tags with a present UID, and leave the tag empty for empty tags" in {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientIdentityRemoved, VR.CS, "NO")
    val anonymized1 = anonymizeDataset(dataset)
    dataset.setString(Tag.StudyInstanceUID, VR.UI, "")
    val anonymized2 = anonymizeDataset(dataset)
    dataset.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val anonymized3 = anonymizeDataset(dataset)
    anonymized1.getString(Tag.StudyInstanceUID) should be (null)
    anonymized2.getString(Tag.StudyInstanceUID) should be (null)
    anonymized3.getString(Tag.StudyInstanceUID) should not be (null)
  }
  
  it should "always create the same new UID from some fixed existing UID" in {
    val dataset = new Attributes()
    val uid = "1.2.3.4.5.6.7.8.9"
    dataset.setString(Tag.SOPInstanceUID, VR.UI, uid)
    val anonymized1 = anonymizeDataset(dataset)
    val anonymized2 = anonymizeDataset(dataset)
    anonymized1.getString(Tag.SOPInstanceUID) should equal (anonymized2.getString(Tag.SOPInstanceUID))
  }
  
  it should "remove private tags" in {
    val dataset = new Attributes()
    val privateTag = 0x65430010 // odd group = private
    dataset.setString(privateTag, VR.LO, "Private tag value")
    val anonymized = anonymizeDataset(dataset)
    anonymized.getString(privateTag) should be (null)
  }

  it should "remove birth date" in {
    val dataset = new Attributes()
    dataset.setDate(Tag.PatientBirthDate, VR.DA, new Date(123456789876L))
    val anonymized = anonymizeDataset(dataset)
    anonymized.getDate(Tag.PatientBirthDate) should equal (null)    
  }
  
  it should "create a legible anonymous patient name" in {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientName, VR.PN, "John Doe")
    val anonymized1 = anonymizeDataset(dataset)    
    dataset.setString(Tag.PatientAge, VR.AS, "50Y")
    val anonymized2 = anonymizeDataset(dataset)    
    dataset.setString(Tag.PatientSex, VR.CS, "M")
    val anonymized3 = anonymizeDataset(dataset)    
    anonymized1.getString(Tag.PatientName) should not be (null)
    anonymized1.getString(Tag.PatientName).isEmpty should be (false)
    anonymized2.getString(Tag.PatientName).contains("50Y") should be (true)
    anonymized3.getString(Tag.PatientName).contains("50Y") should be (true)
    anonymized3.getString(Tag.PatientName).contains("M") should be (true)
  }
  
  def createDataset = {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientName, VR.LO, "pn")
    dataset.setString(Tag.PatientID, VR.LO, "pid")
    dataset.setString(Tag.StudyInstanceUID, VR.LO, "stuid")
    dataset.setString(Tag.SeriesInstanceUID, VR.LO, "seuid")
    dataset.setString(Tag.FrameOfReferenceUID, VR.LO, "foruid")    
    dataset
  }
  
  def createAnonymousDataset = {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientName, VR.LO, "apn")
    dataset.setString(Tag.PatientID, VR.LO, "apid")
    dataset.setString(Tag.StudyInstanceUID, VR.LO, "astuid")
    dataset.setString(Tag.SeriesInstanceUID, VR.LO, "aseuid")
    dataset.setString(Tag.FrameOfReferenceUID, VR.LO, "aforuid")    
    setAnonymous(dataset, true)
    dataset
  }
  
}
package se.nimsa.sbx.anonymization

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import java.util.Date
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue

class AnonymizationUtilTest extends FlatSpec with Matchers {

  "Applying transaction tag values to a attributes" should "replace DICOM attributes" in {
    val t1 = TagValue(Tag.PatientName.intValue, "Mapped Patient Name")  
    val t2 = TagValue(Tag.PatientID.intValue, "Mapped Patient ID")  
    val t3 = TagValue(Tag.SeriesDescription.intValue, "Mapped Series Description")
    val attributes = createAttributes
    applyTagValues(attributes, Seq(t1, t2, t3))
    attributes.getString(Tag.PatientName) should be ("Mapped Patient Name")
    attributes.getString(Tag.PatientID) should be ("Mapped Patient ID")
    attributes.getString(Tag.SeriesDescription) should be ("Mapped Series Description")
  }
  
  "An anonymized attributes" should "be harmonized with respect to existing anonymization keys" in {
    val attributes = createAttributes

    // test identity transform
    val keys1 = List(createAnonymizationKey(attributes, createAnonymousAttributes))
    val harmonizedAttributes1 = harmonizeAnonymization(keys1, attributes, createAnonymousAttributes)
    val harmonizedKey1 = createAnonymizationKey(attributes, harmonizedAttributes1)
    isEqual(keys1.head, harmonizedKey1) should be (true)
    
    // test change patient property
    val keys2 = List(keys1.head.copy(anonPatientID = "apid2"))
    val harmonizedAttributes2 = harmonizeAnonymization(keys2, attributes, createAnonymousAttributes)
    harmonizedAttributes2.getString(Tag.PatientID) should equal ("apid2")
    
    // test change patient and study properties
    val keys3 = List(keys1.head.copy(anonPatientID = "apid2", anonStudyInstanceUID = "astuid2"))
    val harmonizedAttributes3 = harmonizeAnonymization(keys3, attributes, createAnonymousAttributes)
    harmonizedAttributes3.getString(Tag.PatientID) should equal ("apid2")
    harmonizedAttributes3.getString(Tag.StudyInstanceUID) should equal ("astuid2")
    
    // test change patient property, with changed study property but non-matching study UID
    val keys4 = List(keys1.head.copy(anonPatientID = "apid2", studyInstanceUID = "stuid2", anonStudyInstanceUID = "astuid2"))
    val harmonizedAttributes4 = harmonizeAnonymization(keys4, attributes, createAnonymousAttributes)
    harmonizedAttributes4.getString(Tag.PatientID) should equal ("apid2")
    harmonizedAttributes4.getString(Tag.StudyInstanceUID) should not equal "astuid2"
  }

  it should "be restored with essential patient information" in {
    val attributes = createAttributes
    val key1 = createAnonymizationKey(attributes, createAnonymousAttributes)
    val reversedAttributes1 = AnonymizationUtil.reverseAnonymization(List(key1), createAnonymousAttributes)
    reversedAttributes1.getString(Tag.PatientName) should equal (key1.patientName)
    reversedAttributes1.getString(Tag.PatientID) should equal (key1.patientID)
    reversedAttributes1.getString(Tag.StudyInstanceUID) should equal (key1.studyInstanceUID)
    reversedAttributes1.getString(Tag.SeriesInstanceUID) should equal (key1.seriesInstanceUID)
    reversedAttributes1.getString(Tag.FrameOfReferenceUID) should equal (key1.frameOfReferenceUID)
    
    val key2 = key1.copy(anonSeriesInstanceUID = "another aseuid")
    val reversedAttributes2 = reverseAnonymization(List(key2), createAnonymousAttributes)
    reversedAttributes2.getString(Tag.PatientName) should be (key1.patientName) 
    reversedAttributes2.getString(Tag.PatientID) should be (key1.patientID) 
    reversedAttributes2.getString(Tag.StudyInstanceUID) should be (key1.studyInstanceUID) 
    reversedAttributes2.getString(Tag.SeriesInstanceUID) should not be key1.seriesInstanceUID
    reversedAttributes2.getString(Tag.FrameOfReferenceUID) should not be key1.frameOfReferenceUID
    
    val key3 = key1.copy(anonStudyInstanceUID = "another stuid")
    val reversedAttributes3 = reverseAnonymization(List(key3), createAnonymousAttributes)
    reversedAttributes3.getString(Tag.PatientName) should be (key1.patientName) 
    reversedAttributes3.getString(Tag.PatientID) should be (key1.patientID) 
    reversedAttributes3.getString(Tag.StudyInstanceUID) should not be key1.studyInstanceUID
    reversedAttributes3.getString(Tag.SeriesInstanceUID) should not be key1.seriesInstanceUID
    reversedAttributes3.getString(Tag.FrameOfReferenceUID) should not be key1.frameOfReferenceUID
  }
  
  "The anonymization procedure" should "replace an existing accession number with a named based UID" in {
    val attributes = new Attributes()
    attributes.setString(Tag.AccessionNumber, VR.SH, "ACC001")
    val anonymized = anonymizeAttributes(attributes)
    anonymized.getString(Tag.AccessionNumber) should not be null
    anonymized.getString(Tag.AccessionNumber) should not be empty
    anonymized.getString(Tag.AccessionNumber) should not equal attributes.getString(Tag.AccessionNumber)
  }

  it should "leave an empty accession number empty" in {
    val attributes = new Attributes()
    attributes.setString(Tag.AccessionNumber, VR.SH, "")
    val anonymized = anonymizeAttributes(attributes)
    anonymized.getString(Tag.AccessionNumber) shouldBe null
  }
  
  it should "create an new UID from and existing UID" in {
    val attributes = new Attributes()
    attributes.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val anonymized = anonymizeAttributes(attributes)
    anonymized.getString(Tag.StudyInstanceUID) should not equal attributes.getString(Tag.StudyInstanceUID)
  }
  
  it should "create a new UID for tags with a present UID, and leave the tag empty for empty tags" in {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientIdentityRemoved, VR.CS, "NO")
    val anonymized1 = anonymizeAttributes(attributes)
    attributes.setString(Tag.StudyInstanceUID, VR.UI, "")
    val anonymized2 = anonymizeAttributes(attributes)
    attributes.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val anonymized3 = anonymizeAttributes(attributes)
    anonymized1.getString(Tag.StudyInstanceUID) should be (null)
    anonymized2.getString(Tag.StudyInstanceUID) should be (null)
    anonymized3.getString(Tag.StudyInstanceUID) should not be null
  }
  
  it should "always create the same new UID from some fixed existing UID" in {
    val attributes = new Attributes()
    val uid = "1.2.3.4.5.6.7.8.9"
    attributes.setString(Tag.SOPInstanceUID, VR.UI, uid)
    val anonymized1 = anonymizeAttributes(attributes)
    val anonymized2 = anonymizeAttributes(attributes)
    anonymized1.getString(Tag.SOPInstanceUID) should equal (anonymized2.getString(Tag.SOPInstanceUID))
  }
  
  it should "remove private tags" in {
    val attributes = new Attributes()
    val privateTag = 0x65430010 // odd group = private
    attributes.setString(privateTag, VR.LO, "Private tag value")
    val anonymized = anonymizeAttributes(attributes)
    anonymized.getString(privateTag) should be (null)
  }

  it should "remove birth date" in {
    val attributes = new Attributes()
    attributes.setDate(Tag.PatientBirthDate, VR.DA, new Date(123456789876L))
    val anonymized = anonymizeAttributes(attributes)
    anonymized.getDate(Tag.PatientBirthDate) should equal (null)    
  }
  
  it should "create a legible anonymous patient name" in {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientName, VR.PN, "John Doe")
    val anonymized1 = anonymizeAttributes(attributes)
    attributes.setString(Tag.PatientAge, VR.AS, "50Y")
    val anonymized2 = anonymizeAttributes(attributes)
    attributes.setString(Tag.PatientSex, VR.CS, "M")
    val anonymized3 = anonymizeAttributes(attributes)
    anonymized1.getString(Tag.PatientName) should not be null
    anonymized1.getString(Tag.PatientName).isEmpty should be (false)
    anonymized2.getString(Tag.PatientName).contains("50Y") should be (true)
    anonymized3.getString(Tag.PatientName).contains("50Y") should be (true)
    anonymized3.getString(Tag.PatientName).contains("M") should be (true)
  }
  
  def createAttributes = {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientName, VR.LO, "pn")
    attributes.setString(Tag.PatientID, VR.LO, "pid")
    attributes.setString(Tag.StudyInstanceUID, VR.LO, "stuid")
    attributes.setString(Tag.SeriesInstanceUID, VR.LO, "seuid")
    attributes.setString(Tag.FrameOfReferenceUID, VR.LO, "foruid")    
    attributes
  }
  
  def createAnonymousAttributes = {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientName, VR.LO, "apn")
    attributes.setString(Tag.PatientID, VR.LO, "apid")
    attributes.setString(Tag.StudyInstanceUID, VR.LO, "astuid")
    attributes.setString(Tag.SeriesInstanceUID, VR.LO, "aseuid")
    attributes.setString(Tag.FrameOfReferenceUID, VR.LO, "aforuid")    
    setAnonymous(attributes, anonymous = true)
    attributes
  }
  
}
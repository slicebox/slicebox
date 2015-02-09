package se.vgregion.dicom

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import DicomAnonymization._
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
import java.util.Date
import java.text.SimpleDateFormat

class DicomAnonymizationTest extends FlatSpec with Matchers {

  val dateformat = new SimpleDateFormat("yyyy-MM-dd")

  "The anonnymization procedure" should "replace an existing accession number with a named based UID" in {
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
  
  it should "remove patient name and/or patient ID present in other tags" in {
    val dataset = new Attributes()
    val patientName = "John Doe"
    val patientID = "123456-7890"
    dataset.setString(Tag.PatientName, VR.PN, patientName)
    dataset.setString(Tag.PatientID, VR.LO, patientID)
    dataset.setString(Tag.PatientAge, VR.LO, s"Patient: $patientName, $patientID")
    dataset.setString(Tag.PatientSex, VR.LO, s"Patient with ID $patientID is 30 years old")
    dataset.setString(Tag.PregnancyStatus, VR.LO, "Pregnant")
    val anonymized = anonymizeDataset(dataset)
    anonymized.getString(Tag.PatientAge) should be (null)        
    anonymized.getString(Tag.PatientSex) should be (null)        
    anonymized.getString(Tag.PregnancyStatus) should not be (null)        
  }

  it should "replace birth date with a dummy date" in {
    val dataset = new Attributes()
    dataset.setDate(Tag.PatientBirthDate, VR.DA, new Date(123456789876L))
    val anonymized = anonymizeDataset(dataset)
    dateformat.format(anonymized.getDate(Tag.PatientBirthDate)) should equal (dateformat.format(anonymousDate))    
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
}
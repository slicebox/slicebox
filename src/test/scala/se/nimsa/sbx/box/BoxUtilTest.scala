package se.nimsa.sbx.box

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import se.nimsa.sbx.box.BoxProtocol.TransactionTagValue
import org.dcm4che3.data.Tag
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.VR
import java.util.Date
import se.nimsa.sbx.box.BoxProtocol.AnonymizationKey
import se.nimsa.sbx.dicom.DicomUtil._

class BoxUtilTest extends FlatSpec with Matchers {

  "Applying transaction tag values to a dataset" should "replace DICOM attributes" in {
    val t1 = TransactionTagValue(1,2,3,Tag.PatientName.intValue, "Mapped Patient Name")  
    val t2 = TransactionTagValue(2,2,3,Tag.PatientID.intValue, "Mapped Patient ID")  
    val t3 = TransactionTagValue(3,2,3,Tag.SeriesDescription.intValue, "Mapped Series Description")
    val dataset = createDataset
    BoxUtil.applyTagValues(dataset, Seq(t1, t2, t3))
    dataset.getString(Tag.PatientName) should be ("Mapped Patient Name")
    dataset.getString(Tag.PatientID) should be ("Mapped Patient ID")
    dataset.getString(Tag.SeriesDescription) should be ("Mapped Series Description")
    dataset.getString(Tag.Manufacturer) should be ("m")
  }
  
  "An anonymized dataset" should "be harmonized with respect to existing anonymization keys" in {
    val dataset = createDataset

    // test identity transform
    val keys1 = List(BoxUtil.createAnonymizationKey(1, 1, "otherbox", 
        dataset, createAnonymousDataset))
    val harmonizedDataset1 = BoxUtil.harmonizeAnonymization(keys1, dataset, createAnonymousDataset)
    val harmonizedKey1 = BoxUtil.createAnonymizationKey(1, 1, "otherbox", dataset, harmonizedDataset1)
    keys1(0) should equal (harmonizedKey1)
    
    // test change patient property
    val keys2 = List(keys1(0).copy(anonPatientID = "apid2"))
    val harmonizedDataset2 = BoxUtil.harmonizeAnonymization(keys2, dataset, createAnonymousDataset)
    harmonizedDataset2.getString(Tag.PatientID) should equal ("apid2")
    
    // test change patient and study properties
    val keys3 = List(keys1(0).copy(anonPatientID = "apid2", anonStudyInstanceUID = "astuid2"))
    val harmonizedDataset3 = BoxUtil.harmonizeAnonymization(keys3, dataset, createAnonymousDataset)
    harmonizedDataset3.getString(Tag.PatientID) should equal ("apid2")
    harmonizedDataset3.getString(Tag.StudyInstanceUID) should equal ("astuid2")
    
    // test change patient property, with changed study property but non-matching study UID
    val keys4 = List(keys1(0).copy(anonPatientID = "apid2", studyInstanceUID = "stuid2", anonStudyInstanceUID = "astuid2"))
    val harmonizedDataset4 = BoxUtil.harmonizeAnonymization(keys4, dataset, createAnonymousDataset)
    harmonizedDataset4.getString(Tag.PatientID) should equal ("apid2")
    harmonizedDataset4.getString(Tag.StudyInstanceUID) should not equal ("astuid2")
  }

  it should "be restored with essential patient information" in {
    val dataset = createDataset
    val key1 = BoxUtil.createAnonymizationKey(1, 1, "remote box", dataset, createAnonymousDataset)
    val reversedDataset1 = BoxUtil.reverseAnonymization(List(key1), createAnonymousDataset)
    BoxUtil.createAnonymizationKey(1, 1, "remote box", reversedDataset1, createAnonymousDataset) should equal (key1)
    
    val key2 = key1.copy(seriesInstanceUID = "seuid2")
    val reversedDataset2 = BoxUtil.reverseAnonymization(List(key2), createAnonymousDataset)
    reversedDataset2.getString(Tag.PatientName) should be (dataset.getString(Tag.PatientName)) 
    reversedDataset2.getString(Tag.PatientID) should be (dataset.getString(Tag.PatientID)) 
    reversedDataset2.getString(Tag.StudyInstanceUID) should be (dataset.getString(Tag.StudyInstanceUID)) 
    reversedDataset2.getString(Tag.SeriesInstanceUID) should not be (dataset.getString(Tag.SeriesInstanceUID)) 
  }
  
  def createDataset = {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientName, VR.LO, "pn")
    dataset.setString(Tag.PatientID, VR.LO, "pid")
    dataset.setString(Tag.StudyInstanceUID, VR.LO, "stuid")
    dataset.setString(Tag.SeriesInstanceUID, VR.LO, "seuid")
    dataset.setString(Tag.Manufacturer, VR.LO, "m")
    dataset.setString(Tag.StationName, VR.LO, "sn")
    dataset.setString(Tag.FrameOfReferenceUID, VR.LO, "foruid")    
    dataset
  }
  
  def createAnonymousDataset = {
    val dataset = new Attributes()
    dataset.setString(Tag.PatientName, VR.LO, "apn")
    dataset.setString(Tag.PatientID, VR.LO, "apid")
    dataset.setString(Tag.StudyInstanceUID, VR.LO, "astuid")
    dataset.setString(Tag.SeriesInstanceUID, VR.LO, "aseuid")
    dataset.setString(Tag.Manufacturer, VR.LO, "am")
    dataset.setString(Tag.StationName, VR.LO, "asn")
    dataset.setString(Tag.FrameOfReferenceUID, VR.LO, "aforuid")    
    setAnonymous(dataset, true)
    dataset
  }
  
}
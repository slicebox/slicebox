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

  val dataset = new Attributes()

  // replace tags
  dataset.setString(Tag.StationName, VR.SH, "Station Name")
  dataset.setString(Tag.OperatorsName, VR.PN, "Operator")
  dataset.setString(Tag.IssuerOfPatientID, VR.LO, "Issuer of Patient ID")
  dataset.setString(Tag.PatientBirthName, VR.PN, "Patient Birth Name")
  dataset.setString(Tag.PatientMotherBirthName, VR.PN, "Patient Mother Birth Name")
  dataset.setString(Tag.MedicalAlerts, VR.LO, "Medical Alerts")
  dataset.setString(0x00102110, VR.LO, "Contrast Allergies") // contrast allergies
  dataset.setString(Tag.CountryOfResidence, VR.LO, "Country of Residence")
  dataset.setString(Tag.RegionOfResidence, VR.LO, "Region of Residence")
  dataset.setString(Tag.PatientTelephoneNumbers, VR.SH, "Patient Telephone Numbers")
  dataset.setString(Tag.SmokingStatus, VR.CS, "Smoking Status")
  dataset.setString(Tag.PatientReligiousPreference, VR.LO, "Patient Religious Preference")
  dataset.setString(Tag.DeviceSerialNumber, VR.LO, "Device Serial Number")
  dataset.setString(Tag.ProtocolName, VR.LO, "Protocol Name")
  dataset.setDate(Tag.PatientBirthDate, VR.DA, new Date(123456789))
  dataset.setInt(Tag.PregnancyStatus, VR.US, 1)
  dataset.setString(Tag.AccessionNumber, VR.SH, "Accession Number")
  dataset.setString(Tag.StudyID, VR.SH, "Study ID")
  dataset.setString(Tag.PerformedProcedureStepID, VR.SH, "") // empty string on purpose
  dataset.setString(Tag.RequestedProcedureID, VR.SH, null) // null on purpose
  dataset.setString(Tag.PatientName, VR.SH, "John Doe")
  dataset.setString(Tag.PatientID, VR.LO, "123456-7890")
  dataset.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3")
  dataset.setString(Tag.SeriesInstanceUID, VR.UI, "4.5.6")
  dataset.setString(Tag.SOPInstanceUID, VR.UI, "7.8.9")

  // remove tags
  dataset.setString(Tag.InstitutionName, VR.LO, "Institution")
  dataset.setString(Tag.ReferringPhysicianName, VR.PN, "Referring Physician")
  dataset.setString(Tag.InstitutionAddress, VR.LO, "Institution Address")
  dataset.setString(Tag.ReferringPhysicianAddress, VR.LO, "Referring Physician Address")
  dataset.setString(Tag.ReferringPhysicianTelephoneNumbers, VR.LO, "Referring Physician Telephone Numbers")
  dataset.setString(Tag.InstitutionalDepartmentName, VR.LO, "Institutional Department Name")
  dataset.setString(Tag.PhysiciansOfRecord, VR.LO, "Physicians Of Record")
  dataset.setString(Tag.PerformingPhysicianName, VR.LO, "Performing Physician Name")
  dataset.setString(Tag.NameOfPhysiciansReadingStudy, VR.LO, "Name Of Physicians Reading Study")
  dataset.setString(Tag.AdmittingDiagnosesDescription, VR.LO, "Admitting Diagnoses Description")
  dataset.setString(Tag.DerivationDescription, VR.LO, "Derivation Description")
  dataset.setString(Tag.SourceImageSequence, VR.LO, "Source Image Sequence")
  dataset.setString(Tag.PatientBirthTime, VR.LO, "Patient Birth Time")
  dataset.setString(Tag.PatientInsurancePlanCodeSequence, VR.LO, "Patient Insurance Plan Code Sequence")
  dataset.setString(Tag.OtherPatientIDs, VR.LO, "Other Patient IDs")
  dataset.setString(Tag.OtherPatientNames, VR.LO, "Other Patient Names")
  dataset.setString(Tag.MilitaryRank, VR.LO, "Military Rank")
  dataset.setString(Tag.BranchOfService, VR.LO, "BranchOf Service")
  dataset.setString(Tag.MedicalRecordLocator, VR.LO, "Medical Record Locator")
  dataset.setString(Tag.EthnicGroup, VR.LO, "Ethnic Group")
  dataset.setString(Tag.Occupation, VR.LO, "Occupation")
  dataset.setString(Tag.AdditionalPatientHistory, VR.LO, "Additional Patient History")
  dataset.setString(Tag.LastMenstrualDate, VR.LO, "Last Menstrual Date")
  dataset.setString(Tag.PatientComments, VR.LO, "Patient Comments")
  dataset.setString(Tag.ImageComments, VR.LO, "Image Comments")
  dataset.setString(Tag.AdmissionID, VR.LO, "Admission ID")
  dataset.setString(Tag.IssuerOfAdmissionID, VR.LO, "Issuer Of Admission ID")
  dataset.setString(Tag.IssuerOfAdmissionIDSequence, VR.LO, "Issuer Of Admission ID Sequence")
  dataset.setString(Tag.PerformedProcedureStepDescription, VR.LO, "Performed Procedure Step Description")
  dataset.setString(Tag.ScheduledStepAttributesSequence, VR.LO, "Scheduled Step Attributes Sequence")
  dataset.setString(Tag.RequestAttributesSequence, VR.LO, "Request Attributes Sequence")
  dataset.setString(Tag.ReferencedRequestSequence, VR.LO, "Referenced Request Sequence")

  // not anonmymized dicom hierarchy data
  dataset.setString(Tag.PatientSex, VR.LO, "M")
  dataset.setString(Tag.StudyDescription, VR.LO, "Study Description")
  dataset.setString(Tag.StudyDate, VR.LO, "20000101")
  dataset.setString(Tag.Manufacturer, VR.LO, "Manufacturer")
  dataset.setString(Tag.StationName, VR.LO, "Station Name")
  dataset.setString(Tag.FrameOfReferenceUID, VR.LO, "9.8.7")
  dataset.setString(Tag.SeriesDate, VR.LO, "20000102")
  dataset.setString(Tag.Modality, VR.LO, "NM")
  dataset.setString(Tag.BodyPartExamined, VR.LO, "Arm")
  dataset.setString(Tag.ImageType, VR.LO, "Image", "Type")

  val anonymized = anonymizeDataset(dataset)
  println(dataset)
  println(anonymized)

  val dateformat = new SimpleDateFormat("yyy-MM-dd")
  
  "An anonymized dataset" should "be anonymized after anonymization" in {
    anonymized.getString(Tag.StationName) should equal(anonymousString)
    anonymized.getString(Tag.OperatorsName) should equal(anonymousString)
    anonymized.getString(Tag.IssuerOfPatientID) should equal(anonymousString)
    anonymized.getString(Tag.PatientBirthName) should equal(anonymousString)
    anonymized.getString(Tag.PatientMotherBirthName) should equal(anonymousString)
    anonymized.getString(Tag.MedicalAlerts) should equal(anonymousString)
    anonymized.getString(0x00102110) should equal(anonymousString)
    anonymized.getString(Tag.CountryOfResidence) should equal(anonymousString)
    anonymized.getString(Tag.RegionOfResidence) should equal(anonymousString)
    anonymized.getString(Tag.PatientTelephoneNumbers) should equal(anonymousString)
    anonymized.getString(Tag.SmokingStatus) should equal(unknownString)
    anonymized.getString(Tag.PatientReligiousPreference) should equal(anonymousString)
    anonymized.getString(Tag.DeviceSerialNumber) should equal(anonymousString)
    anonymized.getString(Tag.ProtocolName) should equal(anonymousString)
    dateformat.format(anonymized.getDate(Tag.PatientBirthDate)) should equal (dateformat.format(anonymousDate))
    anonymized.getInt(Tag.PregnancyStatus, -1) should equal(anonymousPregnancyStatus)
    anonymized.getString(Tag.AccessionNumber) should not equal(dataset.getString(Tag.AccessionNumber))
    anonymized.getString(Tag.StudyID) should not equal(dataset.getString(Tag.StudyID))
    anonymized.getString(Tag.PerformedProcedureStepID) should equal(null) // since value is empty string
    anonymized.getString(Tag.RequestedProcedureID) should equal(null) // since value is null
    anonymized.getString(Tag.PatientName) should equal(createAnonymousPatientName(dataset.getString(Tag.PatientSex), dataset.getString(Tag.PatientAge)))
    anonymized.getString(Tag.PatientID) should not equal(dataset.getString(Tag.PatientID))
    anonymized.getString(Tag.StudyInstanceUID) should not equal(dataset.getString(Tag.StudyInstanceUID))
    anonymized.getString(Tag.SeriesInstanceUID) should not equal(dataset.getString(Tag.SeriesInstanceUID))
    anonymized.getString(Tag.SOPInstanceUID) should not equal(dataset.getString(Tag.SOPInstanceUID))
    
    anonymized.getString(Tag.InstitutionName) should equal(null)
    anonymized.getString(Tag.ReferringPhysicianName) should equal(null)
  }
  
}
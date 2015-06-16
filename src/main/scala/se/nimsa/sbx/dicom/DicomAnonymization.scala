/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.dicom

import org.dcm4che3.data.VR
import org.dcm4che3.util.UIDUtils
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import java.util.Date
import org.dcm4che3.util.TagUtils
import org.dcm4che3.data.Attributes.Visitor
import DicomUtil._

object DicomAnonymization {

  val emptyString = ""

  def anonymizeDataset(dataset: Attributes): Attributes = {

    if (isAnonymous(dataset)) {

      cloneDataset(dataset)

    } else {

      val patientName = dataset.getString(Tag.PatientName)
      val patientID = dataset.getString(Tag.PatientID)
      val sex = dataset.getString(Tag.PatientSex)
      val age = dataset.getString(Tag.PatientAge)

      val modified = cloneDataset(dataset)

      // this section from standard PS3.15 Table E.1-1

      setAnonymous(modified, true)

      setStringTagIfPresent(modified, Tag.AccessionNumber, VR.SH, createUid(modified.getString(Tag.AccessionNumber)))
      removeTag(modified, Tag.AcquisitionComments)
      removeTag(modified, Tag.AcquisitionContextSequence)
      removeTag(modified, Tag.AcquisitionDeviceProcessingDescription)
      removeTag(modified, Tag.AcquisitionProtocolDescription)
      removeTag(modified, Tag.ActualHumanPerformersSequence)
      removeTag(modified, Tag.AdditionalPatientHistory)
      removeTag(modified, Tag.AddressTrial)
      removeTag(modified, Tag.AdmissionID)
      removeTag(modified, Tag.AdmittingDiagnosesCodeSequence)
      removeTag(modified, Tag.AdmittingDiagnosesDescription)
      setUidTagIfPresent(modified, Tag.ConcatenationUID)
      removeTag(modified, Tag.Allergies)
      removeTag(modified, Tag.Arbitrary)
      removeTag(modified, Tag.AuthorObserverSequence)
      removeTag(modified, Tag.BranchOfService)
      removeTag(modified, Tag.CommentsOnThePerformedProcedureStep)
      removeTag(modified, Tag.ConfidentialityConstraintOnPatientDataDescription)
      setStringTagIfPresent(modified, Tag.ContentCreatorName, VR.PN, emptyString)
      removeTag(modified, Tag.ContentCreatorIdentificationCodeSequence)
      removeTag(modified, Tag.ContentSequence)
      setUidTagIfPresent(modified, Tag.ContextGroupExtensionCreatorUID)
      setStringTagIfPresent(modified, Tag.ContrastBolusAgent, VR.LO, emptyString)
      removeTag(modified, Tag.ContributionDescription)
      removeTag(modified, Tag.CountryOfResidence)
      setUidTagIfPresent(modified, Tag.CreatorVersionUID)
      removeTag(modified, Tag.CurrentObserverTrial)
      removeTag(modified, Tag.CurrentPatientLocation)
      removeTag(modified, Tag.CurveData)
      removeTag(modified, Tag.CustodialOrganizationSequence)
      removeTag(modified, Tag.DataSetTrailingPadding)
      removeTag(modified, Tag.DerivationDescription)
      removeTag(modified, Tag.DigitalSignatureUID)
      removeTag(modified, Tag.DigitalSignaturesSequence)
      setUidTagIfPresent(modified, Tag.DimensionOrganizationUID)
      removeTag(modified, Tag.DischargeDiagnosisDescription)
      removeTag(modified, Tag.DistributionAddress)
      removeTag(modified, Tag.DistributionName)
      setUidTagIfPresent(modified, Tag.DoseReferenceUID)
      removeTag(modified, Tag.FailedSOPInstanceUIDList)
      setUidTagIfPresent(modified, Tag.FiducialUID)
      setStringTagIfPresent(modified, Tag.FillerOrderNumberImagingServiceRequest, VR.LO, emptyString)
      removeTag(modified, Tag.FrameComments)
      setUidTagIfPresent(modified, Tag.FrameOfReferenceUID)
      removeTag(modified, Tag.GraphicAnnotationSequence) // type D
      removeTag(modified, Tag.HumanPerformerName)
      removeTag(modified, Tag.HumanPerformerOrganization)
      removeTag(modified, Tag.IconImageSequence)
      removeTag(modified, Tag.IdentifyingComments)
      removeTag(modified, Tag.ImageComments)
      removeTag(modified, Tag.ImagePresentationComments)
      removeTag(modified, Tag.ImagingServiceRequestComments)
      removeTag(modified, Tag.Impressions)
      removeTag(modified, Tag.InstanceCoercionDateTime)
      setUidTagIfPresent(modified, Tag.InstanceCreatorUID)
      removeTag(modified, Tag.InstitutionAddress)
      removeTag(modified, Tag.InstitutionCodeSequence)
      removeTag(modified, Tag.InstitutionName)
      removeTag(modified, Tag.InstitutionalDepartmentName)
      removeTag(modified, Tag.InsurancePlanIdentification)
      removeTag(modified, Tag.IntendedRecipientsOfResultsIdentificationSequence)
      removeTag(modified, Tag.InterpretationApproverSequence)
      removeTag(modified, Tag.InterpretationAuthor)
      removeTag(modified, Tag.InterpretationDiagnosisDescription)
      removeTag(modified, Tag.InterpretationIDIssuer)
      removeTag(modified, Tag.InterpretationRecorder)
      removeTag(modified, Tag.InterpretationText)
      removeTag(modified, Tag.InterpretationTranscriber)
      setUidTagIfPresent(modified, Tag.IrradiationEventUID)
      removeTag(modified, Tag.IssuerOfAdmissionID)
      removeTag(modified, Tag.IssuerOfPatientID)
      removeTag(modified, Tag.IssuerOfServiceEpisodeID)
      setUidTagIfPresent(modified, Tag.LargePaletteColorLookupTableUID)
      removeTag(modified, Tag.MAC)
      setUidTagIfPresent(modified, Tag.MediaStorageSOPInstanceUID)
      removeTag(modified, Tag.MedicalAlerts)
      removeTag(modified, Tag.MedicalRecordLocator)
      removeTag(modified, Tag.MilitaryRank)
      removeTag(modified, Tag.ModifiedAttributesSequence)
      removeTag(modified, Tag.ModifiedImageDescription)
      removeTag(modified, Tag.ModifyingDeviceID)
      removeTag(modified, Tag.ModifyingDeviceManufacturer)
      removeTag(modified, Tag.NameOfPhysiciansReadingStudy)
      removeTag(modified, Tag.NamesOfIntendedRecipientsOfResults)
      setUidTagIfPresent(modified, Tag.ObservationSubjectUIDTrial)
      setUidTagIfPresent(modified, Tag.ObservationUID)
      removeTag(modified, Tag.Occupation)
      removeTag(modified, Tag.OperatorIdentificationSequence)
      removeTag(modified, Tag.OperatorsName)
      removeTag(modified, Tag.OriginalAttributesSequence)
      removeTag(modified, Tag.OrderCallbackPhoneNumber)
      removeTag(modified, Tag.OrderEnteredBy)
      removeTag(modified, Tag.OrderEntererLocation)
      removeTag(modified, Tag.OtherPatientIDs)
      removeTag(modified, Tag.OtherPatientIDsSequence)
      removeTag(modified, Tag.OtherPatientNames)
      removeTag(modified, Tag.OverlayComments)
      removeTag(modified, Tag.OverlayData)
      setUidTagIfPresent(modified, Tag.PaletteColorLookupTableUID)
      removeTag(modified, Tag.ParticipantSequence)
      removeTag(modified, Tag.PatientAddress)
      removeTag(modified, Tag.PatientComments)
      setStringTag(modified, Tag.PatientID, VR.LO, createUid())
      removeTag(modified, Tag.PatientState)
      removeTag(modified, Tag.PatientTransportArrangements)
      removeTag(modified, Tag.PatientBirthDate)
      removeTag(modified, Tag.PatientBirthName)
      removeTag(modified, Tag.PatientBirthTime)
      removeTag(modified, Tag.PatientInstitutionResidence)
      removeTag(modified, Tag.PatientInsurancePlanCodeSequence)
      removeTag(modified, Tag.PatientMotherBirthName)
      setStringTag(modified, Tag.PatientName, VR.PN, createAnonymousPatientName(sex, age))
      removeTag(modified, Tag.PatientPrimaryLanguageCodeSequence)
      removeTag(modified, Tag.PatientPrimaryLanguageModifierCodeSequence)
      removeTag(modified, Tag.PatientReligiousPreference)
      removeTag(modified, Tag.PatientTelephoneNumbers)
      removeTag(modified, Tag.PerformedLocation)
      removeTag(modified, Tag.PerformedProcedureStepDescription)
      removeTag(modified, Tag.PerformedProcedureStepID)
      removeTag(modified, Tag.PerformingPhysicianIdentificationSequence)
      removeTag(modified, Tag.PerformingPhysicianName)
      removeTag(modified, Tag.PersonAddress)
      removeTag(modified, Tag.PersonIdentificationCodeSequence) // type D
      removeTag(modified, Tag.PersonName) // type D
      removeTag(modified, Tag.PersonTelephoneNumbers)
      removeTag(modified, Tag.PhysicianApprovingInterpretation)
      removeTag(modified, Tag.PhysiciansReadingStudyIdentificationSequence)
      removeTag(modified, Tag.PhysiciansOfRecord)
      removeTag(modified, Tag.PhysiciansOfRecordIdentificationSequence)
      setStringTagIfPresent(modified, Tag.PlacerOrderNumberImagingServiceRequest, VR.LO, emptyString)
      removeTag(modified, Tag.PreMedication)
      removeTag(modified, Tag.ProtocolName)
      removeTag(modified, Tag.ReasonForTheImagingServiceRequest)
      removeTag(modified, Tag.ReasonForStudy)
      removeTag(modified, Tag.ReferencedDigitalSignatureSequence)
      setUidTagIfPresent(modified, Tag.ReferencedFrameOfReferenceUID)
      setUidTagIfPresent(modified, Tag.ReferencedGeneralPurposeScheduledProcedureStepTransactionUID)
      removeTag(modified, Tag.ReferencedImageSequence) // Keep in UID option but removed here
      setUidTagIfPresent(modified, Tag.ReferencedObservationUIDTrial)
      removeTag(modified, Tag.ReferencedPatientAliasSequence)
      removeTag(modified, Tag.ReferencedPatientPhotoSequence)
      removeTag(modified, Tag.ReferencedPatientSequence)
      removeTag(modified, Tag.ReferencedPerformedProcedureStepSequence) // Keep in UID option but removed here
      removeTag(modified, Tag.ReferencedSOPInstanceMACSequence)
      setUidTagIfPresent(modified, Tag.ReferencedSOPInstanceUID)
      setUidTagIfPresent(modified, Tag.ReferencedSOPInstanceUIDInFile)
      removeTag(modified, Tag.ReferencedStudySequence) // Keep in UID option but removed here
      removeTag(modified, Tag.ReferringPhysicianAddress)
      removeTag(modified, Tag.ReferringPhysicianIdentificationSequence)
      setStringTagIfPresent(modified, Tag.ReferringPhysicianName, VR.PN, emptyString)
      removeTag(modified, Tag.ReferringPhysicianTelephoneNumbers)
      removeTag(modified, Tag.RegionOfResidence)
      setUidTagIfPresent(modified, Tag.RelatedFrameOfReferenceUID)
      removeTag(modified, Tag.RequestAttributesSequence)
      removeTag(modified, Tag.RequestedContrastAgent)
      removeTag(modified, Tag.RequestedProcedureComments)
      removeTag(modified, Tag.RequestedProcedureDescription)
      removeTag(modified, Tag.RequestedProcedureID)
      removeTag(modified, Tag.RequestedProcedureLocation)
      setUidTagIfPresent(modified, Tag.RequestedSOPInstanceUID)
      removeTag(modified, Tag.RequestingPhysician)
      removeTag(modified, Tag.RequestingService)
      removeTag(modified, Tag.ResponsibleOrganization)
      removeTag(modified, Tag.ResponsiblePerson)
      removeTag(modified, Tag.ResultsComments)
      removeTag(modified, Tag.ResultsDistributionListSequence)
      removeTag(modified, Tag.ResultsIDIssuer)
      removeTag(modified, Tag.ReviewerName)
      removeTag(modified, Tag.ScheduledHumanPerformersSequence)
      removeTag(modified, Tag.ScheduledPatientInstitutionResidence)
      removeTag(modified, Tag.ScheduledPerformingPhysicianIdentificationSequence)
      removeTag(modified, Tag.ScheduledPerformingPhysicianName)
      removeTag(modified, Tag.ScheduledProcedureStepDescription)
      removeTag(modified, Tag.SeriesDescription)
      setUidTagIfPresent(modified, Tag.SeriesInstanceUID)
      removeTag(modified, Tag.ServiceEpisodeDescription)
      removeTag(modified, Tag.ServiceEpisodeID)
      setUidTag(modified, Tag.SOPInstanceUID)
      removeTag(modified, Tag.SourceImageSequence) // Keep in UID option but removed here
      removeTag(modified, Tag.SpecialNeeds)
      setUidTagIfPresent(modified, Tag.StorageMediaFileSetUID)
      removeTag(modified, Tag.StudyComments)
      removeTag(modified, Tag.StudyDescription)
      setStringTagIfPresent(modified, Tag.StudyID, VR.SH, emptyString)
      removeTag(modified, Tag.StudyIDIssuer)
      setUidTagIfPresent(modified, Tag.StudyInstanceUID)
      setUidTagIfPresent(modified, Tag.SynchronizationFrameOfReferenceUID)
      setUidTagIfPresent(modified, Tag.TargetUID)
      removeTag(modified, Tag.TelephoneNumberTrial)
      setUidTagIfPresent(modified, Tag.TemplateExtensionCreatorUID)
      setUidTagIfPresent(modified, Tag.TemplateExtensionOrganizationUID)
      removeTag(modified, Tag.TextComments)
      removeTag(modified, Tag.TextString)
      removeTag(modified, Tag.TopicAuthor)
      removeTag(modified, Tag.TopicKeywords)
      removeTag(modified, Tag.TopicSubject)
      removeTag(modified, Tag.TopicTitle)
      setUidTagIfPresent(modified, Tag.TransactionUID)
      setUidTagIfPresent(modified, Tag.UID)
      removeTag(modified, Tag.VerbalSourceTrial)
      removeTag(modified, Tag.VerbalSourceIdentifierCodeSequenceTrial)
      removeTag(modified, Tag.VerifyingObserverIdentificationCodeSequence) // type Z
      setStringTagIfPresent(modified, Tag.VerifyingObserverName, VR.PN, emptyString)
      removeTag(modified, Tag.VerifyingObserverSequence) // type D
      removeTag(modified, Tag.VerifyingOrganization)
      removeTag(modified, Tag.VisitComments)

      // remove private tags and all overlay data tags
      var toRemove = Seq.empty[Int]

      modified.accept(new Visitor() {
        def visit(attrs: Attributes, tag: Int, vr: VR, value: Object): Boolean = {

          if (TagUtils.isPrivateGroup(tag))
            toRemove = toRemove :+ tag

          val gn = TagUtils.groupNumber(tag)
          if (gn >= 0x6000 && gn < 0x6100)
            toRemove = toRemove :+ tag

          true
        }
      }, true)

      toRemove.foreach(tag => removeTag(modified, tag))

      modified
    }

  }

  def setStringTag(dataset: Attributes, tag: Int, vr: VR, value: String): Unit = dataset.setString(tag, vr, value)
  def setDateTag(dataset: Attributes, tag: Int, vr: VR, value: Date): Unit = dataset.setDate(tag, vr, value)
  def setUidTag(dataset: Attributes, tag: Int) = setStringTag(dataset, tag, VR.UI, createUid(dataset.getString(tag)))

  def removeTag(dataset: Attributes, tag: Int): Unit = dataset.remove(tag)

  def setStringTagIfPresent(dataset: Attributes, tag: Int, vr: VR, valueFunction: => String) =
    if (isPresent(dataset, tag))
      setStringTag(dataset, tag, vr, valueFunction)

  def setUidTagIfPresent(dataset: Attributes, tag: Int): Unit =
    setStringTagIfPresent(dataset, tag, VR.UI, createUid(dataset.getString(tag)))

  def isPresent(dataset: Attributes, tag: Int): Boolean = {
    val value = dataset.getString(tag)
    value != null && !value.isEmpty
  }

  def createUid(): String = createUid(null)

  def createUid(baseValue: String): String =
    if (baseValue == null || baseValue.isEmpty)
      UIDUtils.createUID()
    else
      UIDUtils.createNameBasedUID(baseValue.getBytes)

  def createAnonymousPatientName(sex: String, age: String) = {
    val sexString = if (sex == null || sex.isEmpty) "<unknown sex>" else sex
    val ageString = if (age == null || age.isEmpty) "<unknown age>" else age
    s"Anonymous $sexString $ageString"
  }

}

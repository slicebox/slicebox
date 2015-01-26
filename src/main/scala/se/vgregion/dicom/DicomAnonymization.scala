package se.vgregion.dicom

import org.dcm4che3.data.VR
import org.dcm4che3.util.UIDUtils
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import java.util.Date
import org.dcm4che3.util.TagUtils
import org.dcm4che3.data.Attributes.Visitor

object DicomAnonymization {

  val emptyString = ""
  val anonymousDate = new Date(0)

  def anonymizeDataset(dataset: Attributes): Attributes = {

    val patientName = dataset.getString(Tag.PatientName)
    val patientID = dataset.getString(Tag.PatientID)
    val sex = dataset.getString(Tag.PatientSex)
    val age = dataset.getString(Tag.PatientAge)

    val modified = cloneDataset(dataset)

    // this section from standard PS3.15 Table E.1-1

    setStringTag(dataset, Tag.PatientIdentityRemoved, VR.CS, "YES")

    setStringTag(modified, Tag.AccessionNumber, VR.SH, createUidOrLeaveEmpty(dataset.getString(Tag.AccessionNumber)))
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
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ConcatenationUID)
    removeTag(modified, Tag.Allergies)
    removeTag(modified, Tag.Arbitrary)
    removeTag(modified, Tag.AuthorObserverSequence)
    removeTag(modified, Tag.BranchOfService)
    removeTag(modified, Tag.CommentsOnThePerformedProcedureStep)
    removeTag(modified, Tag.ConfidentialityConstraintOnPatientDataDescription)
    setStringTag(modified, Tag.ContentCreatorName, VR.PN, emptyString)
    removeTag(modified, Tag.ContentCreatorIdentificationCodeSequence)
    removeTag(modified, Tag.ContentSequence)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ContextGroupExtensionCreatorUID)
    setStringTag(modified, Tag.ContrastBolusAgent, VR.LO, emptyString)
    removeTag(modified, Tag.ContributionDescription)
    removeTag(modified, Tag.CountryOfResidence)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.CreatorVersionUID)
    removeTag(modified, Tag.CurrentObserverTrial)
    removeTag(modified, Tag.CurrentPatientLocation)
    removeTag(modified, Tag.CurveData)
    removeTag(modified, Tag.CustodialOrganizationSequence)
    removeTag(modified, Tag.DataSetTrailingPadding)
    removeTag(modified, Tag.DerivationDescription)
    removeTag(modified, Tag.DigitalSignatureUID)
    removeTag(modified, Tag.DigitalSignaturesSequence)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.DimensionOrganizationUID)
    removeTag(modified, Tag.DischargeDiagnosisDescription)
    removeTag(modified, Tag.DistributionAddress)
    removeTag(modified, Tag.DistributionName)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.DoseReferenceUID)
    removeTag(modified, Tag.FailedSOPInstanceUIDList)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.FiducialUID)
    setStringTag(modified, Tag.FillerOrderNumberImagingServiceRequest, VR.LO, emptyString)
    removeTag(modified, Tag.FrameComments)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.FrameOfReferenceUID)
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
    setUidTagOrLeaveEmpty(modified, dataset, Tag.InstanceCreatorUID)
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
    setUidTagOrLeaveEmpty(modified, dataset, Tag.IrradiationEventUID)
    removeTag(modified, Tag.IssuerOfAdmissionID)
    removeTag(modified, Tag.IssuerOfPatientID)
    removeTag(modified, Tag.IssuerOfServiceEpisodeID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.LargePaletteColorLookupTableUID)
    removeTag(modified, Tag.MAC)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.MediaStorageSOPInstanceUID)
    removeTag(modified, Tag.MedicalAlerts)
    removeTag(modified, Tag.MedicalRecordLocator)
    removeTag(modified, Tag.MilitaryRank)
    removeTag(modified, Tag.ModifiedAttributesSequence)
    removeTag(modified, Tag.ModifiedImageDescription)
    removeTag(modified, Tag.ModifyingDeviceID)
    removeTag(modified, Tag.ModifyingDeviceManufacturer)
    removeTag(modified, Tag.NameOfPhysiciansReadingStudy)
    removeTag(modified, Tag.NamesOfIntendedRecipientsOfResults)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ObservationSubjectUIDTrial)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ObservationUID)
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
    setUidTagOrLeaveEmpty(modified, dataset, Tag.PaletteColorLookupTableUID)
    removeTag(modified, Tag.ParticipantSequence)
    removeTag(modified, Tag.PatientAddress)
    removeTag(modified, Tag.PatientComments)
    setStringTag(modified, Tag.PatientID, VR.LO, createUid(patientID))
    removeTag(modified, Tag.PatientState)
    removeTag(modified, Tag.PatientTransportArrangements)
    setDateTag(modified, Tag.PatientBirthDate, VR.DA, anonymousDate)
    removeTag(modified, Tag.PatientBirthName)
    removeTag(modified, Tag.PatientBirthTime)
    removeTag(modified, Tag.PatientInstitutionResidence)
    removeTag(modified, Tag.PatientInsurancePlanCodeSequence)
    removeTag(modified, Tag.PatientMotherBirthName)
    setStringTag(modified, Tag.PatientName, VR.SH, createAnonymousPatientName(sex, age))
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
    setStringTag(modified, Tag.PlacerOrderNumberImagingServiceRequest, VR.LO, emptyString)
    removeTag(modified, Tag.PreMedication)
    removeTag(modified, Tag.ProtocolName)
    removeTag(modified, Tag.ReasonForTheImagingServiceRequest)
    removeTag(modified, Tag.ReasonForStudy)
    removeTag(modified, Tag.ReferencedDigitalSignatureSequence)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ReferencedFrameOfReferenceUID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ReferencedGeneralPurposeScheduledProcedureStepTransactionUID)
    removeTag(modified, Tag.ReferencedImageSequence) // Keep in UID option but removed here
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ReferencedObservationUIDTrial)
    removeTag(modified, Tag.ReferencedPatientAliasSequence)
    removeTag(modified, Tag.ReferencedPatientPhotoSequence)
    removeTag(modified, Tag.ReferencedPatientSequence)
    removeTag(modified, Tag.ReferencedPerformedProcedureStepSequence) // Keep in UID option but removed here
    removeTag(modified, Tag.ReferencedSOPInstanceMACSequence)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ReferencedSOPInstanceUID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.ReferencedSOPInstanceUIDInFile)
    removeTag(modified, Tag.ReferencedStudySequence) // Keep in UID option but removed here
    removeTag(modified, Tag.ReferringPhysicianAddress)
    removeTag(modified, Tag.ReferringPhysicianIdentificationSequence)
    setStringTag(modified, Tag.ReferringPhysicianName, VR.PN, emptyString)
    removeTag(modified, Tag.ReferringPhysicianTelephoneNumbers)
    removeTag(modified, Tag.RegionOfResidence)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.RelatedFrameOfReferenceUID)
    removeTag(modified, Tag.RequestAttributesSequence)
    removeTag(modified, Tag.RequestedContrastAgent)
    removeTag(modified, Tag.RequestedProcedureComments)
    removeTag(modified, Tag.RequestedProcedureDescription)
    removeTag(modified, Tag.RequestedProcedureID)
    removeTag(modified, Tag.RequestedProcedureLocation)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.RequestedSOPInstanceUID)
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
    setUidTagOrLeaveEmpty(modified, dataset, Tag.SeriesInstanceUID)
    removeTag(modified, Tag.ServiceEpisodeDescription)
    removeTag(modified, Tag.ServiceEpisodeID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.SOPInstanceUID)
    removeTag(modified, Tag.SourceImageSequence) // Keep in UID option but removed here
    removeTag(modified, Tag.SpecialNeeds)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.StorageMediaFileSetUID)
    removeTag(modified, Tag.StudyComments)
    removeTag(modified, Tag.StudyDescription)
    setStringTag(modified, Tag.StudyID, VR.SH, emptyString)
    removeTag(modified, Tag.StudyIDIssuer)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.StudyInstanceUID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.SynchronizationFrameOfReferenceUID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.TargetUID)
    removeTag(modified, Tag.TelephoneNumberTrial)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.TemplateExtensionCreatorUID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.TemplateExtensionOrganizationUID)
    removeTag(modified, Tag.TextComments)
    removeTag(modified, Tag.TextString)
    removeTag(modified, Tag.TopicAuthor)
    removeTag(modified, Tag.TopicKeywords)
    removeTag(modified, Tag.TopicSubject)
    removeTag(modified, Tag.TopicTitle)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.TransactionUID)
    setUidTagOrLeaveEmpty(modified, dataset, Tag.UID)
    removeTag(modified, Tag.VerbalSourceTrial)
    removeTag(modified, Tag.VerbalSourceIdentifierCodeSequenceTrial)
    removeTag(modified, Tag.VerifyingObserverIdentificationCodeSequence) // type Z
    setStringTag(modified, Tag.VerifyingObserverName, VR.PN, emptyString)
    removeTag(modified, Tag.VerifyingObserverSequence) // type D
    removeTag(modified, Tag.VerifyingOrganization)
    removeTag(modified, Tag.VisitComments)

    // remove private tags and for good measure any tags containing either the patient name or id
    var toRemove = Seq.empty[Int]

    modified.accept(new Visitor() {
      def visit(attrs: Attributes, tag: Int, vr: VR, value: Object): Boolean = {

        if (TagUtils.isPrivateGroup(tag))
          toRemove = toRemove :+ tag

        if (value.isInstanceOf[String]) {
          val stringValue = value.asInstanceOf[String]
          if (patientName != null && stringValue.contains(patientName) || patientID != null && stringValue.contains(patientID))
            toRemove = toRemove :+ tag
        }

        true
      }
    }, true)

    toRemove.foreach(tag => removeTag(modified, tag))

    modified
  }

  def cloneDataset(dataset: Attributes): Attributes = new Attributes(dataset)

  def setStringTag(dataset: Attributes, tag: Int, vr: VR, value: String): Unit = dataset.setString(tag, vr, value)
  def setDateTag(dataset: Attributes, tag: Int, vr: VR, value: Date): Unit = dataset.setDate(tag, vr, value)
  def removeTag(dataset: Attributes, tag: Int): Unit = dataset.remove(tag)

  def setUidTagOrLeaveEmpty(modified: Attributes, dataset: Attributes, tag: Int) =
    setStringTag(modified, tag, VR.UI, createUidOrLeaveEmpty(dataset.getString(tag)))

  def createUidsOrLeaveEmpty(baseValues: String*): Array[String] =
    if (baseValues == null)
      null
    else
      baseValues.map(createUidOrLeaveEmpty(_)).toArray

  def createUidOrLeaveEmpty(baseValue: String): String = leaveEmpty(baseValue).getOrElse(createUid(baseValue))

  def leaveEmpty(baseValue: String): Option[String] =
    if (baseValue == null)
      Some(null)
    else if (baseValue.isEmpty())
      Some(baseValue)
    else
      None

  def createUid(baseValue: String): String =
    if (baseValue == null || baseValue.isEmpty)
      UIDUtils.createUID()
    else
      UIDUtils.createNameBasedUID(baseValue.getBytes)

  def createAnonymousPatientName(sex: String, age: String) = {
    val sexString = if (sex == null || sex.isEmpty) "<unknown sex>" else sex
    val ageString = if (age == null || age.isEmpty) "<unknown age>" else age
    s"Anonymous $sex $age"
  }
}
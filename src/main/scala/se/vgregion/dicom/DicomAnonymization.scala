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
  val anonymousString = "anonymous"
  val unknownString = "unknown"
  val anonymousDate = new Date(0)
  val anonymousPregnancyStatus = 4

  def anonymizeDataset(dataset: Attributes): Attributes = {

    val patientName = dataset.getString(Tag.PatientName)
    val patientID = dataset.getString(Tag.PatientID)
    val sex = dataset.getString(Tag.PatientSex)
    val age = dataset.getString(Tag.PatientAge)
    val studyInstanceUID = dataset.getString(Tag.StudyInstanceUID)
    val seriesInstanceUID = dataset.getString(Tag.SeriesInstanceUID)
    val sopInstanceUID = dataset.getString(Tag.SOPInstanceUID)
    val accessionNumber = dataset.getString(Tag.AccessionNumber)
    val studyID = dataset.getString(Tag.StudyID)
    val performedProcedureStepID = dataset.getString(Tag.PerformedProcedureStepID)
    val requestedProcedureID = dataset.getString(Tag.RequestedProcedureID)

    val modified = cloneDataset(dataset)

    // this section from standard PS3.15 Table E.1-1

    setStringTag(dataset, Tag.PatientIdentityRemoved, VR.CS, "YES")

    setStringTag(modified, Tag.AccessionNumber, VR.SH, createUidOrLeaveEmpty(accessionNumber))
    removeTag(dataset, Tag.AcquisitionComments)
    removeTag(dataset, Tag.AcquisitionContextSequence)
    removeTag(dataset, Tag.AcquisitionDeviceProcessingDescription)
    removeTag(dataset, Tag.AcquisitionProtocolDescription)
    removeTag(dataset, Tag.ActualHumanPerformersSequence)
    removeTag(dataset, Tag.AdditionalPatientHistory)
    removeTag(dataset, Tag.AddressTrial)
    removeTag(dataset, Tag.AdmissionID)
    removeTag(dataset, Tag.AdmittingDiagnosesCodeSequence)
    removeTag(dataset, Tag.AdmittingDiagnosesDescription)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ConcatenationUID)
    removeTag(dataset, Tag.Allergies)
    removeTag(dataset, Tag.Arbitrary)
    removeTag(dataset, Tag.AuthorObserverSequence)
    removeTag(dataset, Tag.BranchOfService)
    removeTag(dataset, Tag.CommentsOnThePerformedProcedureStep)
    removeTag(dataset, Tag.ConfidentialityConstraintOnPatientDataDescription)
    setStringTag(modified, Tag.ContentCreatorName, VR.PN, anonymizeOrLeaveEmpty(dataset.getString(Tag.ContentCreatorName)))
    removeTag(dataset, Tag.ContentCreatorIdentificationCodeSequence)
    removeTag(dataset, Tag.ContentSequence)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ContextGroupExtensionCreatorUID)
    setStringTag(dataset, Tag.ContrastBolusAgent, VR.LO, emptyString)
    removeTag(dataset, Tag.ContributionDescription)
    removeTag(dataset, Tag.CountryOfResidence)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.CreatorVersionUID)
    removeTag(dataset, Tag.CurrentObserverTrial)
    removeTag(dataset, Tag.CurrentPatientLocation)
    removeTag(dataset, Tag.CurveData)
    removeTag(dataset, Tag.CustodialOrganizationSequence)
    removeTag(dataset, Tag.DataSetTrailingPadding)
    removeTag(dataset, Tag.DerivationDescription)
    removeTag(dataset, Tag.DigitalSignatureUID)
    removeTag(dataset, Tag.DigitalSignaturesSequence)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.DimensionOrganizationUID)
    removeTag(dataset, Tag.DischargeDiagnosisDescription)
    removeTag(dataset, Tag.DistributionAddress)
    removeTag(dataset, Tag.DistributionName)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.DoseReferenceUID)
    setUidTagsOrLeaveEmpty(dataset, modified, Tag.FailedSOPInstanceUIDList)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.FiducialUID)
    setStringTag(dataset, Tag.FillerOrderNumberImagingServiceRequest, VR.LO, emptyString)
    removeTag(dataset, Tag.FrameComments)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.FrameOfReferenceUID)
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
    setUidTagOrLeaveEmpty(dataset, modified, Tag.InstanceCreatorUID)
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
    setUidTagOrLeaveEmpty(dataset, modified, Tag.IrradiationEventUID)
    removeTag(modified, Tag.IssuerOfAdmissionID)
    removeTag(modified, Tag.IssuerOfPatientID)
    removeTag(modified, Tag.IssuerOfServiceEpisodeID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.LargePaletteColorLookupTableUID)
    removeTag(modified, Tag.MAC)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.MediaStorageSOPInstanceUID)
    removeTag(modified, Tag.MedicalAlerts)
    removeTag(modified, Tag.MedicalRecordLocator)
    removeTag(modified, Tag.MilitaryRank)
    removeTag(modified, Tag.ModifiedAttributesSequence)
    removeTag(modified, Tag.ModifiedImageDescription)
    removeTag(modified, Tag.ModifyingDeviceID)
    removeTag(modified, Tag.ModifyingDeviceManufacturer)
    removeTag(modified, Tag.NameOfPhysiciansReadingStudy)
    removeTag(modified, Tag.NamesOfIntendedRecipientsOfResults)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ObservationSubjectUIDTrial)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ObservationUID)
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
    setUidTagOrLeaveEmpty(dataset, modified, Tag.PaletteColorLookupTableUID)
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
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ReferencedFrameOfReferenceUID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ReferencedGeneralPurposeScheduledProcedureStepTransactionUID)
    removeTag(modified, Tag.ReferencedImageSequence) // Keep in UID option but removed here
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ReferencedObservationUIDTrial)
    removeTag(modified, Tag.ReferencedPatientAliasSequence)
    removeTag(modified, Tag.ReferencedPatientPhotoSequence)
    removeTag(modified, Tag.ReferencedPatientSequence)
    removeTag(modified, Tag.ReferencedPerformedProcedureStepSequence) // Keep in UID option but removed here
    removeTag(modified, Tag.ReferencedSOPInstanceMACSequence)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ReferencedSOPInstanceUID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.ReferencedSOPInstanceUIDInFile)
    removeTag(modified, Tag.ReferencedStudySequence) // Keep in UID option but removed here
    removeTag(modified, Tag.ReferringPhysicianAddress)
    removeTag(modified, Tag.ReferringPhysicianIdentificationSequence)
    setStringTag(modified, Tag.ReferringPhysicianName, VR.PN, emptyString)
    removeTag(modified, Tag.ReferringPhysicianTelephoneNumbers)
    removeTag(modified, Tag.RegionOfResidence)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.RelatedFrameOfReferenceUID)
    removeTag(modified, Tag.RequestAttributesSequence)
    removeTag(modified, Tag.RequestedContrastAgent)
    removeTag(modified, Tag.RequestedProcedureComments)
    removeTag(modified, Tag.RequestedProcedureDescription)
    removeTag(modified, Tag.RequestedProcedureID)
    removeTag(modified, Tag.RequestedProcedureLocation)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.RequestedSOPInstanceUID)
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
    setUidTagOrLeaveEmpty(dataset, modified, Tag.SeriesInstanceUID)
    removeTag(modified, Tag.ServiceEpisodeDescription)
    removeTag(modified, Tag.ServiceEpisodeID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.SOPInstanceUID)
    removeTag(modified, Tag.SourceImageSequence) // Keep in UID option but removed here
    removeTag(modified, Tag.SpecialNeeds)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.StorageMediaFileSetUID)
    removeTag(modified, Tag.StudyComments)
    removeTag(modified, Tag.StudyDescription)
    setStringTag(modified, Tag.StudyID, VR.SH, emptyString)
    removeTag(modified, Tag.StudyIDIssuer)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.StudyInstanceUID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.SynchronizationFrameOfReferenceUID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.TargetUID)
    removeTag(modified, Tag.TelephoneNumberTrial)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.TemplateExtensionCreatorUID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.TemplateExtensionOrganizationUID)
    removeTag(modified, Tag.TextComments)
    removeTag(modified, Tag.TextString)
    removeTag(modified, Tag.TopicAuthor)
    removeTag(modified, Tag.TopicKeywords)
    removeTag(modified, Tag.TopicSubject)
    removeTag(modified, Tag.TopicTitle)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.TransactionUID)
    setUidTagOrLeaveEmpty(dataset, modified, Tag.UID)
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

        if (TagUtils.isPrivateTag(tag))
          toRemove = toRemove :+ tag

        if (value.isInstanceOf[String]) {
          val stringValue = value.asInstanceOf[String]
          if (stringValue.contains(patientName) || stringValue.contains(patientID))
            toRemove = toRemove :+ tag
        }

        true
      }
    }, true)

    toRemove.foreach(tag => removeTag(modified, tag))

    modified
  }

  def cloneDataset(dataset: Attributes): Attributes = new Attributes(dataset)

  def setStringTag(dataset: Attributes, tag: Int, vr: VR, values: String*): Unit =
    if (values == null)
      dataset.setString(tag, vr, null)
    else
      dataset.setString(tag, vr, values: _*)

  def setDateTag(dataset: Attributes, tag: Int, vr: VR, value: Date): Unit = dataset.setDate(tag, vr, value)
  def removeTag(dataset: Attributes, tag: Int): Unit = dataset.remove(tag)

  def setUidTagOrLeaveEmpty(dataset: Attributes, modified: Attributes, tag: Int) =
    setStringTag(modified, tag, VR.UI, createUidOrLeaveEmpty(dataset.getString(tag)))
  def setUidTagsOrLeaveEmpty(dataset: Attributes, modified: Attributes, tag: Int) =
    setStringTag(modified, tag, VR.UI, createUidsOrLeaveEmpty(dataset.getStrings(tag): _*): _*)

  def createUidsOrLeaveEmpty(baseValues: String*): Array[String] =
    if (baseValues == null)
      null
    else
      baseValues.map(createUidOrLeaveEmpty(_)).toArray

  def createUidOrLeaveEmpty(baseValue: String): String = leaveEmpty(baseValue).getOrElse(createUid(baseValue))

  def anonymizeOrLeaveEmpty(baseValue: String): String = leaveEmpty(baseValue).getOrElse(anonymousString)

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
    s"$anonymousString $sex $age"
  }
}
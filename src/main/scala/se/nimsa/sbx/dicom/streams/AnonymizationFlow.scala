package se.nimsa.sbx.dicom.streams

import java.util.UUID

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import org.dcm4che3.data.{Tag, VR}
import org.dcm4che3.util.UIDUtils
import se.nimsa.dcm4che.streams.{DicomFlows, DicomParsing}
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.dicom.DicomUtil

import scala.util.Random

object AnonymizationFlow {

  private def toAsciiBytes(s: String, vr: VR) = DicomUtil.padToEvenLength(ByteString(s), vr)
  private def insert(tag: Int, mod: ByteString => ByteString) = TagModification(tag, mod, insert = true)
  private def modify(tag: Int, mod: ByteString => ByteString) = TagModification(tag, mod, insert = false)
  private def clear(tag: Int) = TagModification(tag, _ => ByteString.empty, insert = false)
  private def createAccessionNumber(accessionNumberBytes: ByteString): ByteString = {
    val seed = UUID.nameUUIDFromBytes(accessionNumberBytes.toArray).getMostSignificantBits
    val rand = new Random(seed)
    val newNumber = (1 to 16).foldLeft("")((s, _) => s + rand.nextInt(10).toString)
    toAsciiBytes(newNumber, VR.SH)
  }
  private def createUid(baseValue: ByteString): ByteString = toAsciiBytes(
    if (baseValue == null || baseValue.isEmpty)
      UIDUtils.createUID()
    else
      UIDUtils.createNameBasedUID(baseValue.toArray), VR.UI)
  private def isOverlay(tag: Int): Boolean = {
    val group = DicomParsing.groupNumber(tag)
    group >= 0x6000 && group < 0x6100
  }

  private val removeTags = Set(
    Tag.AcquisitionComments,
    Tag.AcquisitionContextSequence,
    Tag.AcquisitionDeviceProcessingDescription,
    Tag.AcquisitionProtocolDescription,
    Tag.ActualHumanPerformersSequence,
    Tag.AdditionalPatientHistory,
    Tag.AddressTrial,
    Tag.AdmissionID,
    Tag.AdmittingDiagnosesCodeSequence,
    Tag.AdmittingDiagnosesDescription,
    Tag.Allergies,
    Tag.Arbitrary,
    Tag.AuthorObserverSequence,
    Tag.BranchOfService,
    Tag.CommentsOnThePerformedProcedureStep,
    Tag.ConfidentialityConstraintOnPatientDataDescription,
    Tag.ContentCreatorIdentificationCodeSequence,
    Tag.ContentSequence,
    Tag.ContributionDescription,
    Tag.CountryOfResidence,
    Tag.CurrentObserverTrial,
    Tag.CurrentPatientLocation,
    Tag.CurveData,
    Tag.CustodialOrganizationSequence,
    Tag.DataSetTrailingPadding,
    Tag.DerivationDescription,
    Tag.DigitalSignatureUID,
    Tag.DigitalSignaturesSequence,
    Tag.DischargeDiagnosisDescription,
    Tag.DistributionAddress,
    Tag.DistributionName,
    Tag.FailedSOPInstanceUIDList,
    Tag.FrameComments,
    Tag.GraphicAnnotationSequence, // type D
    Tag.HumanPerformerName,
    Tag.HumanPerformerOrganization,
    Tag.IconImageSequence,
    Tag.IdentifyingComments,
    Tag.ImageComments,
    Tag.ImagePresentationComments,
    Tag.ImagingServiceRequestComments,
    Tag.Impressions,
    Tag.InstanceCoercionDateTime,
    Tag.InstitutionAddress,
    Tag.InstitutionCodeSequence,
    Tag.InstitutionName,
    Tag.InstitutionalDepartmentName,
    Tag.InsurancePlanIdentification,
    Tag.IntendedRecipientsOfResultsIdentificationSequence,
    Tag.InterpretationApproverSequence,
    Tag.InterpretationAuthor,
    Tag.InterpretationDiagnosisDescription,
    Tag.InterpretationIDIssuer,
    Tag.InterpretationRecorder,
    Tag.InterpretationText,
    Tag.InterpretationTranscriber,
    Tag.IssuerOfAdmissionID,
    Tag.IssuerOfPatientID,
    Tag.IssuerOfServiceEpisodeID,
    Tag.MAC,
    Tag.MedicalAlerts,
    Tag.MedicalRecordLocator,
    Tag.MilitaryRank,
    Tag.ModifiedAttributesSequence,
    Tag.ModifiedImageDescription,
    Tag.ModifyingDeviceID,
    Tag.ModifyingDeviceManufacturer,
    Tag.NameOfPhysiciansReadingStudy,
    Tag.NamesOfIntendedRecipientsOfResults,
    Tag.Occupation,
    Tag.OperatorIdentificationSequence,
    Tag.OperatorsName,
    Tag.OriginalAttributesSequence,
    Tag.OrderCallbackPhoneNumber,
    Tag.OrderEnteredBy,
    Tag.OrderEntererLocation,
    Tag.OtherPatientIDs,
    Tag.OtherPatientIDsSequence,
    Tag.OtherPatientNames,
    Tag.OverlayComments,
    Tag.OverlayData,
    Tag.ParticipantSequence,
    Tag.PatientAddress,
    Tag.PatientComments,
    Tag.PatientState,
    Tag.PatientTransportArrangements,
    Tag.PatientBirthDate,
    Tag.PatientBirthName,
    Tag.PatientBirthTime,
    Tag.PatientInstitutionResidence,
    Tag.PatientInsurancePlanCodeSequence,
    Tag.PatientMotherBirthName,
    Tag.PatientPrimaryLanguageCodeSequence,
    Tag.PatientPrimaryLanguageModifierCodeSequence,
    Tag.PatientReligiousPreference,
    Tag.PatientTelephoneNumbers,
    Tag.PerformedLocation,
    Tag.PerformedProcedureStepDescription,
    Tag.PerformedProcedureStepID,
    Tag.PerformingPhysicianIdentificationSequence,
    Tag.PerformingPhysicianName,
    Tag.PersonAddress,
    Tag.PersonIdentificationCodeSequence, // type D
    Tag.PersonName, // type D
    Tag.PersonTelephoneNumbers,
    Tag.PhysicianApprovingInterpretation,
    Tag.PhysiciansReadingStudyIdentificationSequence,
    Tag.PhysiciansOfRecord,
    Tag.PhysiciansOfRecordIdentificationSequence,
    Tag.PreMedication,
    Tag.ProtocolName,
    Tag.ReasonForTheImagingServiceRequest,
    Tag.ReasonForStudy,
    Tag.ReferencedDigitalSignatureSequence,
    Tag.ReferencedImageSequence, // Keep in UID option but removed here
    Tag.ReferencedPatientAliasSequence,
    Tag.ReferencedPatientPhotoSequence,
    Tag.ReferencedPatientSequence,
    Tag.ReferencedPerformedProcedureStepSequence, // Keep in UID option but removed here
    Tag.ReferencedSOPInstanceMACSequence,
    Tag.ReferencedStudySequence, // Keep in UID option but removed here
    Tag.ReferringPhysicianAddress,
    Tag.ReferringPhysicianIdentificationSequence,
    Tag.ReferringPhysicianTelephoneNumbers,
    Tag.RegionOfResidence,
    Tag.RequestAttributesSequence,
    Tag.RequestedContrastAgent,
    Tag.RequestedProcedureComments,
    Tag.RequestedProcedureDescription,
    Tag.RequestedProcedureID,
    Tag.RequestedProcedureLocation,
    Tag.RequestingPhysician,
    Tag.RequestingService,
    Tag.ResponsibleOrganization,
    Tag.ResponsiblePerson,
    Tag.ResultsComments,
    Tag.ResultsDistributionListSequence,
    Tag.ResultsIDIssuer,
    Tag.ReviewerName,
    Tag.ScheduledHumanPerformersSequence,
    Tag.ScheduledPatientInstitutionResidence,
    Tag.ScheduledPerformingPhysicianIdentificationSequence,
    Tag.ScheduledPerformingPhysicianName,
    Tag.ScheduledProcedureStepDescription,
    Tag.SeriesDescription,
    Tag.ServiceEpisodeDescription,
    Tag.ServiceEpisodeID,
    Tag.SourceImageSequence, // Keep in UID option but removed here
    Tag.SpecialNeeds,
    Tag.StudyComments,
    Tag.StudyDescription,
    Tag.StudyIDIssuer,
    Tag.TelephoneNumberTrial,
    Tag.TextComments,
    Tag.TextString,
    Tag.TopicAuthor,
    Tag.TopicKeywords,
    Tag.TopicSubject,
    Tag.TopicTitle,
    Tag.VerbalSourceTrial,
    Tag.VerbalSourceIdentifierCodeSequenceTrial,
    Tag.VerifyingObserverIdentificationCodeSequence, // type Z
    Tag.VerifyingObserverSequence, // type D
    Tag.VerifyingOrganization,
    Tag.VisitComments)

  /**
    * From standard PS3.15 Table E.1-1
    * Remove all private attributes
    * Remove overlay data
    * Remove, set empty or modify certain attributes
    */
  val anonFlow = Flow[DicomPart]
    .via(DicomFlows.blacklistFilter(DicomParsing.isPrivateAttribute)) // remove private attributes
    .via(DicomFlows.blacklistFilter(isOverlay)) // remove overlay data
    .via(DicomFlows.blacklistFilter(removeTags.contains)) // remove tags from above list, if present
    .via(DicomFlows.modifyFlow( // modify, clear and insert
    modify(Tag.AccessionNumber, bytes => if (bytes.nonEmpty) createAccessionNumber(bytes) else bytes),
    modify(Tag.ConcatenationUID, createUid),
    clear(Tag.ContentCreatorName),
    modify(Tag.ContextGroupExtensionCreatorUID, createUid),
    clear(Tag.ContrastBolusAgent),
    modify(Tag.CreatorVersionUID, createUid),
    insert(Tag.DeidentificationMethod, _ => toAsciiBytes("Retain Longitudinal Full Dates Option", VR.LO)),
    modify(Tag.DimensionOrganizationUID, createUid),
    modify(Tag.DoseReferenceUID, createUid),
    modify(Tag.FiducialUID, createUid),
    clear(Tag.FillerOrderNumberImagingServiceRequest),
    modify(Tag.FrameOfReferenceUID, _ => createUid(null)),
    modify(Tag.InstanceCreatorUID, createUid),
    modify(Tag.IrradiationEventUID, createUid),
    modify(Tag.LargePaletteColorLookupTableUID, createUid),
    modify(Tag.MediaStorageSOPInstanceUID, createUid),
    modify(Tag.ObservationSubjectUIDTrial, createUid),
    modify(Tag.ObservationUID, createUid),
    modify(Tag.PaletteColorLookupTableUID, createUid),
    insert(Tag.PatientIdentityRemoved, _ => toAsciiBytes("YES", VR.CS)),
    insert(Tag.PatientID, _ => createUid(null)),
    insert(Tag.PatientName, _ => createUid(null)),
    clear(Tag.PlacerOrderNumberImagingServiceRequest),
    modify(Tag.ReferencedFrameOfReferenceUID, createUid),
    modify(Tag.ReferencedGeneralPurposeScheduledProcedureStepTransactionUID, createUid),
    modify(Tag.ReferencedObservationUIDTrial, createUid),
    modify(Tag.ReferencedSOPInstanceUID, createUid),
    modify(Tag.ReferencedSOPInstanceUIDInFile, createUid),
    clear(Tag.ReferringPhysicianName),
    modify(Tag.RelatedFrameOfReferenceUID, createUid),
    modify(Tag.RequestedSOPInstanceUID, createUid),
    insert(Tag.SeriesInstanceUID, _ => createUid(null)),
    insert(Tag.SOPInstanceUID, createUid),
    modify(Tag.StorageMediaFileSetUID, createUid),
    clear(Tag.StudyID),
    insert(Tag.StudyInstanceUID, _ => createUid(null)),
    modify(Tag.SynchronizationFrameOfReferenceUID, createUid),
    modify(Tag.TargetUID, createUid),
    modify(Tag.TemplateExtensionCreatorUID, createUid),
    modify(Tag.TemplateExtensionOrganizationUID, createUid),
    modify(Tag.TransactionUID, createUid),
    modify(Tag.UID, createUid),
    clear(Tag.VerifyingObserverName)
  ))

  /**
    * Anonymize data if not already anonymized. Assumes first `DicomPart` is a `DicomMetaPart` that is used to determine
    * if data has been anonymized or not.
    *
    * @return a `Flow` of `DicomParts` that will anonymize non-anonymized data but does nothing otherwise
    */
  def maybeAnonFlow = DicomStreams.conditionalFlow(
    {
      case p: DicomMetaPart => !p.isAnonymized
    }, anonFlow, Flow.fromFunction(identity))

}









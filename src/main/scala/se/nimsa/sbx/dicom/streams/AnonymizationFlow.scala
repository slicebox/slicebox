/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import se.nimsa.dicom.data.DicomParsing.isPrivate
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.data.{Tag, TagPath, VR}
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.streams.ModifyFlow.{TagModification, modifyFlow}
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.dicom.DicomUtil._

object AnonymizationFlow {


  private def insert(tag: Int, mod: ByteString => ByteString): TagModification = TagModification.endsWith(TagPath.fromTag(tag), mod, insert = true)
  private def modify(tag: Int, mod: ByteString => ByteString): TagModification = TagModification.endsWith(TagPath.fromTag(tag), mod, insert = false)
  private def clear(tag: Int): TagModification = TagModification.endsWith(TagPath.fromTag(tag), _ => ByteString.empty, insert = false)

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
    Tag.VisitComments
  )

  /**
    * From standard PS3.15 Table E.1-1
    * Remove all private attributes
    * Remove overlay data
    * Remove, set empty or modify certain attributes
    */
  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] =
    tagFilter(_ => true)(tagPath =>
      !tagPath.toList.map(_.tag).exists(tag =>
        isPrivate(tag) || isOverlay(tag) || removeTags.contains(tag))) // remove private, overlay and PHI attributes
      .via(modifyFlow( // modify, clear and insert
      modify(Tag.AccessionNumber, bytes => if (bytes.nonEmpty) createAccessionNumber() else bytes),
      modify(Tag.ConcatenationUID, _ => createUid()),
      clear(Tag.ContentCreatorName),
      modify(Tag.ContextGroupExtensionCreatorUID, _ => createUid()),
      clear(Tag.ContrastBolusAgent),
      modify(Tag.CreatorVersionUID, _ => createUid()),
      insert(Tag.DeidentificationMethod, _ => toAsciiBytes("Retain Longitudinal Full Dates Option", VR.LO)),
      modify(Tag.DimensionOrganizationUID, _ => createUid()),
      modify(Tag.DoseReferenceUID, _ => createUid()),
      modify(Tag.FiducialUID, _ => createUid()),
      clear(Tag.FillerOrderNumberImagingServiceRequest),
      modify(Tag.FrameOfReferenceUID, _ => createUid()),
      modify(Tag.InstanceCreatorUID, _ => createUid()),
      modify(Tag.IrradiationEventUID, _ => createUid()),
      modify(Tag.LargePaletteColorLookupTableUID, _ => createUid()),
      modify(Tag.MediaStorageSOPInstanceUID, _ => createUid()),
      modify(Tag.ObservationSubjectUIDTrial, _ => createUid()),
      modify(Tag.ObservationUID, _ => createUid()),
      modify(Tag.PaletteColorLookupTableUID, _ => createUid()),
      insert(Tag.PatientIdentityRemoved, _ => toAsciiBytes("YES", VR.CS)),
      insert(Tag.PatientID, _ => createUid()),
      insert(Tag.PatientName, _ => createUid()),
      clear(Tag.PlacerOrderNumberImagingServiceRequest),
      modify(Tag.ReferencedFrameOfReferenceUID, _ => createUid()),
      modify(Tag.ReferencedGeneralPurposeScheduledProcedureStepTransactionUID, _ => createUid()),
      modify(Tag.ReferencedObservationUIDTrial, _ => createUid()),
      modify(Tag.ReferencedSOPInstanceUID, _ => createUid()),
      modify(Tag.ReferencedSOPInstanceUIDInFile, _ => createUid()),
      clear(Tag.ReferringPhysicianName),
      modify(Tag.RelatedFrameOfReferenceUID, _ => createUid()),
      modify(Tag.RequestedSOPInstanceUID, _ => createUid()),
      insert(Tag.SeriesInstanceUID, _ => createUid()),
      insert(Tag.SOPInstanceUID, _ => createUid()),
      modify(Tag.StorageMediaFileSetUID, _ => createUid()),
      clear(Tag.StudyID),
      insert(Tag.StudyInstanceUID, _ => createUid()),
      modify(Tag.SynchronizationFrameOfReferenceUID, _ => createUid()),
      modify(Tag.TargetUID, _ => createUid()),
      modify(Tag.TemplateExtensionCreatorUID, _ => createUid()),
      modify(Tag.TemplateExtensionOrganizationUID, _ => createUid()),
      modify(Tag.TransactionUID, _ => createUid()),
      modify(Tag.UID, _ => createUid()),
      clear(Tag.VerifyingObserverName)))
}











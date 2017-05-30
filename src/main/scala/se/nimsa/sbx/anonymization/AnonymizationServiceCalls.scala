package se.nimsa.sbx.anonymization

import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

trait AnonymizationServiceCalls {

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]

  implicit def executor: ExecutionContextExecutor

  val anonymizationInsert = (anonymizationKey: AnonymizationKey) =>
    callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonymizationKey))
      .map(_.anonymizationKey)

  val anonymizationQuery = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  val reverseAnonymizationQuery = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

}

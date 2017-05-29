package se.nimsa.sbx.anonymization

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}

import scala.concurrent.ExecutionContextExecutor

trait AnonymizationServiceCalls {

  val anonymizationService: ActorRef

  implicit val timeout: Timeout
  implicit def executor: ExecutionContextExecutor

  val anonymizationInsert = (anonymizationKey: AnonymizationKey) => anonymizationService
    .ask(AddAnonymizationKey(anonymizationKey))
    .mapTo[AnonymizationKeyAdded].map(_.anonymizationKey)

  val anonymizationQuery = (patientName: PatientName, patientID: PatientID) => anonymizationService
    .ask(GetAnonymizationKeysForPatient(patientName.value, patientID.value))
    .mapTo[AnonymizationKeys].map(_.anonymizationKeys)

  val reverseAnonymizationQuery = (patientName: PatientName, patientID: PatientID) => anonymizationService
    .ask(GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
    .mapTo[AnonymizationKeys].map(_.anonymizationKeys)

}

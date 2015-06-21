package se.nimsa.sbx.anonymization

import akka.actor.Actor
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import akka.actor.Props
import se.nimsa.sbx.dicom.DicomUtil._
import AnonymizationProtocol._
import AnonymizationUtil._
import org.dcm4che3.data.Attributes
import akka.event.Logging

class AnonymizationServiceActor(dbProps: DbProps) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)
  
  val db = dbProps.db
  val dao = new AnonymizationDAO(dbProps.driver)

  setupDb()

  log.info("Anonymization service started")

  def receive = LoggingReceive {

    case msg: AnonymizationRequest =>

      catchAndReport {
      
        msg match {
          case RemoveAnonymizationKey(anonymizationKeyId) =>
            removeAnonymizationKey(anonymizationKeyId)
            sender ! AnonymizationKeyRemoved(anonymizationKeyId)

          case GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter) =>
            sender ! AnonymizationKeys(listAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter))

          case ReverseAnonymization(dataset) =>
            val clonedDataset = cloneDataset(dataset)
            reverseAnonymization(anonymizationKeysForAnonPatient(clonedDataset), clonedDataset)
            sender ! clonedDataset

          case Anonymize(dataset, tagValues) =>
            val anonymizationKeys = anonymizationKeysForPatient(dataset)
            val anonDataset = anonymizeDataset(dataset)
            val harmonizedDataset = harmonizeAnonymization(anonymizationKeys, dataset, anonDataset)
            applyTagValues(harmonizedDataset, tagValues)

            val anonymizationKey = createAnonymizationKey(dataset, harmonizedDataset)
            if (!anonymizationKeys.exists(isEqual(_, anonymizationKey)))
              addAnonymizationKey(anonymizationKey)

            sender ! harmonizedDataset
        }
        
      }
  }

  def setupDb(): Unit =
    db.withSession { implicit session =>
      dao.create
    }

  def addAnonymizationKey(anonymizationKey: AnonymizationKey): AnonymizationKey =
    db.withSession { implicit session =>
      dao.insertAnonymizationKey(anonymizationKey)
    }

  def removeAnonymizationKey(anonymizationKeyId: Long) =
    db.withSession { implicit session =>
      dao.removeAnonymizationKey(anonymizationKeyId)
    }

  def listAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) =
    db.withSession { implicit session =>
      dao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter)
    }

  def anonymizationKeysForAnonPatient(dataset: Attributes) = {
    db.withSession { implicit session =>
      val anonPatient = datasetToPatient(dataset)
      dao.anonymizationKeysForAnonPatient(anonPatient.patientName.value, anonPatient.patientID.value)
    }
  }

  def anonymizationKeysForPatient(dataset: Attributes) = {
    db.withSession { implicit session =>
      val patient = datasetToPatient(dataset)
      dao.anonymizationKeysForPatient(patient.patientName.value, patient.patientID.value)
    }
  }

}

object AnonymizationServiceActor {
  def props(dbProps: DbProps): Props = Props(new AnonymizationServiceActor(dbProps))
}
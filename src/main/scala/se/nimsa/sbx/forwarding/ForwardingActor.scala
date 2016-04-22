package se.nimsa.sbx.forwarding

import akka.actor.{Actor, Props}
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ImageTagValues
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol.{Box, SendToRemoteBox}
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{DeleteMetaData, GetImage}
import se.nimsa.sbx.scu.ScuProtocol.SendImagesToScp
import se.nimsa.sbx.storage.StorageProtocol.DeleteDataset
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class ForwardingActor(rule: ForwardingRule, transaction: ForwardingTransaction, images: Seq[ForwardingTransactionImage], implicit val timeout: Timeout) extends Actor {

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val metaDataService = context.actorSelection("../../MetaDataService")
  val storageService = context.actorSelection("../../StorageService")
  val boxService = context.actorSelection("../../BoxService")
  val scuService = context.actorSelection("../../ScuService")

  val nonBoxBatchId = 1L

  system.scheduler.schedule(pollInterval + pollInterval / 2, pollInterval) {
    self ! FinalizeSentTransactions
  }

  /**
    * Happy flow for BOX sources:
    * ImageAdded (ImageRegisteredForForwarding to sender)
    * AddImageToForwardingQueue (one per applicable rule) (ImageAddedToForwardingQueue to sender of ImageAdded)
    * (transfer is made if batch is complete)
    * ImagesSent (TransactionMarkedAsDelivered to sender (box, scu))
    * FinalizeSentTransactions (TransactionsFinalized to sender (self))
    *
    * Happy flow for non-BOX sources:
    * ImageAdded (ImageRegisteredForForwarding to sender)
    * AddImageToForwardingQueue (one per applicable rule) (ImageAddedToForwardingQueue to sender of ImageAdded)
    * PollForwardingQueue (until transaction update period expires) (TransactionsEnroute to sender (self))
    * (transfer is made)
    * ImagesSent (TransactionMarkedAsDelivered to sender (box, scu))
    * FinalizeSentTransactions (TransactionsFinalized to sender (self))
    */
  def receive = LoggingReceive {
    case FinalizeForwarding =>

      if (imageIdsToDelete.nonEmpty)
        SbxLog.info("Forwarding", s"Deleted ${imageIdsToDelete.length} images after forwarding.")

      val (transactionsToRemove, idsOfDeletedImages) = finalizeSentTransactions()
      sender ! TransactionsFinalized(transactionsToRemove, idsOfDeletedImages)
  }

  def makeTransfer(): Unit = {

    SbxLog.info("Forwarding", s"Forwarding ${images.length} images from ${rule.source.sourceType.toString()} ${rule.source.sourceName} to ${rule.destination.destinationType.toString()} ${rule.destination.destinationName}.")

    val destinationId = rule.destination.destinationId
    val destinationName = rule.destination.destinationName
    val box = Box(destinationId, destinationName, "", "", null, online = false)

    val imageIds = images.map(_.id)

    rule.destination.destinationType match {
      case DestinationType.BOX =>
        boxService.ask(SendToRemoteBox(box, imageIds.map(ImageTagValues(_, Seq.empty))))
          .onFailure {
            case e: Throwable => SbxLog.error("Forwarding", "Could not forward images to remote box " + rule.destination.destinationName + ": " + e.getMessage)
          }
      case DestinationType.SCU =>
        scuService.ask(SendImagesToScp(imageIds, destinationId))
          .onFailure {
            case e: Throwable =>
              SbxLog.warn("Forwarding", "Could not forward images to SCP. Trying again later. Message: " + e.getMessage)
              self ! UpdateTransaction(transaction, enroute = false, delivered = false)
          }
      case _ =>
        SbxLog.error("Forwarding", "Unknown destination type")
    }
  }

  def finalizeSentTransactions(): (List[ForwardingProtocol.ForwardingTransaction], List[Long]) = {
    /*
     * This is tricky since we allow many rules with the same source, but different choices for keep images.
     * - It is safe to remove delivered transactions with keepImages=true
     * - If keepImages=false, wait until all transactions with the same source have been delivered,
     *   then delete.
     * - If there are multiple rules with the same source and differing choices for keepImages,
     *   keepImages=false wins, i.e. images will be deleted eventually.
     */
    val transactionsAndRules = transactionsToRulesAndTransactions(getDeliveredTransactions)
    val toRemoveButNotDeleteImages = transactionsAndRules.filter(_._1.keepImages).map(_._2)
    val toRemoveAndDeleteImages = transactionsAndRules.filter {
      case (rule, transaction) =>
        !rule.keepImages && getUndeliveredTransactionsForSource(rule.source).isEmpty
    }.map(_._2)
    val transactionsToRemove = toRemoveButNotDeleteImages ++ toRemoveAndDeleteImages
    val imageIdsToDelete = toRemoveAndDeleteImages.flatMap(transaction =>
      getTransactionImagesForTransactionId(transaction.id).map(_.imageId))
      .distinct
    transactionsToRemove.foreach(transaction => removeTransactionForId(transaction.id))
    deleteImages(imageIdsToDelete)

    if (transactionsToRemove.nonEmpty) {
      SbxLog.info("Forwarding", s"Finalized ${transactionsToRemove.length} transactions.")
      if (imageIdsToDelete.nonEmpty)
        SbxLog.info("Forwarding", s"Deleted ${imageIdsToDelete.length} images after forwarding.")
    }

    (transactionsToRemove, imageIdsToDelete)
  }


  def deleteImages(imageIds: List[Long]): Future[Seq[Long]] = {
    val futureDeletedImageIds = Future.sequence {
      imageIds.map { imageId =>
        metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].map { imageMaybe =>
          imageMaybe.map { image =>
            metaDataService.ask(DeleteMetaData(imageId)).flatMap { _ =>
              storageService.ask(DeleteDataset(image)).map { _ =>
                imageId
              }
            }
          }
        }.unwrap
      }
    }.map(_.flatten)

    futureDeletedImageIds.onFailure {
      case e: Throwable =>
        SbxLog.error("Forwarding", "Could not delete images after transfer: " + e.getMessage)
    }

    futureDeletedImageIds
  }

}

object ForwardingActor {
  def props(rule: ForwardingRule, transaction: ForwardingTransaction, images: Seq[ForwardingTransactionImage], timeout: Timeout): Props = Props(new ForwardingActor(rule, transaction, images, timeout))
}

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

class ForwardingActor(rule: ForwardingRule, transaction: ForwardingTransaction, images: Seq[ForwardingTransactionImage], implicit val timeout: Timeout) extends Actor {

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val metaDataService = context.actorSelection("../../MetaDataService")
  val storageService = context.actorSelection("../../StorageService")
  val boxService = context.actorSelection("../../BoxService")
  val scuService = context.actorSelection("../../ScuService")

  doForward()

  def receive = LoggingReceive {
    case FinalizeForwarding(deleteImages) =>
      if (deleteImages)
        doDelete().andThen {
          case _ => context.stop(self)
        }
      else
        context.stop(self)
  }

  def doForward(): Unit = {

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
              context.parent ! UpdateTransaction(transaction.copy(enroute = false, delivered = false))
          }
      case _ =>
        SbxLog.error("Forwarding", "Unknown destination type")
    }
  }

  def doDelete(): Future[Seq[Long]] = {

    val futureDeletedImageIds = Future.sequence {
      images.map(_.imageId).map { imageId =>
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

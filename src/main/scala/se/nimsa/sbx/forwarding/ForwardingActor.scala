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

package se.nimsa.sbx.forwarding

import akka.actor.{Actor, Props}
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ImageTagValues
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol.{Box, SendToRemoteBox}
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.DeleteMetaData
import se.nimsa.sbx.scu.ScuProtocol.SendImagesToScp
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.Future
import scala.util.{Failure, Success}

class ForwardingActor(rule: ForwardingRule, transaction: ForwardingTransaction, images: Seq[ForwardingTransactionImage], storage: StorageService)(implicit val timeout: Timeout) extends Actor {

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val metaDataService = context.actorSelection("../../MetaDataService")
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

    val imageIds = images.map(_.imageId)

    rule.destination.destinationType match {
      case DestinationType.BOX =>
        boxService.ask(SendToRemoteBox(box, imageIds.map(ImageTagValues(_, Seq.empty))))
          .onComplete {
            case Success(_) =>
            case Failure(e) => SbxLog.error("Forwarding", "Could not forward images to remote box " + rule.destination.destinationName + ": " + e.getMessage)
          }
      case DestinationType.SCU =>
        scuService.ask(SendImagesToScp(imageIds, destinationId))
          .onComplete {
            case Success(_) =>
            case Failure(e) =>
              SbxLog.warn("Forwarding", "Could not forward images to SCP. Trying again later. Message: " + e.getMessage)
              context.parent ! UpdateTransaction(transaction.copy(enroute = false, delivered = false))
          }
      case _ =>
        SbxLog.error("Forwarding", "Unknown destination type")
    }
  }

  def doDelete(): Future[Seq[Long]] = {

    val futureDeletedImageIds = {
      val imageIds = images.map(_.imageId)
      metaDataService.ask(DeleteMetaData(imageIds))
        .map { _ =>
          storage.deleteFromStorage(imageIds)
          system.eventStream.publish(ImagesDeleted(imageIds))
          imageIds
        }
    }

    futureDeletedImageIds.onComplete {
      case Failure(e) =>
        SbxLog.error("Forwarding", "Could not delete images after transfer: " + e.getMessage)
      case _ =>
    }

    futureDeletedImageIds
  }

}

object ForwardingActor {
  def props(rule: ForwardingRule, transaction: ForwardingTransaction, images: Seq[ForwardingTransactionImage], storage: StorageService)(implicit timeout: Timeout): Props = Props(new ForwardingActor(rule, transaction, images, storage))
}

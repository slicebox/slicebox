/*
 * Copyright 2015 Lars Edenbrandt
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

import ForwardingProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.util.Timeout
import akka.pattern.ask
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.storage.StorageProtocol._
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt
import se.nimsa.sbx.scu.ScuProtocol.SendImagesToScp
import se.nimsa.sbx.box.BoxProtocol.SendToRemoteBox
import se.nimsa.sbx.box.BoxProtocol.ImageTagValues
import se.nimsa.sbx.box.BoxProtocol.InboxEntry
import se.nimsa.sbx.box.BoxProtocol.GetInboxEntryForImageId
import se.nimsa.sbx.app.GeneralProtocol._

class ForwardingServiceActor(dbProps: DbProps, pollInterval: FiniteDuration = 30.seconds)(implicit timeout: Timeout) extends Actor {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val db = dbProps.db
  val forwardingDao = new ForwardingDAO(dbProps.driver)

  val storageService = context.actorSelection("../StorageService")
  val boxService = context.actorSelection("../BoxService")
  val scuService = context.actorSelection("../ScuService")

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageAdded])
    context.system.eventStream.subscribe(context.self, classOf[ImagesSent])
  }

  system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollTransactionQueue
  }

  log.info("Forwarding service started")

  def receive = LoggingReceive {

    case ImageAdded(image) =>
      maybeAddImageToForwardingQueue(image)

    case MaybeAddImageToForwardingQueue(image, sourceTypeAndId) =>
      maybeAddImageToForwardingQueue(image, sourceTypeAndId)

    case ImagesSent(imageIds) =>
      self ! RemoveTransactionAndMaybeDeleteImages(imageIds)

    case RemoveTransactionAndMaybeDeleteImages(imageIds) =>
      removeTransactionAndMaybeDeleteImages(imageIds)

    case PollTransactionQueue =>
      maybeTransferImagesForNonBoxSources

    case MarkTransactionAsProcessed(transaction, processed) =>
      markTransactionAsProcessed(transaction, processed)

    case MakeTransfer(rule, transaction) =>
      makeTransfer(rule, transaction)

    case msg: ForwardingRequest =>

      msg match {
        case GetForwardingRules =>
          val forwardingRules = getForwardingRulesFromDb()
          sender ! ForwardingRules(forwardingRules)

        case AddForwardingRule(forwardingRule) =>
          val dbForwardingRule = addForwardingRuleToDb(forwardingRule)
          sender ! ForwardingRuleAdded(dbForwardingRule)

        case RemoveForwardingRule(forwardingRuleId) =>
          removeForwardingRuleFromDb(forwardingRuleId)
          sender ! ForwardingRuleRemoved(forwardingRuleId)

      }
  }

  def getForwardingRulesFromDb() =
    db.withSession { implicit session =>
      forwardingDao.listForwardingRules
    }

  def addForwardingRuleToDb(forwardingRule: ForwardingRule): ForwardingRule =
    db.withSession { implicit session =>
      forwardingDao.insertForwardingRule(forwardingRule)
    }

  def removeForwardingRuleFromDb(forwardingRuleId: Long): Unit =
    db.withSession { implicit session =>
      forwardingDao.removeForwardingRule(forwardingRuleId)
    }

  def maybeAddImageToForwardingQueue(image: Image): Unit =
    if (hasForwardingRules) {

      // get series source of image
      val seriesTypeAndId = getSourceForSeries(image.seriesId).onComplete {
        case Success(seriesSourceMaybe) =>
          seriesSourceMaybe match {
            case Some(seriesSource) =>
              self ! MaybeAddImageToForwardingQueue(image, seriesSource.sourceTypeId)
            case None =>
              SbxLog.error("Forwarding", s"No series source found for image with id ${image.id}. Not adding this image to forwarding queue.")
          }
        case Failure(e) =>
          SbxLog.error("Forwarding", s"Error getting series source for image with id ${image.id}. Not adding this image to forwarding queue.")
      }
    }

  def maybeAddImageToForwardingQueue(image: Image, sourceTypeAndId: SourceTypeId) = {

    // look for rule
    val ruleMaybe = getForwardingRuleForSourceTypeAndId(sourceTypeAndId.sourceType, sourceTypeAndId.sourceId)

    // look for transaction, create or update (timestamp)
    val transaction = ruleMaybe.map(createOrUpdateForwardingTransaction(_))

    // add to queue
    val added = transaction.map(addImageToForwardingQueue(_, image))

    // check if source is box, if so, check if box transfer is complete and its time to forward
    for {
      forwardingRule <- ruleMaybe
      forwardingTransaction <- transaction
      forwardingTransactionImage <- added
    } yield {
      if (forwardingRule.source.sourceType == SourceType.BOX && !forwardingTransaction.processed)
        maybeTransferImagesForBoxSource(image.id, forwardingTransaction, forwardingRule)
    }
  }

  def hasForwardingRules: Boolean =
    db.withSession { implicit session =>
      forwardingDao.getNumberOfForwardingRules > 0
    }

  def getSourceForSeries(seriesId: Long): Future[Option[SeriesSource]] =
    storageService.ask(GetSourceForSeries(seriesId)).mapTo[Option[SeriesSource]]

  def getForwardingRuleForSourceTypeAndId(sourceType: SourceType, sourceId: Long): Option[ForwardingRule] =
    db.withSession { implicit session =>
      forwardingDao.getForwardingRuleForSourceTypeAndId(sourceType, sourceId)
    }

  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule): ForwardingTransaction =
    db.withSession { implicit session =>
      forwardingDao.createOrUpdateForwardingTransaction(forwardingRule)
    }

  def addImageToForwardingQueue(forwardingTransaction: ForwardingTransaction, image: Image) =
    db.withSession { implicit session =>
      forwardingDao.insertForwardingTransactionImage(ForwardingTransactionImage(-1, forwardingTransaction.id, image.id))
    }

  def maybeTransferImagesForNonBoxSources(): Unit = {
    // look for transactions to carry out
    val transactions = getExpiredButNotProcessedTransactions

    // transfer each transaction
    transactions.map(transaction => {

      // get rule
      val rule = getForwardingRuleForId(transaction.forwardingRuleId)

      rule.map(forwardingRule =>
        makeTransfer(forwardingRule, transaction))
    })
  }

  def maybeTransferImagesForBoxSource(imageId: Long, transaction: ForwardingTransaction, forwardingRule: ForwardingRule): Unit = {

    // get box information, are all images received?
    val inboxEntry = boxService.ask(GetInboxEntryForImageId(imageId)).mapTo[Option[InboxEntry]]

    inboxEntry.onComplete {
      case Success(entryMaybe) =>
        entryMaybe match {
          case Some(entry) =>
            if (entry.receivedImageCount == entry.totalImageCount)
              self ! MakeTransfer(forwardingRule, transaction)
          case None =>
            SbxLog.info("Forwarding", s"No inbox entries found for image id $imageId when transferring from box ${forwardingRule.source.sourceName}.")
            self ! RemoveTransactionAndMaybeDeleteImages(List(imageId))
        }
      case Failure(e) =>
        SbxLog.error("Forwarding", s"Could not get inbox entries for received image with id $imageId")
        self ! RemoveTransactionAndMaybeDeleteImages(List(imageId))
    }
  }

  def makeTransfer(rule: ForwardingRule, transaction: ForwardingTransaction) = {

    val imageIds = getTransactionImagesForTransactionId(transaction.id)
      .map(_.imageId)

    markTransactionAsProcessed(transaction, true)

    val destinationId = rule.destination.destinationId

    // send to destination and clear queue if successful
    rule.destination.destinationType match {
      case DestinationType.BOX =>
        boxService.ask(SendToRemoteBox(destinationId, imageIds.map(ImageTagValues(_, Seq.empty))))
          .onFailure {
            case e: Throwable => SbxLog.error("Forwarding", "Could not forward images to remote box " + rule.destination.destinationName + ": " + e.getMessage)
          }
      case DestinationType.SCU =>
        scuService.ask(SendImagesToScp(imageIds, destinationId))
          .onFailure {
            case e: Throwable =>
              SbxLog.warn("Forwarding", "Could not forward images to SCP. Trying again later. Message: " + e.getMessage)
              self ! MarkTransactionAsProcessed(transaction, false)
          }
      case _ =>
    }
  }

  def getExpiredButNotProcessedTransactions =
    db.withSession { implicit session =>
      val timeLimit = System.currentTimeMillis - pollInterval.toMillis
      forwardingDao.listExpiredButNotProcessedTransactions(timeLimit)
    }

  def getForwardingRuleForId(forwardingRuleId: Long): Option[ForwardingRule] =
    db.withSession { implicit session =>
      forwardingDao.getForwardingRuleForId(forwardingRuleId)
    }

  def getTransactionImagesForTransactionId(transactionId: Long): List[ForwardingTransactionImage] =
    db.withSession { implicit session =>
      forwardingDao.getTransactionImagesForTransactionId(transactionId)
    }

  def removeTransactionForId(transactionId: Long) =
    db.withSession { implicit session =>
      forwardingDao.removeTransactionForId(transactionId)
    }

  def markTransactionAsProcessed(transaction: ForwardingTransaction, processed: Boolean) =
    db.withSession { implicit session =>
      forwardingDao.updateForwardingTransaction(transaction.copy(processed = processed))
    }

  def getTransactionForImageId(imageId: Long): Option[ForwardingTransaction] =
    db.withSession { implicit session =>
      forwardingDao.getTransactionForImageId(imageId)
    }

  def removeTransactionAndMaybeDeleteImages(imageIds: Seq[Long]): Unit =
    if (!imageIds.isEmpty) {
      val imageId = imageIds(0) // pick any image, they are all in the same transaction
      val transaction = getTransactionForImageId(imageId)
      transaction.foreach(forwardingTransaction => {
        removeTransactionForId(forwardingTransaction.id)
        val rule = getForwardingRuleForId(forwardingTransaction.forwardingRuleId)
        rule.foreach(forwardingRule =>
          if (!forwardingRule.keepImages) {
            deleteImages(imageIds)
          })
      })
    } else
      SbxLog.warn("Forwarding", "Cannot remove transaction, list of forwarded images is empty.")

  def deleteImages(imageIds: Seq[Long]): Future[Seq[Long]] = {
    val futureDeletedImageIds = Future.sequence(imageIds.map(imageId =>
      storageService.ask(DeleteImage(imageId)).mapTo[ImageDeleted]))
      .map(_.map(_.imageId))

    futureDeletedImageIds.onFailure {
      case e: Throwable =>
        SbxLog.error("Forwarding", "Could not delete images after transfer: " + e.getMessage)
    }

    futureDeletedImageIds
  }

}

object ForwardingServiceActor {
  def props(dbProps: DbProps, timeout: Timeout): Props = Props(new ForwardingServiceActor(dbProps)(timeout))
}

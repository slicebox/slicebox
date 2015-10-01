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
    self ! PollForwardingQueue
  }
  system.scheduler.schedule(pollInterval + pollInterval / 2, pollInterval) {
    self ! FinalizeSentTransactions
  }

  log.info("Forwarding service started")

  def receive = LoggingReceive {

    case ImageAdded(image) =>
      maybeAddImageToForwardingQueue(image)

    case ImagesSent(destination, imageIds) =>
      markTransactionAsDelivered(destination, imageIds)

    case MaybeAddImageToForwardingQueue(image, sourceTypeAndId) =>
      maybeAddImageToForwardingQueue(image, sourceTypeAndId)

    case PollForwardingQueue =>
      maybeSendImagesForNonBoxSources

    case FinalizeSentTransactions =>
      finalizeSentTransactions

    case UpdateTransaction(transaction, enroute, delivered) =>
      updateTransaction(transaction, enroute, delivered)

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

    // look for rules with this source
    val rules = getForwardingRulesForSourceTypeAndId(sourceTypeAndId.sourceType, sourceTypeAndId.sourceId)

    // look for transactions, create or update (timestamp)
    val transactions = rules.map(createOrUpdateForwardingTransaction(_))

    // add to queue
    transactions.map(addImageToForwardingQueue(_, image))

    // check if source is box, if so, maybe transfer
    if (sourceTypeAndId.sourceType == SourceType.BOX)
      rules.zip(transactions).map {
        case (rule, transaction) =>
          if (!transaction.enroute)
            maybeSendImagesForBoxSource(image.id, transaction, rule)
      }
  }

  def hasForwardingRules(): Boolean =
    db.withSession { implicit session =>
      forwardingDao.getNumberOfForwardingRules > 0
    }

  def getSourceForSeries(seriesId: Long): Future[Option[SeriesSource]] =
    storageService.ask(GetSourceForSeries(seriesId)).mapTo[Option[SeriesSource]]

  def getForwardingRulesForSourceTypeAndId(sourceType: SourceType, sourceId: Long): List[ForwardingRule] =
    db.withSession { implicit session =>
      forwardingDao.getForwardingRulesForSourceTypeAndId(sourceType, sourceId)
    }

  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule): ForwardingTransaction =
    db.withSession { implicit session =>
      forwardingDao.createOrUpdateForwardingTransaction(forwardingRule)
    }

  def addImageToForwardingQueue(forwardingTransaction: ForwardingTransaction, image: Image) =
    db.withSession { implicit session =>
      forwardingDao.insertForwardingTransactionImage(ForwardingTransactionImage(-1, forwardingTransaction.id, image.id))
    }

  def maybeSendImagesForNonBoxSources(): Unit = {
    // look for transactions to carry out
    val transactions = getFreshExpiredTransactions

    // transfer each transaction
    transactions.map(transaction => {

      // get rule
      val rule = getForwardingRuleForId(transaction.forwardingRuleId)

      rule.map(forwardingRule =>
        if (forwardingRule.source.sourceType != SourceType.BOX)
          makeTransfer(forwardingRule, transaction))
    })
  }

  def maybeSendImagesForBoxSource(imageId: Long, transaction: ForwardingTransaction, forwardingRule: ForwardingRule): Unit = {

    // get box information, are all images received?
    val inboxEntry = boxService.ask(GetInboxEntryForImageId(imageId)).mapTo[Option[InboxEntry]]

    inboxEntry.onComplete {
      case Success(entryMaybe) =>
        entryMaybe match {
          case Some(entry) =>
            if (entry.receivedImageCount == entry.totalImageCount)
              self ! MakeTransfer(forwardingRule, transaction)
          case None =>
            SbxLog.error("Forwarding", s"No inbox entries found for image id $imageId when transferring from box ${forwardingRule.source.sourceName}. Cannot complete forwarding transfer.")
        }
      case Failure(e) =>
        SbxLog.error("Forwarding", s"Error getting inbox entries for received image with id $imageId. Could not complete forwarding transfer.")
    }
  }

  def makeTransfer(rule: ForwardingRule, transaction: ForwardingTransaction) = {

    val imageIds = getTransactionImagesForTransactionId(transaction.id)
      .map(_.imageId)

    updateTransaction(transaction, true, false)

    val destinationId = rule.destination.destinationId

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
              self ! UpdateTransaction(transaction, false, false)
          }
      case _ =>
    }
  }

  def getFreshExpiredTransactions() =
    db.withSession { implicit session =>
      val timeLimit = System.currentTimeMillis - pollInterval.toMillis
      forwardingDao.listFreshExpiredTransactions(timeLimit)
    }

  def getForwardingRuleForId(forwardingRuleId: Long): Option[ForwardingRule] =
    db.withSession { implicit session =>
      forwardingDao.getForwardingRuleForId(forwardingRuleId)
    }

  def getTransactionImagesForTransactionId(transactionId: Long): List[ForwardingTransactionImage] =
    db.withSession { implicit session =>
      forwardingDao.getTransactionImagesForTransactionId(transactionId)
    }

  def updateTransaction(transaction: ForwardingTransaction, enroute: Boolean, delivered: Boolean) =
    db.withSession { implicit session =>
      forwardingDao.updateForwardingTransaction(transaction.copy(enroute = enroute, delivered = delivered))
    }

  def getTransactionForDestinationAndImageId(destination: Destination, imageId: Long): Option[ForwardingTransaction] =
    db.withSession { implicit session =>
      forwardingDao.getTransactionForDestinationAndImageId(destination, imageId)
    }

  def markTransactionAsDelivered(destination: Destination, imageIds: Seq[Long]): Unit =
    if (!imageIds.isEmpty) {
      val imageId = imageIds(0) // pick any image, they are all in the same transaction
      getTransactionForDestinationAndImageId(destination, imageId)
        .map(transaction =>
          updateTransaction(transaction, false, true))
    }

  def finalizeSentTransactions(): Unit = {
    /*
     * This is tricky since we allow many rules with the same source, but different choices for keep images.
     * - It is safe to remove delivered transactions with keepImages=true
     * - If keepImages=false, wait until all transactions with the same source have been delivered,
     *   then delete.
     * - If there are multiple rules with the same source and differing choices for keepImages,
     *   keepImages=false wins, i.e. images will be deleted eventually.
     */
    var imageIdsToDelete = Set.empty[Long]
    getDeliveredTransactions.foreach(transaction => {
      val rule = getForwardingRuleForId(transaction.forwardingRuleId)
      rule.foreach(forwardingRule => {
        if (forwardingRule.keepImages)
          removeTransactionForId(transaction.id)
        else if (getUndeliveredTransactionsForSource(forwardingRule.source).isEmpty) {
          imageIdsToDelete = imageIdsToDelete ++ getTransactionImagesForTransactionId(transaction.id).map(_.imageId)
          removeTransactionForId(transaction.id)
        }
      })
    })
    deleteImages(imageIdsToDelete.toSeq)
  }

  def removeTransactionForId(transactionId: Long) =
    db.withSession { implicit session =>
      forwardingDao.removeTransactionForId(transactionId)
    }

  def getDeliveredTransactions(): List[ForwardingTransaction] =
    db.withSession { implicit session =>
      forwardingDao.getDeliveredTransactions
    }

  def getUndeliveredTransactionsForSource(source: Source): List[ForwardingTransaction] =
    db.withSession { implicit session =>
      forwardingDao.getUndeliveredTransactionsForSourceTypeAndId(source.sourceType, source.sourceId)
    }

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

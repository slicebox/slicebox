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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import ForwardingProtocol._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.storage.StorageProtocol._

class ForwardingServiceActor(dbProps: DbProps, pollInterval: FiniteDuration = 30.seconds)(implicit timeout: Timeout) extends Actor {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val db = dbProps.db
  val forwardingDao = new ForwardingDAO(dbProps.driver)

  val storageService = context.actorSelection("../StorageService")
  val boxService = context.actorSelection("../BoxService")
  val scuService = context.actorSelection("../ScuService")

  val nonBoxBatchId = 1L

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageAdded])
    context.system.eventStream.subscribe(context.self, classOf[ImageDeleted])
    context.system.eventStream.subscribe(context.self, classOf[ImagesSent])
  }

  system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollForwardingQueue
  }
  system.scheduler.schedule(pollInterval + pollInterval / 2, pollInterval) {
    self ! FinalizeSentTransactions
  }

  log.info("Forwarding service started")

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

    case ImageAdded(image, source) =>
      val applicableRules = maybeAddImageToForwardingQueue(image, source, sender)
      sender ! ImageRegisteredForForwarding(image, applicableRules)
      
    case ImageDeleted(imageId) =>
      removeImageFromTransactions(imageId)
      
    case AddImageToForwardingQueue(image, rule, batchId, transferNow, origin) =>
      val (transaction, transactionImage) = addImageToForwardingQueue(image, rule, batchId)
      origin ! ImageAddedToForwardingQueue(transactionImage)
      if (transferNow)
    	  makeTransfer(rule, transaction)
    	  
    case PollForwardingQueue =>
      val transactions = maybeSendImagesForNonBoxSources()
      sender ! TransactionsEnroute(transactions)

    case UpdateTransaction(transaction, enroute, delivered) =>
      updateTransaction(transaction, enroute, delivered)

    case ImagesSent(destination, imageIds) =>
      val transactionMaybe = markTransactionAsDelivered(destination, imageIds)
      sender ! TransactionMarkedAsDelivered(transactionMaybe)

    case FinalizeSentTransactions =>
      val (transactionsToRemove, idsOfDeletedImages) = finalizeSentTransactions
      sender ! TransactionsFinalized(transactionsToRemove, idsOfDeletedImages)

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

  def maybeAddImageToForwardingQueue(image: Image, source: Source, origin: ActorRef): List[ForwardingRule] =
    if (hasForwardingRules) {

      // look for rules with this source
      val rules = getForwardingRulesForSourceTypeAndId(source.sourceType, source.sourceId)

      // check if source is box, if so, maybe transfer
      if (source.sourceType == SourceType.BOX)
        rules.map(rule => addImageToForwardingQueueForBoxSource(image, rule, origin))
      else
        rules.map(rule => self ! AddImageToForwardingQueue(image, rule, nonBoxBatchId, false, origin))

      rules
    } else
      List.empty

  def addImageToForwardingQueue(image: Image, rule: ForwardingRule, batchId: Long): (ForwardingTransaction, ForwardingTransactionImage) = {
    // look for transactions, create or update (timestamp)
    val transaction = createOrUpdateForwardingTransaction(rule, batchId)

    // add to queue
    val addedImage = addImageToForwardingQueue(transaction, image)

    (transaction, addedImage)
  }

  def hasForwardingRules(): Boolean =
    db.withSession { implicit session =>
      forwardingDao.getNumberOfForwardingRules > 0
    }

  def getForwardingRulesForSourceTypeAndId(sourceType: SourceType, sourceId: Long): List[ForwardingRule] =
    db.withSession { implicit session =>
      forwardingDao.getForwardingRulesForSourceTypeAndId(sourceType, sourceId)
    }

  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule, batchId: Long): ForwardingTransaction =
    db.withSession { implicit session =>
      forwardingDao.createOrUpdateForwardingTransaction(forwardingRule, batchId)
    }

  def addImageToForwardingQueue(forwardingTransaction: ForwardingTransaction, image: Image) =
    db.withSession { implicit session =>
      forwardingDao.insertForwardingTransactionImage(ForwardingTransactionImage(-1, forwardingTransaction.id, image.id))
    }

  def maybeSendImagesForNonBoxSources(): List[ForwardingTransaction] = {
    val rulesAndTransactions = transactionsToRulesAndTransactions(getFreshExpiredTransactions())
      .filter(_._1.source.sourceType != SourceType.BOX)

    rulesAndTransactions.foreach {
      case (rule, transaction) => makeTransfer(rule, transaction)
    }

    rulesAndTransactions.map(_._2)
  }

  def addImageToForwardingQueueForBoxSource(image: Image, forwardingRule: ForwardingRule, origin: ActorRef): Unit = {

    /*
     * This method is called as the result of the ImageAdded event. The same event updates the inbox entry
     * and the order in which this happens is indeterminate. Therefore, we try getting the inbox entry for
     * the added image a few times before either succeeding or giving up.
     */

    def inboxEntryForImage(image: Image, attempt: Int, maxAttempts: Int, attemptInterval: Long): Unit =
      boxService.ask(GetIncomingEntryForImageId(image.id)).mapTo[Option[IncomingEntry]].onComplete {
        case Success(entryMaybe) => entryMaybe match {
          case Some(entry) =>
            self ! AddImageToForwardingQueue(image, forwardingRule, entry.id, entry.receivedImageCount >= entry.totalImageCount, origin)
          case None =>
            if (attempt < maxAttempts) {
              Thread.sleep(attemptInterval)
              inboxEntryForImage(image, attempt + 1, maxAttempts, attemptInterval)
            } else
              SbxLog.error("Forwarding", s"No inbox entries found afer $attempt attempts for image id ${image.id} when transferring from box ${forwardingRule.source.sourceName}. Cannot complete forwarding transfer.")
        }
        case Failure(e) =>
          SbxLog.error("Forwarding", s"Error getting inbox entries for received image with id ${image.id}. Could not complete forwarding transfer.")
      }

    // get box information, are all images received?
    inboxEntryForImage(image, 1, 10, 500)

  }

  def makeTransfer(rule: ForwardingRule, transaction: ForwardingTransaction) = {

    val imageIds = getTransactionImagesForTransactionId(transaction.id)
      .map(_.imageId)

    SbxLog.info("Forwarding", s"Forwarding ${imageIds.length} images from ${rule.source.sourceType.toString} ${rule.source.sourceName} to ${rule.destination.destinationType.toString} ${rule.destination.destinationName}.")

    updateTransaction(transaction, true, false)

    val destinationId = rule.destination.destinationId
    val destinationName = rule.destination.destinationName
    val box = Box(destinationId, destinationName, "", "", null, false)
    
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

  def markTransactionAsDelivered(destination: Destination, imageIds: Seq[Long]) =
    imageIds.headOption.flatMap(imageId => {
      val transactionMaybe = getTransactionForDestinationAndImageId(destination, imageId)
      transactionMaybe.map(transaction => updateTransaction(transaction, false, true))
      transactionMaybe
    })

  def finalizeSentTransactions() = {
    /*
     * This is tricky since we allow many rules with the same source, but different choices for keep images.
     * - It is safe to remove delivered transactions with keepImages=true
     * - If keepImages=false, wait until all transactions with the same source have been delivered,
     *   then delete.
     * - If there are multiple rules with the same source and differing choices for keepImages,
     *   keepImages=false wins, i.e. images will be deleted eventually.
     */
    val transactionsAndRules = transactionsToRulesAndTransactions(getDeliveredTransactions())
    val toRemoveButNotDeleteImages = transactionsAndRules.filter(_._1.keepImages).map(_._2)
    val toRemoveAndDeleteImages = transactionsAndRules.filter {
      case (rule, transaction) =>
        !rule.keepImages && getUndeliveredTransactionsForSource(rule.source).isEmpty
    }.map(_._2)
    val transactionsToRemove = toRemoveButNotDeleteImages ++ toRemoveAndDeleteImages
    val imageIdsToDelete = toRemoveAndDeleteImages.map(transaction =>
      getTransactionImagesForTransactionId(transaction.id).map(_.imageId))
      .flatten.distinct
    transactionsToRemove.foreach(transaction => removeTransactionForId(transaction.id))
    deleteImages(imageIdsToDelete)

    if (!transactionsToRemove.isEmpty) {
      SbxLog.info("Forwarding", s"Finalized ${transactionsToRemove.length} transactions.")
      if (!imageIdsToDelete.isEmpty)
        SbxLog.info("Forwarding", s"Deleted ${imageIdsToDelete.length} images after forwarding.")
    }

    (transactionsToRemove, imageIdsToDelete)
  }

  def transactionsToRulesAndTransactions(transactions: List[ForwardingTransaction]) =
    transactions
      .map(transaction => (getForwardingRuleForId(transaction.forwardingRuleId), transaction))
      .filter(_._1.isDefined)
      .map {
        case (ruleMaybe, transaction) => (ruleMaybe.get, transaction)
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

  def deleteImages(imageIds: List[Long]): Future[Seq[Long]] = {
    val futureDeletedImageIds = Future.sequence(imageIds.map(imageId =>
      storageService.ask(DeleteDataset(imageId)).mapTo[DatasetDeleted]))
      .map(_.map(_.imageId))

    futureDeletedImageIds.onFailure {
      case e: Throwable =>
        SbxLog.error("Forwarding", "Could not delete images after transfer: " + e.getMessage)
    }

    futureDeletedImageIds
  }

  def removeImageFromTransactions(imageId: Long) = 
    db.withSession { implicit session =>
      forwardingDao.removeTransactionImagesForImageId(imageId)
    }
}

object ForwardingServiceActor {
  def props(dbProps: DbProps, timeout: Timeout): Props = Props(new ForwardingServiceActor(dbProps)(timeout))
}

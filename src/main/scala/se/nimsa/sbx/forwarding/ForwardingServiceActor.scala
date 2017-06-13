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

import akka.actor.{Actor, ActorRef, Props}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.util.FutureUtil.await

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ForwardingServiceActor(forwardingDao: ForwardingDAO, pollInterval: FiniteDuration = 30.seconds)(implicit timeout: Timeout) extends Actor {

  import scala.collection.mutable

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val transactionIdToForwardingActor = mutable.Map.empty[Long, ActorRef]

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
    * Message flow:
    * ImageAdded (ImageRegisteredForForwarding to sender)
    * PollForwardingQueue (until transaction update period expires) (TransactionsEnroute to sender (self))
    * (transfer is made)
    * ImagesSent (TransactionMarkedAsDelivered to sender (box, scu))
    * FinalizeSentTransactions (TransactionsFinalized to sender (self))
    */
  def receive = LoggingReceive {

    // EVENTS

    case ImageAdded(image, source, _) =>
      val applicableRules = maybeAddImageToForwardingQueue(image, source, sender)
      sender ! ImageRegisteredForForwarding(image, applicableRules)

    case ImageDeleted(imageId) =>
      removeImageFromTransactions(imageId)

    case ImagesSent(destination, imageIds) =>
      val transactionMaybe = markTransactionAsDelivered(destination, imageIds)
      sender ! TransactionMarkedAsDelivered(transactionMaybe)


    // INTERNAL

    case PollForwardingQueue =>
      val transactions = maybeSendImages()
      sender ! TransactionsEnroute(transactions)

    case FinalizeSentTransactions =>
      val removedTransactions = finalizeSentTransactions()
      sender ! TransactionsFinalized(removedTransactions)

    case UpdateTransaction(transaction) =>
      updateTransaction(transaction)

    // EXTERNAL

    case msg: ForwardingRequest =>

      msg match {
        case GetForwardingRules(startIndex, count) =>
          val forwardingRules = getForwardingRulesFromDb(startIndex, count)
          sender ! ForwardingRules(forwardingRules)

        case AddForwardingRule(forwardingRule) =>
          val dbForwardingRule = addForwardingRuleToDb(forwardingRule)
          sender ! ForwardingRuleAdded(dbForwardingRule)

        case RemoveForwardingRule(forwardingRuleId) =>
          removeForwardingRuleFromDb(forwardingRuleId)
          sender ! ForwardingRuleRemoved(forwardingRuleId)

      }
  }

  def markTransactionAsDelivered(destination: Destination, imageIds: Seq[Long]): Option[ForwardingTransaction] =
    imageIds.headOption.flatMap(imageId => {
      val transactionMaybe = getTransactionForDestinationAndImageId(destination, imageId)
      transactionMaybe.foreach(transaction => updateTransaction(transaction.copy(enroute = false, delivered = true)))
      transactionMaybe
    })

  def maybeAddImageToForwardingQueue(image: Image, source: Source, origin: ActorRef): Seq[ForwardingRule] = {
    // look for rules with this source
    val rules = getForwardingRulesForSourceTypeAndId(source.sourceType, source.sourceId)

    rules.foreach { rule =>
      // look for transactions, create or update (timestamp)
      val transaction = createOrUpdateForwardingTransaction(rule)

      // add to queue
      addImageToForwardingQueue(transaction, image)
    }

    rules
  }

  def maybeSendImages(): Seq[ForwardingTransaction] = {
    val rulesAndTransactions = transactionsToRulesAndTransactions(getFreshExpiredTransactions)

    rulesAndTransactions.foreach {
      case (rule, transaction) => sendImages(rule, transaction)
    }

    rulesAndTransactions.map(_._2)
  }

  def sendImages(rule: ForwardingRule, transaction: ForwardingTransaction): Unit = {

    val updatedTransaction = updateTransaction(transaction.copy(enroute = true, delivered = false))
    val images = getTransactionImagesForTransactionId(updatedTransaction.id)

    val forwardingActor = context.actorOf(ForwardingActor.props(rule, updatedTransaction, images, timeout))
    transactionIdToForwardingActor(updatedTransaction.id) = forwardingActor
  }

  def finalizeSentTransactions(): Seq[ForwardingTransaction] = {
    /*
     * This is tricky since we allow many rules with the same source, but different choices for keep images.
     * - It is safe to remove delivered transactions with keepImages=true
     * - If keepImages=false, wait until all transactions with the same source have been delivered,
     *   then delete.
     * - If there are multiple rules with the same source and differing choices for keepImages,
     *   keepImages=false wins, i.e. images will be deleted eventually.
     */
    val transactionsAndRules = transactionsToRulesAndTransactions(getDeliveredTransactions)

    val toRemoveButNotDeleteTransactions = transactionsAndRules.filter {
      case (rule, _) =>
        rule.keepImages
    }.map(_._2)

    val toRemoveAndDeleteTransactions = transactionsAndRules.filter {
      case (rule, _) =>
        !rule.keepImages && getUndeliveredTransactionsForSource(rule.source).isEmpty
    }.map(_._2)

    val transactionsToRemove = toRemoveButNotDeleteTransactions ++ toRemoveAndDeleteTransactions

    toRemoveButNotDeleteTransactions.foreach { transaction =>
      transactionIdToForwardingActor.get(transaction.id).foreach { forwardingActor =>
        forwardingActor ! FinalizeForwarding(deleteImages = false)
      }
    }

    toRemoveAndDeleteTransactions.foreach { transaction =>
      transactionIdToForwardingActor.get(transaction.id).foreach { forwardingActor =>
        forwardingActor ! FinalizeForwarding(deleteImages = true)
      }
    }

    transactionsToRemove.foreach { transaction =>
      removeTransactionForId(transaction.id)
      transactionIdToForwardingActor.remove(transaction.id)
    }

    if (transactionsToRemove.nonEmpty) {
      SbxLog.info("Forwarding", s"Finalized ${transactionsToRemove.length} transactions.")
    }

    transactionsToRemove
  }

  def transactionsToRulesAndTransactions(transactions: Seq[ForwardingTransaction]) =
    transactions
      .map(transaction => (getForwardingRuleForId(transaction.forwardingRuleId), transaction))
      .filter(_._1.isDefined)
      .map {
        case (ruleMaybe, transaction) => (ruleMaybe.get, transaction)
      }


  // Database function

  def getForwardingRulesFromDb(startIndex: Long, count: Long) =
    await(forwardingDao.listForwardingRules(startIndex, count))

  def addForwardingRuleToDb(forwardingRule: ForwardingRule): ForwardingRule =
    await(forwardingDao.getForwardingRuleForSourceIdAndTypeAndDestinationIdAndType(
      forwardingRule.source.sourceId,
      forwardingRule.source.sourceType,
      forwardingRule.destination.destinationId,
      forwardingRule.destination.destinationType))
      .getOrElse(await(forwardingDao.insertForwardingRule(forwardingRule)))

  def removeForwardingRuleFromDb(forwardingRuleId: Long): Unit =
    await(forwardingDao.removeForwardingRule(forwardingRuleId))

  def hasForwardingRules: Boolean =
    await(forwardingDao.getNumberOfForwardingRules) > 0

  def getForwardingRulesForSourceTypeAndId(sourceType: SourceType, sourceId: Long): Seq[ForwardingRule] =
    await(forwardingDao.getForwardingRulesForSourceTypeAndId(sourceType, sourceId))

  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule): ForwardingTransaction =
    await(forwardingDao.createOrUpdateForwardingTransaction(forwardingRule))

  def addImageToForwardingQueue(forwardingTransaction: ForwardingTransaction, image: Image) =
    // dicom data may be added multiple times (with overwrite), check if it has been added before
    await(forwardingDao.addImageToForwardingQueue(forwardingTransaction.id, image.id))

  def getFreshExpiredTransactions: Seq[ForwardingTransaction] = {
    val timeLimit = System.currentTimeMillis - pollInterval.toMillis
    await(forwardingDao.listFreshExpiredTransactions(timeLimit))
  }

  def getForwardingRuleForId(forwardingRuleId: Long): Option[ForwardingRule] =
    await(forwardingDao.getForwardingRuleForId(forwardingRuleId))

  def getTransactionImagesForTransactionId(transactionId: Long): Seq[ForwardingTransactionImage] =
    await(forwardingDao.getTransactionImagesForTransactionId(transactionId))

  def getTransactionForDestinationAndImageId(destination: Destination, imageId: Long): Option[ForwardingTransaction] =
    await(forwardingDao.getTransactionForDestinationAndImageId(destination, imageId))

  def updateTransaction(transaction: ForwardingTransaction): ForwardingTransaction = {
    await(forwardingDao.updateForwardingTransaction(transaction))
    transaction
  }

  def removeTransactionForId(transactionId: Long): Unit =
    await(forwardingDao.removeTransactionForId(transactionId))

  def getDeliveredTransactions: Seq[ForwardingTransaction] =
    await(forwardingDao.getDeliveredTransactions)

  def getUndeliveredTransactionsForSource(source: Source): Seq[ForwardingTransaction] =
    await(forwardingDao.getUndeliveredTransactionsForSourceTypeAndId(source.sourceType, source.sourceId))

  def removeImageFromTransactions(imageId: Long): Unit =
    await(forwardingDao.removeTransactionImagesForImageId(imageId))

}

object ForwardingServiceActor {
  def props(forwardingDao: ForwardingDAO, timeout: Timeout): Props = Props(new ForwardingServiceActor(forwardingDao)(timeout))
}

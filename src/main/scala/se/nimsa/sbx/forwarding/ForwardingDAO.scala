/*
 * Copyright 2017 Lars Edenbrandt
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

import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class ForwardingDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  private val toForwardingRule = (id: Long, sourceType: String, sourceName: String, sourceId: Long, destinationType: String, destinationName: String, destinationId: Long, keepImages: Boolean) => ForwardingRule(id, Source(SourceType.withName(sourceType), sourceName, sourceId), Destination(DestinationType.withName(destinationType), destinationName, destinationId), keepImages)
  private val fromForwardingRule = (rule: ForwardingRule) => Option((rule.id, rule.source.sourceType.toString(), rule.source.sourceName, rule.source.sourceId, rule.destination.destinationType.toString(), rule.destination.destinationName, rule.destination.destinationId, rule.keepImages))

  class ForwardingRuleTable(tag: Tag) extends Table[ForwardingRule](tag, ForwardingRuleTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sourceType = column[String]("sourcetype")
    def sourceName = column[String]("sourcename")
    def sourceId = column[Long]("sourceid")
    def destinationType = column[String]("destinationtype")
    def destinationName = column[String]("destinationname")
    def destinationId = column[Long]("destinationid")
    def keepImages = column[Boolean]("keepimages")
    def idxUnique = index("idx_unique_forwarding_rule", (sourceType, sourceId, destinationType, destinationId), unique = true)
    def * = (id, sourceType, sourceName, sourceId, destinationType, destinationName, destinationId, keepImages) <> (toForwardingRule.tupled, fromForwardingRule)
  }

  object ForwardingRuleTable {
    val name = "ForwardingRules"
  }

  val ruleQuery = TableQuery[ForwardingRuleTable]

  class ForwardingTransactionTable(tag: Tag) extends Table[ForwardingTransaction](tag, ForwardingTransactionTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def forwardingRuleId = column[Long]("forwardingruleid")
    def created = column[Long]("created")
    def updated = column[Long]("updated")
    def enroute = column[Boolean]("enroute")
    def delivered = column[Boolean]("delivered")
    def fkForwardingRule = foreignKey("fk_forwarding_rule", forwardingRuleId, ruleQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, forwardingRuleId, created, updated, enroute, delivered) <> (ForwardingTransaction.tupled, ForwardingTransaction.unapply)
  }

  object ForwardingTransactionTable {
    val name = "ForwardingTransactions"
  }

  val transactionQuery = TableQuery[ForwardingTransactionTable]

  class ForwardingTransactionImageTable(tag: Tag) extends Table[ForwardingTransactionImage](tag, ForwardingTransactionImageTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def forwardingTransactionId = column[Long]("forwardingtransactionid")
    def imageId = column[Long]("imageid")
    def fkForwardingTransaction = foreignKey("fk_forwarding_transaction", forwardingTransactionId, transactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, forwardingTransactionId, imageId) <> (ForwardingTransactionImage.tupled, ForwardingTransactionImage.unapply)
  }

  object ForwardingTransactionImageTable {
    val name = "ForwardingTransactionImages"
  }

  val transactionImageQuery = TableQuery[ForwardingTransactionImageTable]

  def create() = createTables(dbConf, (ForwardingRuleTable.name, ruleQuery), (ForwardingTransactionTable.name, transactionQuery), (ForwardingTransactionImageTable.name, transactionImageQuery))

  def drop() = db.run {
    (ruleQuery.schema ++ transactionQuery.schema ++ transactionImageQuery.schema).drop
  }

  def clear() = db.run {
    DBIO.seq(ruleQuery.delete, transactionQuery.delete, transactionImageQuery.delete)
  }

  def getNumberOfForwardingRules: Future[Int] = db.run(ruleQuery.length.result)

  def insertForwardingRule(forwardingRule: ForwardingRule): Future[ForwardingRule] = db.run {
    (ruleQuery returning ruleQuery.map(_.id) += forwardingRule)
      .map(generatedId => forwardingRule.copy(id = generatedId))
  }

  def listForwardingRules(startIndex: Long, count: Long): Future[Seq[ForwardingRule]] = db.run {
    ruleQuery
      .drop(startIndex)
      .take(count)
      .result
  }

  def listForwardingTransactions: Future[Seq[ForwardingTransaction]] = db.run {
    transactionQuery.result
  }

  def listForwardingTransactionImages: Future[Seq[ForwardingTransactionImage]] = db.run {
    transactionImageQuery.result
  }

  def removeForwardingRule(forwardingRuleId: Long): Future[Unit] = db.run {
    ruleQuery.filter(_.id === forwardingRuleId).delete.map(_ => {})
  }

  def getForwardingRulesForSourceTypeAndIdAction(sourceType: SourceType, sourceId: Long) =
    ruleQuery
      .filter(_.sourceType === sourceType.toString)
      .filter(_.sourceId === sourceId)
      .result

  def getForwardingRulesForSourceTypeAndId(sourceType: SourceType, sourceId: Long): Future[Seq[ForwardingRule]] =
    db.run(getForwardingRulesForSourceTypeAndIdAction(sourceType, sourceId))

  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule): Future[ForwardingTransaction] = db.run {
    getFreshTransactionForRuleAction(forwardingRule).flatMap {
      _.map { transaction =>
        val updatedTransaction = transaction.copy(updated = System.currentTimeMillis())
        updateForwardingTransactionAction(updatedTransaction).map(_ => updatedTransaction)
      }.getOrElse {
        insertForwardingTransactionAction(ForwardingTransaction(-1, forwardingRule.id, System.currentTimeMillis, System.currentTimeMillis, enroute = false, delivered = false))
      }
    }
  }

  def getFreshTransactionForRuleAction(forwardingRule: ForwardingRule) =
    transactionQuery
      .filter(_.forwardingRuleId === forwardingRule.id)
      .filter(_.enroute === false)
      .filter(_.delivered === false)
      .result
      .headOption

  def getFreshTransactionForRule(forwardingRule: ForwardingRule): Future[Option[ForwardingTransaction]] =
    db.run(getFreshTransactionForRuleAction(forwardingRule))

  def updateForwardingTransactionAction(forwardingTransaction: ForwardingTransaction) =
    transactionQuery.filter(_.id === forwardingTransaction.id).update(forwardingTransaction)

  def updateForwardingTransaction(forwardingTransaction: ForwardingTransaction): Future[Int] =
    db.run(updateForwardingTransactionAction(forwardingTransaction))

  def insertForwardingTransactionAction(forwardingTransaction: ForwardingTransaction) =
    (transactionQuery returning transactionQuery.map(_.id) += forwardingTransaction)
      .map(generatedId => forwardingTransaction.copy(id = generatedId))

  def insertForwardingTransaction(forwardingTransaction: ForwardingTransaction): Future[ForwardingTransaction] =
    db.run(insertForwardingTransactionAction(forwardingTransaction))

  def insertForwardingTransactionImageAction(forwardingTransactionImage: ForwardingTransactionImage) =
    (transactionImageQuery returning transactionImageQuery.map(_.id) += forwardingTransactionImage)
      .map(generatedId => forwardingTransactionImage.copy(id = generatedId))

  def insertForwardingTransactionImage(forwardingTransactionImage: ForwardingTransactionImage): Future[ForwardingTransactionImage] =
    db.run(insertForwardingTransactionImageAction(forwardingTransactionImage))

  def listFreshExpiredTransactions(timeLimit: Long): Future[Seq[ForwardingTransaction]] = db.run {
    transactionQuery
      .filter(_.updated < timeLimit)
      .filter(_.enroute === false)
      .filter(_.delivered === false)
      .result
  }

  def getForwardingRuleForId(forwardingRuleId: Long): Future[Option[ForwardingRule]] = db.run {
    ruleQuery.filter(_.id === forwardingRuleId).result.headOption
  }

  def getForwardingRuleForSourceIdAndTypeAndDestinationIdAndType(sourceId: Long,
                                                                 sourceType: SourceType,
                                                                 destinationId: Long,
                                                                 destinationType: DestinationType): Future[Option[ForwardingRule]] =
    db.run {
      ruleQuery
        .filter(_.sourceId === sourceId)
        .filter(_.sourceType === sourceType.toString())
        .filter(_.destinationId === destinationId)
        .filter(_.destinationType === destinationType.toString())
        .result
        .headOption
    }

  def getTransactionImagesForTransactionId(transactionId: Long): Future[Seq[ForwardingTransactionImage]] = db.run {
    transactionImageQuery.filter(_.forwardingTransactionId === transactionId).result
  }

  def getTransactionImageForTransactionIdAndImageIdAction(transactionId: Long, imageId: Long) =
    transactionImageQuery
      .filter(_.forwardingTransactionId === transactionId)
      .filter(_.imageId === imageId)
      .result
      .headOption

  def getTransactionImageForTransactionIdAndImageId(transactionId: Long, imageId: Long): Future[Option[ForwardingTransactionImage]] =
    db.run(getTransactionImageForTransactionIdAndImageIdAction(transactionId, imageId))

  def removeTransactionForId(transactionId: Long): Future[Unit] = db.run {
    transactionQuery.filter(_.id === transactionId).delete.map(_ => {})
  }

  def getTransactionForDestinationAndImageId(destination: Destination, imageId: Long): Future[Option[ForwardingTransaction]] = db.run {
    val join = for {
      ruleEntry <- ruleQuery
      transactionEntry <- transactionQuery if ruleEntry.id === transactionEntry.forwardingRuleId
      imageEntry <- transactionImageQuery if transactionEntry.id === imageEntry.forwardingTransactionId
    } yield (ruleEntry, transactionEntry, imageEntry)
    join
      .filter(_._3.imageId === imageId)
      .filter(_._1.destinationType === destination.destinationType.toString)
      .filter(_._1.destinationId === destination.destinationId)
      .map(_._2)
      .result
      .headOption
  }

  def getDeliveredTransactions: Future[Seq[ForwardingTransaction]] = db.run {
    transactionQuery.filter(_.delivered === true).result
  }

  def getUndeliveredTransactionsForSourceTypeAndId(sourceType: SourceType, sourceId: Long): Future[Seq[ForwardingTransaction]] =
    db.run {
      val join = for {
        ruleEntry <- ruleQuery
        transactionEntry <- transactionQuery if ruleEntry.id === transactionEntry.forwardingRuleId
      } yield (ruleEntry, transactionEntry)
      join
        .filter(_._1.sourceType === sourceType.toString)
        .filter(_._1.sourceId === sourceId)
        .filter(_._2.delivered === false)
        .map(_._2)
        .result
    }

  def removeTransactionImagesForImageId(imageId: Long): Future[Unit] = db.run {
    transactionImageQuery.filter(_.imageId === imageId).delete.map(_ => {})
  }

  def addImageToForwardingQueue(transactionId: Long, imageId: Long): Future[ForwardingTransactionImage] = {
    val action = getTransactionImageForTransactionIdAndImageIdAction(transactionId, imageId).flatMap { maybeImage =>
      maybeImage
        .map(DBIO.successful)
        .getOrElse(
          insertForwardingTransactionImageAction(ForwardingTransactionImage(-1, transactionId, imageId)))
    }

    db.run(action.transactionally)
  }
}

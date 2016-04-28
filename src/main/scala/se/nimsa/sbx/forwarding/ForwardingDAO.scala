/*
 * Copyright 2016 Lars Edenbrandt
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
import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import se.nimsa.sbx.app.GeneralProtocol._

class ForwardingDAO(val driver: JdbcProfile) {

  import driver.simple._

  private val toForwardingRule = (id: Long, sourceType: String, sourceName: String, sourceId: Long, destinationType: String, destinationName: String, destinationId: Long, keepImages: Boolean) => ForwardingRule(id, Source(SourceType.withName(sourceType), sourceName, sourceId), Destination(DestinationType.withName(destinationType), destinationName, destinationId), keepImages)

  private val fromForwardingRule = (rule: ForwardingRule) => Option((rule.id, rule.source.sourceType.toString(), rule.source.sourceName, rule.source.sourceId, rule.destination.destinationType.toString(), rule.destination.destinationName, rule.destination.destinationId, rule.keepImages))

  class ForwardingRuleTable(tag: Tag) extends Table[ForwardingRule](tag, "ForwardingRules") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def sourceType = column[String]("sourcetype")

    def sourceName = column[String]("sourcename")

    def sourceId = column[Long]("sourceid")

    def destinationType = column[String]("destinationtype")

    def destinationName = column[String]("destinationname")

    def destinationId = column[Long]("destinationid")

    def keepImages = column[Boolean]("keepimages")

    def idxUnique = index("idx_unique_forwarding_rule", (sourceType, sourceId, destinationType, destinationId), unique = true)

    def * = (id, sourceType, sourceName, sourceId, destinationType, destinationName, destinationId, keepImages) <>(toForwardingRule.tupled, fromForwardingRule)
  }

  val ruleQuery = TableQuery[ForwardingRuleTable]

  class ForwardingTransactionTable(tag: Tag) extends Table[ForwardingTransaction](tag, "ForwardingTransactions") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def forwardingRuleId = column[Long]("forwardingruleid")

    def created = column[Long]("created")

    def updated = column[Long]("updated")

    def enroute = column[Boolean]("enroute")

    def delivered = column[Boolean]("delivered")

    def fkForwardingRule = foreignKey("fk_forwarding_rule", forwardingRuleId, ruleQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (id, forwardingRuleId, created, updated, enroute, delivered) <>(ForwardingTransaction.tupled, ForwardingTransaction.unapply)
  }

  val transactionQuery = TableQuery[ForwardingTransactionTable]

  class ForwardingTransactionImageTable(tag: Tag) extends Table[ForwardingTransactionImage](tag, "ForwardingTransactionImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def forwardingTransactionId = column[Long]("forwardingtransactionid")

    def imageId = column[Long]("imageid")

    def fkForwardingTransaction = foreignKey("fk_forwarding_transaction", forwardingTransactionId, transactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (id, forwardingTransactionId, imageId) <>(ForwardingTransactionImage.tupled, ForwardingTransactionImage.unapply)
  }

  val transactionImageQuery = TableQuery[ForwardingTransactionImageTable]

  def create(implicit session: Session): Unit = {
    if (MTable.getTables("ForwardingRules").list.isEmpty) ruleQuery.ddl.create
    if (MTable.getTables("ForwardingTransactions").list.isEmpty) transactionQuery.ddl.create
    if (MTable.getTables("ForwardingTransactionImages").list.isEmpty) transactionImageQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (ruleQuery.ddl ++ transactionQuery.ddl ++ transactionImageQuery.ddl).drop

  def clear(implicit session: Session) = {
    ruleQuery.delete
    transactionQuery.delete
    transactionImageQuery.delete
  }

  def getNumberOfForwardingRules(implicit session: Session): Int = ruleQuery.length.run

  def insertForwardingRule(forwardingRule: ForwardingRule)(implicit session: Session): ForwardingRule = {
    val generatedId = (ruleQuery returning ruleQuery.map(_.id)) += forwardingRule
    forwardingRule.copy(id = generatedId)
  }

  def listForwardingRules(startIndex: Long, count: Long)(implicit session: Session): List[ForwardingRule] =
    ruleQuery
      .drop(startIndex)
      .take(count)
      .list

  def listForwardingTransactions(implicit session: Session): List[ForwardingTransaction] =
    transactionQuery.list

  def listForwardingTransactionImages(implicit session: Session): List[ForwardingTransactionImage] =
    transactionImageQuery.list

  def removeForwardingRule(forwardingRuleId: Long)(implicit session: Session): Unit =
    ruleQuery.filter(_.id === forwardingRuleId).delete

  def getForwardingRulesForSourceTypeAndId(sourceType: SourceType, sourceId: Long)(implicit session: Session) =
    ruleQuery
      .filter(_.sourceType === sourceType.toString)
      .filter(_.sourceId === sourceId)
      .list

  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule)(implicit session: Session): ForwardingTransaction =
    getFreshTransactionForRule(forwardingRule) match {
      case Some(transaction) =>
        val updatedTransaction = transaction.copy(updated = System.currentTimeMillis())
        updateForwardingTransaction(updatedTransaction)
        updatedTransaction
      case None =>
        insertForwardingTransaction(ForwardingTransaction(-1, forwardingRule.id, System.currentTimeMillis, System.currentTimeMillis, enroute = false, delivered = false))
    }

  def getFreshTransactionForRule(forwardingRule: ForwardingRule)(implicit session: Session): Option[ForwardingTransaction] =
    transactionQuery
      .filter(_.forwardingRuleId === forwardingRule.id)
      .filter(_.enroute === false)
      .filter(_.delivered === false)
      .firstOption

  def updateForwardingTransaction(forwardingTransaction: ForwardingTransaction)(implicit session: Session) =
    transactionQuery.filter(_.id === forwardingTransaction.id).update(forwardingTransaction)

  def insertForwardingTransaction(forwardingTransaction: ForwardingTransaction)(implicit session: Session): ForwardingTransaction = {
    val generatedId = (transactionQuery returning transactionQuery.map(_.id)) += forwardingTransaction
    forwardingTransaction.copy(id = generatedId)
  }

  def insertForwardingTransactionImage(forwardingTransactionImage: ForwardingTransactionImage)(implicit session: Session) = {
    val generatedId = (transactionImageQuery returning transactionImageQuery.map(_.id)) += forwardingTransactionImage
    forwardingTransactionImage.copy(id = generatedId)
  }

  def listFreshExpiredTransactions(timeLimit: Long)(implicit session: Session) =
    transactionQuery
      .filter(_.updated < timeLimit)
      .filter(_.enroute === false)
      .filter(_.delivered === false)
      .list

  def getForwardingRuleForId(forwardingRuleId: Long)(implicit session: Session) =
    ruleQuery.filter(_.id === forwardingRuleId).firstOption

  def getTransactionImagesForTransactionId(transactionId: Long)(implicit session: Session) =
    transactionImageQuery.filter(_.forwardingTransactionId === transactionId).list

  def getTransactionImageForTransactionIdAndImageId(transactionId: Long, imageId: Long)(implicit session: Session) =
    transactionImageQuery
      .filter(_.forwardingTransactionId === transactionId)
      .filter(_.imageId === imageId)
      .firstOption

  def removeTransactionForId(transactionId: Long)(implicit session: Session) =
    transactionQuery.filter(_.id === transactionId).delete

  def getTransactionForDestinationAndImageId(destination: Destination, imageId: Long)(implicit session: Session) = {
    val join = for {
      ruleEntry <- ruleQuery
      transactionEntry <- transactionQuery if ruleEntry.id === transactionEntry.forwardingRuleId
      imageEntry <- transactionImageQuery if transactionEntry.id === imageEntry.forwardingTransactionId
    } yield (ruleEntry, transactionEntry, imageEntry)
    join
      .filter(_._3.imageId === imageId)
      .filter(_._1.destinationType === destination.destinationType.toString)
      .filter(_._1.destinationId === destination.destinationId)
      .map(_._2).firstOption
  }

  def getDeliveredTransactions(implicit session: Session) =
    transactionQuery.filter(_.delivered === true).list

  def getUndeliveredTransactionsForSourceTypeAndId(sourceType: SourceType, sourceId: Long)(implicit session: Session) = {
    val join = for {
      ruleEntry <- ruleQuery
      transactionEntry <- transactionQuery if ruleEntry.id === transactionEntry.forwardingRuleId
    } yield (ruleEntry, transactionEntry)
    join
      .filter(_._1.sourceType === sourceType.toString)
      .filter(_._1.sourceId === sourceId)
      .filter(_._2.delivered === false)
      .map(_._2)
      .list
  }

  def removeTransactionImagesForImageId(imageId: Long)(implicit session: Session) =
    transactionImageQuery.filter(_.imageId === imageId).delete

}

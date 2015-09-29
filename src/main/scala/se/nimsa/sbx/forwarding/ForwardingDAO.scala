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
import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.app.GeneralProtocol._

class ForwardingDAO(val driver: JdbcProfile) {
  import driver.simple._

  private val toForwardingRule = (id: Long, sourceType: String, sourceName: String, sourceId: Long, destinationType: String, destinationName: String, destinationId: Long, keepImages: Boolean) => ForwardingRule(id, Source(SourceType.withName(sourceType), sourceName, sourceId), Destination(DestinationType.withName(destinationType), destinationName, destinationId), keepImages)

  private val fromForwardingRule = (rule: ForwardingRule) => Option((rule.id, rule.source.sourceType.toString, rule.source.sourceName, rule.source.sourceId, rule.destination.destinationType.toString, rule.destination.destinationName, rule.destination.destinationId, rule.keepImages))

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
    def * = (id, sourceType, sourceName, sourceId, destinationType, destinationName, destinationId, keepImages) <> (toForwardingRule.tupled, fromForwardingRule)
  }

  val ruleQuery = TableQuery[ForwardingRuleTable]

  private val toForwardingTransaction = (id: Long, forwardingRuleId: Long, lastUpdated: Long, processed: Boolean) => 
    ForwardingTransaction(id, forwardingRuleId, lastUpdated, processed)

  private val fromForwardingTransaction = (transaction: ForwardingTransaction) => 
    Option((transaction.id, transaction.forwardingRuleId, transaction.lastUpdated, transaction.processed))

  class ForwardingTransactionTable(tag: Tag) extends Table[ForwardingTransaction](tag, "ForwardingTransactions") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def forwardingRuleId = column[Long]("forwardingruleid")
    def lastUpdated = column[Long]("lastupdated")
    def processed = column[Boolean]("processed")
    def fkForwardingRule = foreignKey("fk_forwarding_rule", forwardingRuleId, ruleQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, forwardingRuleId, lastUpdated, processed) <> (toForwardingTransaction.tupled, fromForwardingTransaction)
  }

  val transactionQuery = TableQuery[ForwardingTransactionTable]

  private val toForwardingTransactionImage = (id: Long, forwardingTransactionId: Long, imageId: Long) => ForwardingTransactionImage(id, forwardingTransactionId, imageId)

  private val fromForwardingTransactionImage = (transactionImage: ForwardingTransactionImage) => Option((transactionImage.id, transactionImage.forwardingTransactionId, transactionImage.imageId))

  class ForwardingTransactionImageTable(tag: Tag) extends Table[ForwardingTransactionImage](tag, "ForwardingTransactionImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def forwardingTransactionId = column[Long]("forwardingtransactionid")
    def imageId = column[Long]("imageid")
    def fkForwardingTransaction = foreignKey("fk_forwarding_transaction", forwardingTransactionId, transactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, forwardingTransactionId, imageId) <> (toForwardingTransactionImage.tupled, fromForwardingTransactionImage)
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

  def listForwardingRules(implicit session: Session): List[ForwardingRule] =
    ruleQuery.list

  def removeForwardingRule(forwardingRuleId: Long)(implicit session: Session): Unit =
    ruleQuery.filter(_.id === forwardingRuleId).delete

  def getForwardingRuleForSourceTypeAndId(sourceType: SourceType, sourceId: Long)(implicit session: Session) =
    ruleQuery.filter(_.sourceType === sourceType.toString).filter(_.sourceId === sourceId).firstOption
      
  def createOrUpdateForwardingTransaction(forwardingRule: ForwardingRule)(implicit session: Session): ForwardingTransaction =
    getUnprocessedTransactionForRule(forwardingRule) match {
      case Some(transaction) =>
        val updatedTransaction = transaction.copy(lastUpdated = System.currentTimeMillis())
        updateForwardingTransaction(updatedTransaction)
        updatedTransaction
      case None =>
        insertForwardingTransaction(ForwardingTransaction(-1, forwardingRule.id, System.currentTimeMillis, false))
    }

  def getUnprocessedTransactionForRule(forwardingRule: ForwardingRule)(implicit session: Session): Option[ForwardingTransaction] =
    transactionQuery.filter(_.forwardingRuleId === forwardingRule.id).filter(_.processed === false).firstOption

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

  def listExpiredButNotProcessedTransactions(timeLimit: Long)(implicit session: Session) =
    transactionQuery.filter(_.lastUpdated < timeLimit).filter(!_.processed).list

  def getForwardingRuleForId(forwardingRuleId: Long)(implicit session: Session) =
    ruleQuery.filter(_.id === forwardingRuleId).firstOption

  def getTransactionImagesForTransactionId(transactionId: Long)(implicit session: Session) =
    transactionImageQuery.filter(_.forwardingTransactionId === transactionId).list

  def removeTransactionForId(transactionId: Long)(implicit session: Session) =
    transactionQuery.filter(_.id === transactionId).delete

  def getTransactionForImageId(imageId: Long)(implicit session: Session) = {
    val join = for {
      transactionEntry <- transactionQuery
      imageEntry <- transactionImageQuery if transactionEntry.id === imageEntry.forwardingTransactionId
    } yield (transactionEntry, imageEntry)
    join.filter(_._2.imageId === imageId).map(_._1).firstOption
  }

}

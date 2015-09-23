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

class ForwardingDAO(val driver: JdbcProfile) {
  import driver.simple._

  private val toForwardingRule = (id: Long, sourceType: String, sourceName: String, sourceId: Long, targetBoxName: String, targetBoxId: Long, keepImages: Boolean) => ForwardingRule(id, Source(SourceType.withName(sourceType), sourceName, sourceId), targetBoxName, targetBoxId, keepImages)

  private val fromForwardingRule = (rule: ForwardingRule) => Option((rule.id, rule.source.sourceType.toString, rule.source.sourceName, rule.source.sourceId, rule.targetBoxName, rule.targetBoxId, rule.keepImages))

  class ForwardingRuleTable(tag: Tag) extends Table[ForwardingRule](tag, "ForwardingRules") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sourceType = column[String]("sourcetype")
    def sourceName = column[String]("sourcename")
    def sourceId = column[Long]("sourceid")
    def targetBoxName = column[String]("targetboxname")
    def targetBoxId = column[Long]("targetboxid")
    def keepImages = column[Boolean]("keepimages")
    def idxUnique = index("idx_unique_forwarding_rule", (sourceType, sourceId, targetBoxId), unique = true)
    def * = (id, sourceType, sourceName, sourceId, targetBoxName, targetBoxId, keepImages) <> (toForwardingRule.tupled, fromForwardingRule)
  }

  val ruleQuery = TableQuery[ForwardingRuleTable]


  case class ForwardingTransaction(id: Long, forwardingRuleId: Long, lastUpdated: Long)
  
  private val toForwardingTransaction = (id: Long, forwardingRuleId: Long, lastUpdated: Long) => ForwardingTransaction(id, forwardingRuleId, lastUpdated)

  private val fromForwardingTransaction = (transaction: ForwardingTransaction) => Option((transaction.id, transaction.forwardingRuleId, transaction.lastUpdated))
  
  class ForwardingTransactionTable(tag: Tag) extends Table[ForwardingTransaction](tag, "ForwardingTransactions") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def forwardingRuleId = column[Long]("forwardingruleid")
    def lastUpdated = column[Long]("lastupdated")
    def fkForwardingRule = foreignKey("fk_forwarding_rule", forwardingRuleId, ruleQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, forwardingRuleId, lastUpdated) <> (toForwardingTransaction.tupled, fromForwardingTransaction)
  }

  val transactionQuery = TableQuery[ForwardingTransactionTable]

  
  case class ForwardingTransactionImage(id: Long, forwardingTransactionId: Long, imageId: Long)
  
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

  
  def insertForwardingRule(forwardingRule: ForwardingRule)(implicit session: Session): ForwardingRule = {
    val generatedId = (ruleQuery returning ruleQuery.map(_.id)) += forwardingRule
    forwardingRule.copy(id = generatedId)
  }

  def listForwardingRules(implicit session: Session): List[ForwardingRule] =
    ruleQuery.list

  def removeForwardingRule(forwardingRuleId: Long)(implicit session: Session): Unit =
    ruleQuery.filter(_.id === forwardingRuleId).delete

}

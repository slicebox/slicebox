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

package se.nimsa.sbx.transaction

import se.nimsa.sbx.transaction.TransactionProtocol.{Transaction, TransactionImage}

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable

class TransactionDAO(val driver: JdbcProfile) {
  import driver.simple._

  class TransactionTable(tag: Tag) extends Table[Transaction](tag, "Transactions") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def incoming = column[Boolean]("incoming")
    def clientId = column[Long]("clientid")
    def clientName = column[String]("clientname")
    def clientType = column[String]("clienttype")
    def files = column[Long]("files")
    def added = column[Long]("added")
    def created = column[Long]("created")
    def updated = column[Long]("updated")
    def * = (id, incoming, clientId, clientName, clientType, files, added, created, updated) <> (Transaction.tupled, Transaction.unapply)
  }

  val transactionQuery = TableQuery[TransactionTable]

  class TransactionImageTable(tag: Tag) extends Table[TransactionImage](tag, "TransactionImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def transactionId = column[Long]("transactionid")
    def overwrite = column[Boolean]("overwrite")
    def imageId = column[Long]("imageid")
    def fkTransaction = foreignKey("fk_transaction_id", transactionId, transactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, transactionId, overwrite, imageId) <> (TransactionImage.tupled, TransactionImage.unapply)
  }

  val transactionImageQuery = TableQuery[TransactionImageTable]

  def create(implicit session: Session): Unit = {
    if (MTable.getTables("Transactions").list.isEmpty) transactionQuery.ddl.create
    if (MTable.getTables("TransactionImages").list.isEmpty) transactionImageQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (transactionQuery.ddl ++ transactionImageQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    transactionQuery.delete // cascade deletes images
  }

  def listTransactions(implicit session: Session): List[Transaction] =
    transactionQuery.list

  def insertTransaction(transaction: Transaction)(implicit session: Session): Transaction = {
    val generatedId = (transactionQuery returning transactionQuery.map(_.id)) += transaction
    transaction.copy(id = generatedId)
  }

  def insertTransactionImage(transactionImage: TransactionImage)(implicit session: Session): TransactionImage = {
    val generatedId = (transactionImageQuery returning transactionImageQuery.map(_.id)) += transactionImage
    transactionImage.copy(id = generatedId)
  }

  def transactionById(transactionId: Long)(implicit session: Session): Option[Transaction] =
    transactionQuery.filter(_.id === transactionId).firstOption

  def updateTransaction(transaction: Transaction)(implicit session: Session): Unit =
    transactionQuery.filter(_.id === transaction.id).update(transaction)

  def removeTransaction(transactionId: Long)(implicit session: Session): Unit =
    transactionQuery.filter(_.id === transactionId).delete

  def listTransactionImages(implicit session: Session): List[TransactionImage] =
    transactionImageQuery.list

  def transactionImagesByTransactionId(transactionId: Long)(implicit session: Session): List[TransactionImage] =
    transactionImageQuery.filter(_.transactionId === transactionId).list

}

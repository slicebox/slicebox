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

package se.nimsa.sbx.box

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import BoxProtocol._
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }

class BoxDAO(val driver: JdbcProfile) {
  import driver.simple._

  val toBox = (id: Long, name: String, token: String, baseUrl: String, sendMethod: String, online: Boolean) => Box(id, name, token, baseUrl, BoxSendMethod.withName(sendMethod), online)
  val fromBox = (box: Box) => Option((box.id, box.name, box.token, box.baseUrl, box.sendMethod.toString, box.online))

  class BoxTable(tag: Tag) extends Table[Box](tag, "Box") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def token = column[String]("token")
    def baseUrl = column[String]("baseurl")
    def sendMethod = column[String]("sendmethod")
    def online = column[Boolean]("online")
    def idxUniqueName = index("idx_unique_box_name", name, unique = true)
    def * = (id, name, token, baseUrl, sendMethod, online) <> (toBox.tupled, fromBox)
  }

  val boxQuery = TableQuery[BoxTable]

  val toOutboxEntry = (id: Long, remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageFileId: Long, failed: Boolean) =>
    OutboxEntry(id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageFileId, failed)
  val fromOutboxEntry = (entry: OutboxEntry) => Option((entry.id, entry.remoteBoxId, entry.transactionId, entry.sequenceNumber, entry.totalImageCount, entry.imageFileId, entry.failed))

  // TODO: should probably add unique index on (remoteBoxId,transactionId)
  class OutboxTable(tag: Tag) extends Table[OutboxEntry](tag, "Outbox") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def transactionId = column[Long]("transactionid")
    def sequenceNumber = column[Long]("sequencenumber")
    def totalImageCount = column[Long]("totalimagecount")
    def imageFileId = column[Long]("imagefileid")
    def failed = column[Boolean]("failed")
    def * = (id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageFileId, failed) <> (toOutboxEntry.tupled, fromOutboxEntry)
  }

  val outboxQuery = TableQuery[OutboxTable]

  val toInboxEntry = (id: Long, remoteBoxId: Long, transactionId: Long, receivedImageCount: Long, totalImageCount: Long) =>
    InboxEntry(id, remoteBoxId, transactionId, receivedImageCount, totalImageCount)
  val fromInboxEntry = (entry: InboxEntry) => Option((entry.id, entry.remoteBoxId, entry.transactionId, entry.receivedImageCount, entry.totalImageCount))

  class InboxTable(tag: Tag) extends Table[InboxEntry](tag, "Inbox") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def transactionId = column[Long]("transactionid")
    def receivedImageCount = column[Long]("receivedimagecount")
    def totalImageCount = column[Long]("totalimagecount")
    def * = (id, remoteBoxId, transactionId, receivedImageCount, totalImageCount) <> (toInboxEntry.tupled, fromInboxEntry)
  }

  val inboxQuery = TableQuery[InboxTable]

  def columnExists(tableName: String, columnName: String)(implicit session: Session): Boolean = {
    val tables = MTable.getTables(tableName).list
    if (tables.isEmpty)
      false
    else
      !tables(0).getColumns.list.filter(_.name == columnName).isEmpty
  }

  def create(implicit session: Session): Unit =
    if (MTable.getTables("Box").list.isEmpty) {
      (boxQuery.ddl ++ outboxQuery.ddl ++ inboxQuery.ddl).create
    }

  def drop(implicit session: Session): Unit =
    (boxQuery.ddl ++ outboxQuery.ddl ++ inboxQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    boxQuery.delete
    inboxQuery.delete
    outboxQuery.delete
  }
  
  def insertBox(box: Box)(implicit session: Session): Box = {
    val generatedId = (boxQuery returning boxQuery.map(_.id)) += box
    box.copy(id = generatedId)
  }

  def insertOutboxEntry(entry: OutboxEntry)(implicit session: Session): OutboxEntry = {
    val generatedId = (outboxQuery returning outboxQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def insertInboxEntry(entry: InboxEntry)(implicit session: Session): InboxEntry = {
    val generatedId = (inboxQuery returning inboxQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def boxById(boxId: Long)(implicit session: Session): Option[Box] =
    boxQuery.filter(_.id === boxId).list.headOption

  def boxByName(name: String)(implicit session: Session): Option[Box] =
    boxQuery.filter(_.name === name).list.headOption

  def pushBoxByBaseUrl(baseUrl: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === BoxSendMethod.PUSH.toString)
      .filter(_.baseUrl === baseUrl)
      .list.headOption

  def pollBoxByToken(token: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === BoxSendMethod.POLL.toString)
      .filter(_.token === token)
      .list.headOption

  def updateBoxOnlineStatus(boxId: Long, online: Boolean)(implicit session: Session): Unit =
    boxQuery
      .filter(_.id === boxId)
      .map(_.online)
      .update(online)

  def updateInboxEntry(entry: InboxEntry)(implicit session: Session): Unit =
    inboxQuery.filter(_.id === entry.id).update(entry)

  def nextOutboxEntryForRemoteBoxId(remoteBoxId: Long)(implicit session: Session): Option[OutboxEntry] =
    outboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.failed === false)
      .sortBy(_.sequenceNumber.asc)
      .list.headOption

  def markOutboxTransactionAsFailed(remoteBoxId: Long, transactionId: Long)(implicit session: Session): Unit =
    outboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .map(_.failed)
      .update(true)

  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long)(implicit session: Session): Unit = {
    inboxEntryByTransactionId(remoteBoxId, transactionId) match {
      case Some(inboxEntry) => {
        val updatedInboxEntry = inboxEntry.copy(receivedImageCount = sequenceNumber, totalImageCount = totalImageCount)
        updateInboxEntry(updatedInboxEntry)
      }
      case None => {
        val inboxEntry = InboxEntry(-1, remoteBoxId, transactionId, sequenceNumber, totalImageCount)
        insertInboxEntry(inboxEntry)
      }
    }
  }

  def inboxEntryByTransactionId(remoteBoxId: Long, transactionId: Long)(implicit session: Session): Option[InboxEntry] =
    inboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .list.headOption

  def outboxEntryById(outboxEntryId: Long)(implicit session: Session): Option[OutboxEntry] =
    outboxQuery
      .filter(_.id === outboxEntryId)
      .list.headOption

  def outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long)(implicit session: Session): Option[OutboxEntry] =
    outboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .filter(_.sequenceNumber === sequenceNumber)
      .list.headOption

  def removeInboxEntry(entryId: Long)(implicit session: Session): Unit =
    inboxQuery.filter(_.id === entryId).delete

  def removeBox(boxId: Long)(implicit session: Session): Unit =
    boxQuery.filter(_.id === boxId).delete

  def removeOutboxEntry(entryId: Long)(implicit session: Session): Unit =
    outboxQuery.filter(_.id === entryId).delete

  def removeOutboxEntries(entryIds: Seq[Long])(implicit session: Session): Unit =
    outboxQuery.filter(_.id inSet entryIds).delete

  def listBoxes(implicit session: Session): List[Box] =
    boxQuery.list

  def listOutboxEntries(implicit session: Session): List[OutboxEntry] =
    outboxQuery.list

  def listInboxEntries(implicit session: Session): List[InboxEntry] =
    inboxQuery.list

}

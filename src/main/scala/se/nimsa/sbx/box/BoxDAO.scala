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
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue

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

  val toOutboxEntry = (id: Long, remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, failed: Boolean) =>
    OutboxEntry(id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageId, failed)
  val fromOutboxEntry = (entry: OutboxEntry) => Option((entry.id, entry.remoteBoxId, entry.transactionId, entry.sequenceNumber, entry.totalImageCount, entry.imageId, entry.failed))

  // TODO: should probably add unique index on (remoteBoxId,transactionId)
  class OutboxTable(tag: Tag) extends Table[OutboxEntry](tag, "Outbox") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def transactionId = column[Long]("transactionid")
    def sequenceNumber = column[Long]("sequencenumber")
    def totalImageCount = column[Long]("totalimagecount")
    def imageId = column[Long]("imageid")
    def failed = column[Boolean]("failed")
    def * = (id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageId, failed) <> (toOutboxEntry.tupled, fromOutboxEntry)
  }

  val outboxQuery = TableQuery[OutboxTable]

  val toInboxEntry = (id: Long, remoteBoxId: Long, transactionId: Long, receivedImageCount: Long, totalImageCount: Long, lastUpdated: Long) =>
    InboxEntry(id, remoteBoxId, transactionId, receivedImageCount, totalImageCount, lastUpdated)
  val fromInboxEntry = (entry: InboxEntry) => Option((entry.id, entry.remoteBoxId, entry.transactionId, entry.receivedImageCount, entry.totalImageCount, entry.lastUpdated))

  class InboxTable(tag: Tag) extends Table[InboxEntry](tag, "Inbox") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def transactionId = column[Long]("transactionid")
    def receivedImageCount = column[Long]("receivedimagecount")
    def totalImageCount = column[Long]("totalimagecount")
    def lastUpdated = column[Long]("lastupdated")
    def * = (id, remoteBoxId, transactionId, receivedImageCount, totalImageCount, lastUpdated) <> (toInboxEntry.tupled, fromInboxEntry)
  }

  val inboxQuery = TableQuery[InboxTable]

  val toSentEntry = (id: Long, remoteBoxId: Long, transactionId: Long, sentImageCount: Long, totalImageCount: Long, lastUpdated: Long) =>
    SentEntry(id, remoteBoxId, transactionId, sentImageCount, totalImageCount, lastUpdated)
  val fromSentEntry = (entry: SentEntry) => Option((entry.id, entry.remoteBoxId, entry.transactionId, entry.sentImageCount, entry.totalImageCount, entry.lastUpdated))

  class SentTable(tag: Tag) extends Table[SentEntry](tag, "Sent") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def transactionId = column[Long]("transactionid")
    def sentImageCount = column[Long]("sentimagecount")
    def totalImageCount = column[Long]("totalimagecount")
    def lastUpdated = column[Long]("lastupdated")
    def * = (id, remoteBoxId, transactionId, sentImageCount, totalImageCount, lastUpdated) <> (toSentEntry.tupled, fromSentEntry)
  }

  val sentQuery = TableQuery[SentTable]

  val toSentImage = (id: Long, sentEntryId: Long, imageId: Long) => SentImage(id, sentEntryId, imageId)
  val fromSentImage = (sentImage: SentImage) => Option((sentImage.id, sentImage.sentEntryId, sentImage.imageId))

  class SentImageTable(tag: Tag) extends Table[SentImage](tag, "SentImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sentEntryId = column[Long]("sententryid")
    def imageId = column[Long]("imageid")
    def fkInboxEntry = foreignKey("fk_sent_entry", sentEntryId, sentQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, sentEntryId, imageId) <> (toSentImage.tupled, fromSentImage)
  }

  val sentImageQuery = TableQuery[SentImageTable]

  val toInboxImage = (id: Long, inboxEntryId: Long, imageId: Long) => InboxImage(id, inboxEntryId, imageId)
  val fromInboxImage = (inboxImage: InboxImage) => Option((inboxImage.id, inboxImage.inboxEntryId, inboxImage.imageId))

  class InboxImageTable(tag: Tag) extends Table[InboxImage](tag, "InboxImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def inboxEntryId = column[Long]("inboxentryid")
    def imageId = column[Long]("imageid")
    def fkInboxEntry = foreignKey("fk_inbox_entry", inboxEntryId, inboxQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, inboxEntryId, imageId) <> (toInboxImage.tupled, fromInboxImage)
  }

  val inboxImageQuery = TableQuery[InboxImageTable]

  val toTransactionTagValue = (id: Long, transactionId: Long, imageId: Long, tag: Int, value: String) => TransactionTagValue(id, transactionId, imageId, TagValue(tag, value))
  val fromTransactionTagValue = (entry: TransactionTagValue) => Option((entry.id, entry.transactionId, entry.imageId, entry.tagValue.tag, entry.tagValue.value))

  class TransactionTagValueTable(tag: Tag) extends Table[TransactionTagValue](tag, "TransactionTagValue") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def transactionId = column[Long]("transactionid")
    def imageId = column[Long]("imageid")
    def dicomTag = column[Int]("tag")
    def value = column[String]("value")
    def * = (id, transactionId, imageId, dicomTag, value) <> (toTransactionTagValue.tupled, fromTransactionTagValue)
  }

  val transactionTagValueQuery = TableQuery[TransactionTagValueTable]

  def columnExists(tableName: String, columnName: String)(implicit session: Session): Boolean = {
    val tables = MTable.getTables(tableName).list
    if (tables.isEmpty)
      false
    else
      !tables(0).getColumns.list.filter(_.name == columnName).isEmpty
  }

  def create(implicit session: Session): Unit = {
    if (MTable.getTables("Box").list.isEmpty) boxQuery.ddl.create
    if (MTable.getTables("Outbox").list.isEmpty) outboxQuery.ddl.create
    if (MTable.getTables("Inbox").list.isEmpty) inboxQuery.ddl.create
    if (MTable.getTables("Sent").list.isEmpty) sentQuery.ddl.create
    if (MTable.getTables("InboxImages").list.isEmpty) inboxImageQuery.ddl.create
    if (MTable.getTables("SentImages").list.isEmpty) sentImageQuery.ddl.create
    if (MTable.getTables("TransactionTagValue").list.isEmpty) transactionTagValueQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (boxQuery.ddl ++ outboxQuery.ddl ++ inboxQuery.ddl ++ inboxImageQuery.ddl ++ sentQuery.ddl ++ sentImageQuery.ddl ++ transactionTagValueQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    boxQuery.delete
    inboxQuery.delete
    outboxQuery.delete
    sentQuery.delete
    inboxImageQuery.delete
    sentImageQuery.delete
    transactionTagValueQuery.delete
  }

  def listTransactionTagValues(implicit session: Session): List[TransactionTagValue] =
    transactionTagValueQuery.list

  def insertTransactionTagValue(entry: TransactionTagValue)(implicit session: Session): TransactionTagValue = {
    val generatedId = (transactionTagValueQuery returning transactionTagValueQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def tagValuesByImageIdAndTransactionId(imageId: Long, transactionId: Long)(implicit session: Session): List[TransactionTagValue] =
    transactionTagValueQuery.filter(_.imageId === imageId).filter(_.transactionId === transactionId).list

  def removeTransactionTagValue(transactionTagValueId: Long)(implicit session: Session): Unit =
    transactionTagValueQuery.filter(_.id === transactionTagValueId).delete

  def removeTransactionTagValuesByTransactionId(transactionId: Long)(implicit session: Session): Unit =
    transactionTagValueQuery.filter(_.transactionId === transactionId).delete

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

  def insertSentEntry(entry: SentEntry)(implicit session: Session): SentEntry = {
    val generatedId = (sentQuery returning sentQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def insertInboxImage(inboxImage: InboxImage)(implicit session: Session): InboxImage = {
    val generatedId = (inboxImageQuery returning inboxImageQuery.map(_.id)) += inboxImage
    inboxImage.copy(id = generatedId)
  }

  def insertSentImage(sentImage: SentImage)(implicit session: Session): SentImage = {
    val generatedId = (sentImageQuery returning sentImageQuery.map(_.id)) += sentImage
    sentImage.copy(id = generatedId)
  }

  def boxById(boxId: Long)(implicit session: Session): Option[Box] =
    boxQuery.filter(_.id === boxId).firstOption

  def boxByName(name: String)(implicit session: Session): Option[Box] =
    boxQuery.filter(_.name === name).firstOption

  def pushBoxByBaseUrl(baseUrl: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === BoxSendMethod.PUSH.toString)
      .filter(_.baseUrl === baseUrl)
      .firstOption

  def pollBoxByToken(token: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === BoxSendMethod.POLL.toString)
      .filter(_.token === token)
      .firstOption

  def updateBoxOnlineStatus(boxId: Long, online: Boolean)(implicit session: Session): Unit =
    boxQuery
      .filter(_.id === boxId)
      .map(_.online)
      .update(online)

  def updateInboxEntry(entry: InboxEntry)(implicit session: Session): Unit =
    inboxQuery.filter(_.id === entry.id).update(entry)

  def updateSentEntry(entry: SentEntry)(implicit session: Session): Unit =
    sentQuery.filter(_.id === entry.id).update(entry)

  def nextOutboxEntryForRemoteBoxId(remoteBoxId: Long)(implicit session: Session): Option[OutboxEntry] =
    outboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.failed === false)
      .sortBy(_.sequenceNumber.asc)
      .firstOption

  def markOutboxTransactionAsFailed(remoteBoxId: Long, transactionId: Long)(implicit session: Session): Unit =
    outboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .map(_.failed)
      .update(true)

  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long)(implicit session: Session): InboxEntry = {
    inboxEntryByTransactionId(remoteBoxId, transactionId) match {
      case Some(inboxEntry) =>
        val updatedInboxEntry = inboxEntry.copy(receivedImageCount = sequenceNumber, totalImageCount = totalImageCount, lastUpdated = System.currentTimeMillis())
        updateInboxEntry(updatedInboxEntry)
        updatedInboxEntry
      case None => {
        val inboxEntry = InboxEntry(-1, remoteBoxId, transactionId, sequenceNumber, totalImageCount, System.currentTimeMillis())
        insertInboxEntry(inboxEntry)
      }
    }
  }

  def updateSent(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long)(implicit session: Session): SentEntry = {
    sentEntryByTransactionId(remoteBoxId, transactionId) match {
      case Some(sentEntry) => {
        val updatedSentEntry = sentEntry.copy(sentImageCount = sequenceNumber, totalImageCount = totalImageCount, lastUpdated = System.currentTimeMillis())
        updateSentEntry(updatedSentEntry)
        updatedSentEntry
      }
      case None => {
        val sentEntry = SentEntry(-1, remoteBoxId, transactionId, sequenceNumber, totalImageCount, System.currentTimeMillis())
        insertSentEntry(sentEntry)
      }
    }
  }

  def inboxEntryByTransactionId(remoteBoxId: Long, transactionId: Long)(implicit session: Session): Option[InboxEntry] =
    inboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .firstOption

  def sentEntryByTransactionId(remoteBoxId: Long, transactionId: Long)(implicit session: Session): Option[SentEntry] =
    sentQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .firstOption

  def outboxEntryById(outboxEntryId: Long)(implicit session: Session): Option[OutboxEntry] =
    outboxQuery
      .filter(_.id === outboxEntryId)
      .firstOption

  def outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long)(implicit session: Session): Option[OutboxEntry] =
    outboxQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .filter(_.sequenceNumber === sequenceNumber)
      .firstOption

  def removeInboxEntry(entryId: Long)(implicit session: Session): Unit =
    inboxQuery.filter(_.id === entryId).delete

  def removeBox(boxId: Long)(implicit session: Session): Unit =
    boxQuery.filter(_.id === boxId).delete

  def removeOutboxEntry(entryId: Long)(implicit session: Session): Unit =
    outboxQuery.filter(_.id === entryId).delete

  def removeOutboxEntries(entryIds: Seq[Long])(implicit session: Session): Unit =
    outboxQuery.filter(_.id inSet entryIds).delete

  def removeSentEntry(entryId: Long)(implicit session: Session): Unit =
    sentQuery.filter(_.id === entryId).delete

  def listBoxes(implicit session: Session): List[Box] =
    boxQuery.list

  def listOutboxEntries(implicit session: Session): List[OutboxEntry] =
    outboxQuery.list

  def listInboxEntries(implicit session: Session): List[InboxEntry] =
    inboxQuery.list

  def listSentEntries(implicit session: Session): List[SentEntry] =
    sentQuery.list

  def listInboxImages(implicit session: Session): List[InboxImage] =
    inboxImageQuery.list

  def listSentImagesForSentEntryId(sentEntryId: Long)(implicit session: Session): List[SentImage] =
    sentImageQuery.filter(_.sentEntryId === sentEntryId).list

  def listInboxImagesForInboxEntryId(inboxEntryId: Long)(implicit session: Session): List[InboxImage] =
    inboxImageQuery.filter(_.inboxEntryId === inboxEntryId).list

  def inboxEntryByImageId(imageId: Long)(implicit session: Session): Option[InboxEntry] = {
    val join = for {
      inboxEntry <- inboxQuery
      imageEntry <- inboxImageQuery if inboxEntry.id === imageEntry.inboxEntryId
    } yield (inboxEntry, imageEntry)
    join.filter(_._2.imageId === imageId).map(_._1).firstOption
  }

  def sentImagesByTransactionId(transactionId: Long)(implicit session: Session): List[SentImage] = {
    val join = for {
      sentEntry <- sentQuery
      imageEntry <- sentImageQuery if sentEntry.id === imageEntry.sentEntryId
    } yield (sentEntry, imageEntry)
    join.filter(_._1.transactionId === transactionId).map(_._2).list
  }

}

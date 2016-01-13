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
import BoxProtocol.BoxSendMethod._
import BoxProtocol.TransactionStatus._
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue

class BoxDAO(val driver: JdbcProfile) {
  import driver.simple._

  implicit val statusColumnType =
    MappedColumnType.base[TransactionStatus, String](ts => ts.toString, TransactionStatus.withName)

  implicit val sendMethodColumnType =
    MappedColumnType.base[BoxSendMethod, String](bsm => bsm.toString, BoxSendMethod.withName)

  class BoxTable(tag: Tag) extends Table[Box](tag, "Box") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def token = column[String]("token")
    def baseUrl = column[String]("baseurl")
    def sendMethod = column[BoxSendMethod]("sendmethod")
    def online = column[Boolean]("online")
    def idxUniqueName = index("idx_unique_box_name", name, unique = true)
    def * = (id, name, token, baseUrl, sendMethod, online) <> (Box.tupled, Box.unapply)
  }

  val boxQuery = TableQuery[BoxTable]

  class OutgoingTable(tag: Tag) extends Table[OutgoingEntry](tag, "Outgoing") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def remoteBoxName = column[String]("remoteboxname")
    def transactionId = column[Long]("transactionid")
    def deliveredImageCount = column[Long]("deliveredimagecount")
    def totalImageCount = column[Long]("totalimagecount")
    def lastUpdated = column[Long]("lastupdated")
    def status = column[TransactionStatus]("status")
    def * = (id, remoteBoxId, remoteBoxName, transactionId, deliveredImageCount, totalImageCount, lastUpdated, status) <> (OutgoingEntry.tupled, OutgoingEntry.unapply)
  }

  val outgoingQuery = TableQuery[OutgoingTable]

  class OutgoingImageTable(tag: Tag) extends Table[OutgoingImage](tag, "OutgoingImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def outgoingEntryId = column[Long]("outgoingentryid")
    def imageId = column[Long]("imageid")
    def delivered = column[Boolean]("delivered")
    def fkInboxEntry = foreignKey("fk_outgoing_entry", outgoingEntryId, outgoingQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, outgoingEntryId, imageId, delivered) <> (OutgoingImage.tupled, OutgoingImage.unapply)
  }

  val outgoingImageQuery = TableQuery[OutgoingImageTable]

  class IncomingTable(tag: Tag) extends Table[IncomingEntry](tag, "Incoming") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def remoteBoxId = column[Long]("remoteboxid")
    def remoteBoxName = column[String]("remoteboxname")
    def transactionId = column[Long]("transactionid")
    def receivedImageCount = column[Long]("receivedimagecount")
    def totalImageCount = column[Long]("totalimagecount")
    def lastUpdated = column[Long]("lastupdated")
    def status = column[TransactionStatus]("status")
    def * = (id, remoteBoxId, remoteBoxName, transactionId, receivedImageCount, totalImageCount, lastUpdated, status) <> (IncomingEntry.tupled, IncomingEntry.unapply)
  }

  val incomingQuery = TableQuery[IncomingTable]

  class IncomingImageTable(tag: Tag) extends Table[IncomingImage](tag, "IncomingImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def incomingEntryId = column[Long]("incomingentryid")
    def imageId = column[Long]("imageid")
    def fkInboxEntry = foreignKey("fk_incoming_entry", incomingEntryId, incomingQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, incomingEntryId, imageId) <> (IncomingImage.tupled, IncomingImage.unapply)
  }

  val incomingImageQuery = TableQuery[IncomingImageTable]

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
    if (MTable.getTables("Outgoing").list.isEmpty) outgoingQuery.ddl.create
    if (MTable.getTables("Incoming").list.isEmpty) incomingQuery.ddl.create
    if (MTable.getTables("OutgoingImages").list.isEmpty) outgoingImageQuery.ddl.create
    if (MTable.getTables("IncomingImages").list.isEmpty) incomingImageQuery.ddl.create
    if (MTable.getTables("TransactionTagValue").list.isEmpty) transactionTagValueQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (boxQuery.ddl ++ outgoingQuery.ddl ++ incomingQuery.ddl ++ outgoingImageQuery.ddl ++ incomingImageQuery.ddl ++ transactionTagValueQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    boxQuery.delete
    incomingQuery.delete
    outgoingQuery.delete
    outgoingImageQuery.delete
    incomingImageQuery.delete
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

  def insertOutgoingEntry(entry: OutgoingEntry)(implicit session: Session): OutgoingEntry = {
    val generatedId = (outgoingQuery returning outgoingQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def insertIncomingEntry(entry: IncomingEntry)(implicit session: Session): IncomingEntry = {
    val generatedId = (incomingQuery returning incomingQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def insertOutgoingImage(outgoingImage: OutgoingImage)(implicit session: Session): OutgoingImage = {
    val generatedId = (outgoingImageQuery returning outgoingImageQuery.map(_.id)) += outgoingImage
    outgoingImage.copy(id = generatedId)
  }

  def insertIncomingImage(incomingImage: IncomingImage)(implicit session: Session): IncomingImage = {
    val generatedId = (incomingImageQuery returning incomingImageQuery.map(_.id)) += incomingImage
    incomingImage.copy(id = generatedId)
  }

  def boxById(boxId: Long)(implicit session: Session): Option[Box] =
    boxQuery.filter(_.id === boxId).firstOption

  def boxByName(name: String)(implicit session: Session): Option[Box] =
    boxQuery.filter(_.name === name).firstOption

  def pushBoxByBaseUrl(baseUrl: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === (PUSH: BoxSendMethod))
      .filter(_.baseUrl === baseUrl)
      .firstOption

  def pollBoxByToken(token: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === (POLL: BoxSendMethod))
      .filter(_.token === token)
      .firstOption

  def updateBoxOnlineStatus(boxId: Long, online: Boolean)(implicit session: Session): Unit =
    boxQuery
      .filter(_.id === boxId)
      .map(_.online)
      .update(online)

  def updateIncomingEntry(entry: IncomingEntry)(implicit session: Session): Unit =
    incomingQuery.filter(_.id === entry.id).update(entry)

  def updateOutgoingEntry(entry: OutgoingEntry)(implicit session: Session): Unit =
    outgoingQuery.filter(_.id === entry.id).update(entry)

  def updateOutgoingImage(image: OutgoingImage)(implicit session: Session): Unit =
    outgoingImageQuery.filter(_.id === image.id).update(image)

  def nextOutgoingEntryAndImageForRemoteBoxId(remoteBoxId: Long)(implicit session: Session): Option[OutgoingEntryAndImage] = {
    val join = for {
      outgoingEntry <- outgoingQuery
      imageEntry <- outgoingImageQuery if outgoingEntry.id === imageEntry.outgoingEntryId
    } yield (outgoingEntry, imageEntry)
    join
      .filter(_._1.remoteBoxId === remoteBoxId)
      .filterNot(_._1.status === (FAILED: TransactionStatus))
      .filterNot(_._1.status === (FINISHED: TransactionStatus))
      .filter(_._2.delivered === false)
      .firstOption
      .map(OutgoingEntryAndImage.tupled)
  }

  def outgoingEntryAndImageByTransactionIdAndImageId(remoteBoxId: Long, transactionId: Long, imageId: Long)(implicit session: Session): Option[OutgoingEntryAndImage] = {
    val join = for {
      outgoingEntry <- outgoingQuery
      imageEntry <- outgoingImageQuery if outgoingEntry.id === imageEntry.outgoingEntryId
    } yield (outgoingEntry, imageEntry)
    join
      .filter(_._1.remoteBoxId === remoteBoxId)
      .filter(_._1.transactionId === transactionId)
      .filterNot(_._1.status === (FAILED: TransactionStatus))
      .filterNot(_._1.status === (FINISHED: TransactionStatus))
      .firstOption
      .map(OutgoingEntryAndImage.tupled)
  }

  def setOutgoingTransactionStatus(remoteBoxId: Long, transactionId: Long, status: TransactionStatus)(implicit session: Session): Unit =
    outgoingQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .map(_.status)
      .update(status)

  def incomingEntryByTransactionId(remoteBoxId: Long, transactionId: Long)(implicit session: Session): Option[IncomingEntry] =
    incomingQuery
      .filter(_.remoteBoxId === remoteBoxId)
      .filter(_.transactionId === transactionId)
      .firstOption

  def outgoingEntryById(outboxEntryId: Long)(implicit session: Session): Option[OutgoingEntry] =
    outgoingQuery
      .filter(_.id === outboxEntryId)
      .firstOption

  def removeIncomingEntry(entryId: Long)(implicit session: Session): Unit =
    incomingQuery.filter(_.id === entryId).delete

  def removeBox(boxId: Long)(implicit session: Session): Unit =
    boxQuery.filter(_.id === boxId).delete

  def removeOutgoingEntry(entryId: Long)(implicit session: Session): Unit =
    outgoingQuery.filter(_.id === entryId).delete

  def removeOutgoingEntries(entryIds: Seq[Long])(implicit session: Session): Unit =
    outgoingQuery.filter(_.id inSet entryIds).delete

  def listBoxes(implicit session: Session): List[Box] =
    boxQuery.list

  def listOutgoingEntries(implicit session: Session): List[OutgoingEntry] =
    outgoingQuery
      .sortBy(_.id.desc)
      .list

  def listIncomingEntries(implicit session: Session): List[IncomingEntry] =
    incomingQuery
      .sortBy(_.lastUpdated.desc)
      .list

  def listIncomingImages(implicit session: Session): List[IncomingImage] =
    incomingImageQuery.list

  def listOutgoingImagesForOutgoingEntryId(outgoingEntryId: Long)(implicit session: Session): List[OutgoingImage] =
    outgoingImageQuery.filter(_.outgoingEntryId === outgoingEntryId).list

  def listIncomingImagesForIncomingEntryId(incomingEntryId: Long)(implicit session: Session): List[IncomingImage] =
    incomingImageQuery.filter(_.incomingEntryId === incomingEntryId).list

  def incomingEntryByImageId(imageId: Long)(implicit session: Session): Option[IncomingEntry] = {
    val join = for {
      incomingEntry <- incomingQuery
      imageEntry <- incomingImageQuery if incomingEntry.id === imageEntry.incomingEntryId
    } yield (incomingEntry, imageEntry)
    join.filter(_._2.imageId === imageId).map(_._1).firstOption
  }

  def outgoingImagesByTransactionId(transactionId: Long)(implicit session: Session): List[OutgoingImage] = {
    val join = for {
      outgoingEntry <- outgoingQuery
      imageEntry <- outgoingImageQuery if outgoingEntry.id === imageEntry.outgoingEntryId
    } yield (outgoingEntry, imageEntry)
    join.filter(_._1.transactionId === transactionId).map(_._2).list
  }

  def removeOutgoingImagesForImageId(imageId: Long)(implicit session: Session) =
    outgoingImageQuery.filter(_.imageId === imageId).delete

  def removeIncomingImagesForImageId(imageId: Long)(implicit session: Session) =
    incomingImageQuery.filter(_.imageId === imageId).delete

  def removeTransactionTagValuesForImageId(imageId: Long)(implicit session: Session) =
    transactionTagValueQuery.filter(_.imageId === imageId).delete

}

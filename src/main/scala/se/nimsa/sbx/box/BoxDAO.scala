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

package se.nimsa.sbx.box

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import BoxProtocol._
import BoxProtocol.BoxSendMethod._
import BoxProtocol.TransactionStatus._
import scala.slick.jdbc.{GetResult, StaticQuery => Q}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue

class BoxDAO(val driver: JdbcProfile) {

  import driver.simple._

  implicit val statusColumnType =
    MappedColumnType.base[TransactionStatus, String](ts => ts.toString(), TransactionStatus.withName)

  implicit val sendMethodColumnType =
    MappedColumnType.base[BoxSendMethod, String](bsm => bsm.toString(), BoxSendMethod.withName)

  class BoxTable(tag: Tag) extends Table[Box](tag, "Boxes") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def name = column[String]("name")

    def token = column[String]("token")

    def baseUrl = column[String]("baseurl")

    def sendMethod = column[BoxSendMethod]("sendmethod")

    def online = column[Boolean]("online")

    def idxUniqueName = index("idx_unique_box_name", name, unique = true)

    def * = (id, name, token, baseUrl, sendMethod, online) <>(Box.tupled, Box.unapply)
  }

  val boxQuery = TableQuery[BoxTable]

  class OutgoingTransactionTable(tag: Tag) extends Table[OutgoingTransaction](tag, "OutgoingTransactions") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def boxId = column[Long]("boxid")

    def boxName = column[String]("boxname")

    def sentImageCount = column[Long]("sentimagecount")

    def totalImageCount = column[Long]("totalimagecount")

    def created = column[Long]("created")

    def updated = column[Long]("updated")

    def status = column[TransactionStatus]("status")

    def * = (id, boxId, boxName, sentImageCount, totalImageCount, created, updated, status) <>(OutgoingTransaction.tupled, OutgoingTransaction.unapply)
  }

  val outgoingTransactionQuery = TableQuery[OutgoingTransactionTable]

  class OutgoingImageTable(tag: Tag) extends Table[OutgoingImage](tag, "OutgoingImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def outgoingTransactionId = column[Long]("outgoingtransactionid")

    def imageId = column[Long]("imageid")

    def sequenceNumber = column[Long]("sequencenumber")

    def sent = column[Boolean]("sent")

    def fkOutgoingTransaction = foreignKey("fk_outgoing_transaction_id", outgoingTransactionId, outgoingTransactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def idxUniqueTransactionAndNumber = index("idx_unique_outgoing_image", (outgoingTransactionId, sequenceNumber), unique = true)

    def * = (id, outgoingTransactionId, imageId, sequenceNumber, sent) <>(OutgoingImage.tupled, OutgoingImage.unapply)
  }

  val outgoingImageQuery = TableQuery[OutgoingImageTable]

  val toOutgoingTagValue = (id: Long, outgoingImageId: Long, tag: Int, value: String) => OutgoingTagValue(id, outgoingImageId, TagValue(tag, value))
  val fromOutgoingTagValue = (tagValue: OutgoingTagValue) => Option((tagValue.id, tagValue.outgoingImageId, tagValue.tagValue.tag, tagValue.tagValue.value))

  class OutgoingTagValueTable(tag: Tag) extends Table[OutgoingTagValue](tag, "OutgoingTagValues") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def outgoingImageId = column[Long]("outgoingimageid")

    def dicomTag = column[Int]("tag")

    def value = column[String]("value")

    def fkOutgoingImage = foreignKey("fk_outgoing_image_id", outgoingImageId, outgoingImageQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (id, outgoingImageId, dicomTag, value) <>(toOutgoingTagValue.tupled, fromOutgoingTagValue)
  }

  val outgoingTagValueQuery = TableQuery[OutgoingTagValueTable]

  class IncomingTransactionTable(tag: Tag) extends Table[IncomingTransaction](tag, "IncomingTransactions") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def boxId = column[Long]("boxid")

    def boxName = column[String]("boxname")

    def outgoingTransactionId = column[Long]("outgoingtransactionid")

    def receivedImageCount = column[Long]("receivedimagecount")

    def addedImageCount = column[Long]("addedimagecount")

    def totalImageCount = column[Long]("totalimagecount")

    def created = column[Long]("created")

    def updated = column[Long]("updated")

    def status = column[TransactionStatus]("status")

    def * = (id, boxId, boxName, outgoingTransactionId, receivedImageCount, addedImageCount, totalImageCount, created, updated, status) <>(IncomingTransaction.tupled, IncomingTransaction.unapply)
  }

  val incomingTransactionQuery = TableQuery[IncomingTransactionTable]

  class IncomingImageTable(tag: Tag) extends Table[IncomingImage](tag, "IncomingImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def incomingTransactionId = column[Long]("incomingtransactionid")

    def imageId = column[Long]("imageid")

    def sequenceNumber = column[Long]("sequencenumber")

    def overwrite = column[Boolean]("overwrite")

    def fkIncomingTransaction = foreignKey("fk_incoming_transaction_id", incomingTransactionId, incomingTransactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def idxUniqueTransactionAndNumber = index("idx_unique_incoming_image", (incomingTransactionId, sequenceNumber), unique = true)

    def * = (id, incomingTransactionId, imageId, sequenceNumber, overwrite) <>(IncomingImage.tupled, IncomingImage.unapply)
  }

  val incomingImageQuery = TableQuery[IncomingImageTable]

  def columnExists(tableName: String, columnName: String)(implicit session: Session): Boolean = {
    val tables = MTable.getTables(tableName).list
    if (tables.isEmpty)
      false
    else
      tables.head.getColumns.list.exists(_.name == columnName)
  }

  def create(implicit session: Session): Unit = {
    if (MTable.getTables("Boxes").list.isEmpty) boxQuery.ddl.create
    if (MTable.getTables("OutgoingTransactions").list.isEmpty) outgoingTransactionQuery.ddl.create
    if (MTable.getTables("OutgoingImages").list.isEmpty) outgoingImageQuery.ddl.create
    if (MTable.getTables("OutgoingTagValues").list.isEmpty) outgoingTagValueQuery.ddl.create
    if (MTable.getTables("IncomingTransactions").list.isEmpty) incomingTransactionQuery.ddl.create
    if (MTable.getTables("IncomingImages").list.isEmpty) incomingImageQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (boxQuery.ddl ++ outgoingTransactionQuery.ddl ++ incomingTransactionQuery.ddl ++ outgoingImageQuery.ddl ++ outgoingTagValueQuery.ddl ++ incomingImageQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    boxQuery.delete
    outgoingTransactionQuery.delete // cascade deletes images and tag values
    incomingTransactionQuery.delete // cascade deletes images
  }

  def listOutgoingTagValues(implicit session: Session): List[OutgoingTagValue] =
    outgoingTagValueQuery.list

  def insertOutgoingTagValue(tagValue: OutgoingTagValue)(implicit session: Session): OutgoingTagValue = {
    val generatedId = (outgoingTagValueQuery returning outgoingTagValueQuery.map(_.id)) += tagValue
    tagValue.copy(id = generatedId)
  }

  def tagValuesByOutgoingTransactionImage(outgoingTransactionId: Long, outgoingImageId: Long)(implicit session: Session): List[OutgoingTagValue] = {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
      tagValue <- outgoingTagValueQuery if image.id === tagValue.outgoingImageId
    } yield (transaction, image, tagValue)
    join
      .filter(_._1.id === outgoingTransactionId)
      .filter(_._2.id === outgoingImageId)
      .map(_._3)
      .list
  }

  def insertBox(box: Box)(implicit session: Session): Box = {
    val generatedId = (boxQuery returning boxQuery.map(_.id)) += box
    box.copy(id = generatedId)
  }

  def insertOutgoingTransaction(transaction: OutgoingTransaction)(implicit session: Session): OutgoingTransaction = {
    val generatedId = (outgoingTransactionQuery returning outgoingTransactionQuery.map(_.id)) += transaction
    transaction.copy(id = generatedId)
  }

  def insertIncomingTransaction(transaction: IncomingTransaction)(implicit session: Session): IncomingTransaction = {
    val generatedId = (incomingTransactionQuery returning incomingTransactionQuery.map(_.id)) += transaction
    transaction.copy(id = generatedId)
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

  def updateIncomingTransaction(transaction: IncomingTransaction)(implicit session: Session): Unit =
    incomingTransactionQuery.filter(_.id === transaction.id).update(transaction)

  def updateOutgoingTransaction(transaction: OutgoingTransaction)(implicit session: Session): Unit =
    outgoingTransactionQuery.filter(_.id === transaction.id).update(transaction)

  def updateOutgoingImage(image: OutgoingImage)(implicit session: Session): Unit =
    outgoingImageQuery.filter(_.id === image.id).update(image)

  def updateIncomingImage(image: IncomingImage)(implicit session: Session): Unit =
    incomingImageQuery.filter(_.id === image.id).update(image)

  def nextOutgoingTransactionImageForBoxId(boxId: Long)(implicit session: Session): Option[OutgoingTransactionImage] = {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
    } yield (transaction, image)
    join
      .filter(_._1.boxId === boxId)
      .filterNot(_._1.status === (FAILED: TransactionStatus))
      .filterNot(_._1.status === (FINISHED: TransactionStatus))
      .filter(_._2.sent === false)
      .sortBy(_._2.sequenceNumber.asc)
      .firstOption
      .map(OutgoingTransactionImage.tupled)
  }

  def outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(boxId: Long, outgoingTransactionId: Long, outgoingImageId: Long)(implicit session: Session): Option[OutgoingTransactionImage] = {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
    } yield (transaction, image)
    join
      .filter(_._1.boxId === boxId)
      .filter(_._1.id === outgoingTransactionId)
      .filter(_._2.id === outgoingImageId)
      .firstOption
      .map(OutgoingTransactionImage.tupled)
  }

  def setOutgoingTransactionStatus(outgoingTransactionId: Long, status: TransactionStatus)(implicit session: Session): Unit =
    outgoingTransactionQuery
      .filter(_.id === outgoingTransactionId)
      .map(_.status)
      .update(status)

  def setIncomingTransactionStatus(incomingTransactionId: Long, status: TransactionStatus)(implicit session: Session): Unit =
    incomingTransactionQuery
      .filter(_.id === incomingTransactionId)
      .map(_.status)
      .update(status)

  def incomingTransactionByOutgoingTransactionId(boxId: Long, outgoingTransactionId: Long)(implicit session: Session): Option[IncomingTransaction] =
    incomingTransactionQuery
      .filter(_.boxId === boxId)
      .filter(_.outgoingTransactionId === outgoingTransactionId)
      .firstOption

  def incomingImageByIncomingTransactionIdAndSequenceNumber(incomingTransactionId: Long, sequenceNumber: Long)(implicit session: Session): Option[IncomingImage] =
    incomingImageQuery
      .filter(_.incomingTransactionId === incomingTransactionId)
      .filter(_.sequenceNumber === sequenceNumber)
      .firstOption

  def outgoingTransactionById(outgoingTransactionId: Long)(implicit session: Session): Option[OutgoingTransaction] =
    outgoingTransactionQuery
      .filter(_.id === outgoingTransactionId)
      .firstOption

  def removeIncomingTransaction(incomingTransactionId: Long)(implicit session: Session): Unit =
    incomingTransactionQuery.filter(_.id === incomingTransactionId).delete

  def removeBox(boxId: Long)(implicit session: Session): Unit =
    boxQuery.filter(_.id === boxId).delete

  def removeOutgoingTransaction(outgoingTransactionId: Long)(implicit session: Session): Unit =
    outgoingTransactionQuery.filter(_.id === outgoingTransactionId).delete

  def removeOutgoingTransactions(outgoingTransactionIds: Seq[Long])(implicit session: Session): Unit =
    outgoingTransactionQuery.filter(_.id inSet outgoingTransactionIds).delete

  def listBoxes(startIndex: Long, count: Long)(implicit session: Session): List[Box] =
    boxQuery.drop(startIndex).take(count).list

  def listOutgoingTransactions(startIndex: Long, count: Long)(implicit session: Session): List[OutgoingTransaction] =
    outgoingTransactionQuery
      .drop(startIndex)
      .take(count)
      .sortBy(_.updated.desc)
      .list

  def listOutgoingImages(implicit session: Session): List[OutgoingImage] =
    outgoingImageQuery.list

  def listIncomingTransactions(startIndex: Long, count: Long)(implicit session: Session): List[IncomingTransaction] =
    incomingTransactionQuery
      .drop(startIndex)
      .take(count)
      .sortBy(_.updated.desc)
      .list

  def listIncomingImages(implicit session: Session): List[IncomingImage] =
    incomingImageQuery
      .list

  def listOutgoingTransactionsInProcess(implicit session: Session): List[OutgoingTransaction] =
    outgoingTransactionQuery
      .filter(_.status === (PROCESSING: TransactionStatus))
      .list

  def listIncomingTransactionsInProcess(implicit session: Session): List[IncomingTransaction] =
    incomingTransactionQuery
      .filter(_.status === (PROCESSING: TransactionStatus))
      .list

  def countOutgoingImagesForOutgoingTransactionId(outgoingTransactionId: Long)(implicit session: Session): Int =
    outgoingImageQuery.filter(_.outgoingTransactionId === outgoingTransactionId).length.run

  def listOutgoingImagesForOutgoingTransactionId(outgoingTransactionId: Long)(implicit session: Session): List[OutgoingImage] =
    outgoingImageQuery.filter(_.outgoingTransactionId === outgoingTransactionId).list

  def countIncomingImagesForIncomingTransactionId(incomingTransactionId: Long)(implicit session: Session): Int =
    incomingImageQuery.filter(_.incomingTransactionId === incomingTransactionId).length.run

  def listIncomingImagesForIncomingTransactionId(incomingTransactionId: Long)(implicit session: Session): List[IncomingImage] =
    incomingImageQuery.filter(_.incomingTransactionId === incomingTransactionId).list

  def outgoingImagesByOutgoingTransactionId(outgoingTransactionId: Long)(implicit session: Session): List[OutgoingImage] = {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
    } yield (transaction, image)
    join.filter(_._1.id === outgoingTransactionId).map(_._2).list
  }

  def removeOutgoingImagesForImageId(imageId: Long)(implicit session: Session) =
    outgoingImageQuery.filter(_.imageId === imageId).delete

  def removeIncomingImagesForImageId(imageId: Long)(implicit session: Session) =
    incomingImageQuery.filter(_.imageId === imageId).delete

}

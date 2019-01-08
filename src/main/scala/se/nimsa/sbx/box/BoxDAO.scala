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

package se.nimsa.sbx.box

import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{ImageTagValues, TagValue}
import se.nimsa.sbx.box.BoxProtocol.BoxSendMethod._
import se.nimsa.sbx.box.BoxProtocol.TransactionStatus._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class BoxDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  implicit val statusColumnType: BaseColumnType[TransactionStatus] =
    MappedColumnType.base[TransactionStatus, String](ts => ts.toString, TransactionStatus.withName)

  implicit val sendMethodColumnType: BaseColumnType[BoxSendMethod] =
    MappedColumnType.base[BoxSendMethod, String](bsm => bsm.toString, BoxSendMethod.withName)

  implicit val defaultProfileColumnType: BaseColumnType[AnonymizationProfile] =
    MappedColumnType.base[AnonymizationProfile, String](
      p => p.options.map(_.name).mkString(","),
      s => AnonymizationProfile(s.split(",").map(ConfidentialityOption.withName))
    )

  class BoxTable(tag: Tag) extends Table[Box](tag, BoxTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name", O.Length(180))
    def token = column[String]("token")
    def baseUrl = column[String]("baseurl")
    def sendMethod = column[BoxSendMethod]("sendmethod")
    def defaultProfile = column[AnonymizationProfile]("defaultprofile")
    def online = column[Boolean]("online")
    def idxUniqueName = index("idx_unique_box_name", name, unique = true)
    def * = (id, name, token, baseUrl, sendMethod, defaultProfile, online) <> (Box.tupled, Box.unapply)
  }

  object BoxTable {
    val name = "Boxes"
  }

  val boxQuery = TableQuery[BoxTable]

  class OutgoingTransactionTable(tag: Tag) extends Table[OutgoingTransaction](tag, OutgoingTransactionTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def boxId = column[Long]("boxid")
    def boxName = column[String]("boxname")
    def profile = column[AnonymizationProfile]("profile")
    def sentImageCount = column[Long]("sentimagecount")
    def totalImageCount = column[Long]("totalimagecount")
    def created = column[Long]("created")
    def updated = column[Long]("updated")
    def status = column[TransactionStatus]("status")
    def idxBoxId = index("idx_box_id", boxId)
    def * = (id, boxId, boxName, profile, sentImageCount, totalImageCount, created, updated, status) <> (OutgoingTransaction.tupled, OutgoingTransaction.unapply)
  }

  object OutgoingTransactionTable {
    val name = "OutgoingTransactions"
  }

  val outgoingTransactionQuery = TableQuery[OutgoingTransactionTable]

  class OutgoingImageTable(tag: Tag) extends Table[OutgoingImage](tag, OutgoingImageTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def outgoingTransactionId = column[Long]("outgoingtransactionid")
    def imageId = column[Long]("imageid")
    def sequenceNumber = column[Long]("sequencenumber")
    def sent = column[Boolean]("sent")
    def fkOutgoingTransaction = foreignKey("fk_outgoing_transaction_id", outgoingTransactionId, outgoingTransactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def idxUniqueTransactionAndNumber = index("idx_unique_outgoing_image", (outgoingTransactionId, sequenceNumber), unique = true)
    def idxImageId = index("idx_outgoing_image_image_id", imageId)
    def * = (id, outgoingTransactionId, imageId, sequenceNumber, sent) <> (OutgoingImage.tupled, OutgoingImage.unapply)
  }

  object OutgoingImageTable {
    val name = "OutgoingImages"
  }

  val outgoingImageQuery = TableQuery[OutgoingImageTable]

  val toOutgoingTagValue: (Long, Long, String, String) => OutgoingTagValue =
    (id: Long, outgoingImageId: Long, tagPath: String, value: String) => OutgoingTagValue(id, outgoingImageId, TagValue(TagPathTag.parse(tagPath), value))
  val fromOutgoingTagValue: OutgoingTagValue => Option[(Long, Long, String, String)] =
    (tagValue: OutgoingTagValue) => Option((tagValue.id, tagValue.outgoingImageId, tagValue.tagValue.tagPath.toString, tagValue.tagValue.value))

  class OutgoingTagValueTable(tag: Tag) extends Table[OutgoingTagValue](tag, OutgoingTagValueTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def outgoingImageId = column[Long]("outgoingimageid")
    def dicomTag = column[String]("tagPath")
    def value = column[String]("value")
    def fkOutgoingImage = foreignKey("fk_outgoing_image_id", outgoingImageId, outgoingImageQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, outgoingImageId, dicomTag, value) <> (toOutgoingTagValue.tupled, fromOutgoingTagValue)
  }

  object OutgoingTagValueTable {
    val name = "OutgoingTagValues"
  }

  val outgoingTagValueQuery = TableQuery[OutgoingTagValueTable]

  class IncomingTransactionTable(tag: Tag) extends Table[IncomingTransaction](tag, IncomingTransactionTable.name) {
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
    def idxIncomingTransactionBoxId = index("idx_incoming_transaction_box_id", boxId)
    def * = (id, boxId, boxName, outgoingTransactionId, receivedImageCount, addedImageCount, totalImageCount, created, updated, status) <> (IncomingTransaction.tupled, IncomingTransaction.unapply)
  }

  object IncomingTransactionTable {
    val name = "IncomingTransactions"
  }

  val incomingTransactionQuery = TableQuery[IncomingTransactionTable]

  class IncomingImageTable(tag: Tag) extends Table[IncomingImage](tag, IncomingImageTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def incomingTransactionId = column[Long]("incomingtransactionid")
    def imageId = column[Long]("imageid")
    def sequenceNumber = column[Long]("sequencenumber")
    def overwrite = column[Boolean]("overwrite")
    def fkIncomingTransaction = foreignKey("fk_incoming_transaction_id", incomingTransactionId, incomingTransactionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def idxUniqueTransactionAndNumber = index("idx_unique_incoming_image", (incomingTransactionId, sequenceNumber), unique = true)
    def idxIncomingTransactionId = index("idx_incoming_image_image_id", imageId)
    def * = (id, incomingTransactionId, imageId, sequenceNumber, overwrite) <> (IncomingImage.tupled, IncomingImage.unapply)
  }

  object IncomingImageTable {
    val name = "IncomingImages"
  }

  val incomingImageQuery = TableQuery[IncomingImageTable]

  def create(): Future[Unit] = createTables(dbConf,
    (BoxTable.name, boxQuery),
    (OutgoingTransactionTable.name, outgoingTransactionQuery),
    (OutgoingImageTable.name, outgoingImageQuery),
    (OutgoingTagValueTable.name, outgoingTagValueQuery),
    (IncomingTransactionTable.name, incomingTransactionQuery),
    (IncomingImageTable.name, incomingImageQuery))

  def drop(): Future[Unit] = db.run {
    (boxQuery.schema ++ outgoingTransactionQuery.schema ++ incomingTransactionQuery.schema ++ outgoingImageQuery.schema ++ outgoingTagValueQuery.schema ++ incomingImageQuery.schema).drop
  }

  def clear(): Future[Unit] = db.run {
    DBIO.seq(
      boxQuery.delete,
      outgoingTransactionQuery.delete, // cascade deletes images and tag values
      incomingTransactionQuery.delete) // cascade deletes images
  }

  def listOutgoingTagValues: Future[Seq[OutgoingTagValue]] = db.run {
    outgoingTagValueQuery.result
  }

  def insertOutgoingTagValueAction(tagValue: OutgoingTagValue): DBIOAction[OutgoingTagValue, NoStream, Effect.Write] =
    (outgoingTagValueQuery returning outgoingTagValueQuery.map(_.id) += tagValue)
      .map(generatedId => tagValue.copy(id = generatedId))

  def insertOutgoingTagValue(tagValue: OutgoingTagValue): Future[OutgoingTagValue] = db.run(insertOutgoingTagValueAction(tagValue))

  def tagValuesByOutgoingTransactionImage(outgoingTransactionId: Long, outgoingImageId: Long): Future[Seq[OutgoingTagValue]] = db.run {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
      tagValue <- outgoingTagValueQuery if image.id === tagValue.outgoingImageId
    } yield (transaction, image, tagValue)
    join
      .filter(_._1.id === outgoingTransactionId)
      .filter(_._2.id === outgoingImageId)
      .map(_._3)
      .result
  }

  def insertBox(box: Box): Future[Box] = db.run {
    (boxQuery returning boxQuery.map(_.id) += box)
      .map(generatedId => box.copy(id = generatedId))
  }

  def insertOutgoingTransactionAction(transaction: OutgoingTransaction): DBIOAction[OutgoingTransaction, NoStream, Effect.Write] =
    (outgoingTransactionQuery returning outgoingTransactionQuery.map(_.id) += transaction)
      .map(generatedId => transaction.copy(id = generatedId))

  def insertOutgoingTransaction(transaction: OutgoingTransaction): Future[OutgoingTransaction] =
    db.run(insertOutgoingTransactionAction(transaction))

  def insertIncomingTransactionAction(transaction: IncomingTransaction): DBIOAction[IncomingTransaction, NoStream, Effect.Write] =
    (incomingTransactionQuery returning incomingTransactionQuery.map(_.id) += transaction)
      .map(generatedId => transaction.copy(id = generatedId))

  def insertIncomingTransaction(transaction: IncomingTransaction): Future[IncomingTransaction] =
    db.run(insertIncomingTransactionAction(transaction))

  def insertOutgoingImageAction(outgoingImage: OutgoingImage): DBIOAction[OutgoingImage, NoStream, Effect.Write] =
    (outgoingImageQuery returning outgoingImageQuery.map(_.id) += outgoingImage)
      .map(generatedId => outgoingImage.copy(id = generatedId))

  def insertOutgoingImage(outgoingImage: OutgoingImage): Future[OutgoingImage] = db.run(insertOutgoingImageAction(outgoingImage))

  def insertIncomingImageAction(incomingImage: IncomingImage): DBIOAction[IncomingImage, NoStream, Effect.Write] =
    (incomingImageQuery returning incomingImageQuery.map(_.id) += incomingImage)
      .map(generatedId => incomingImage.copy(id = generatedId))

  def insertIncomingImage(incomingImage: IncomingImage): Future[IncomingImage] = db.run(insertIncomingImageAction(incomingImage))

  def boxById(boxId: Long): Future[Option[Box]] = db.run {
    boxQuery.filter(_.id === boxId).result.headOption
  }

  def boxByName(name: String): Future[Option[Box]] = db.run {
    boxQuery.filter(_.name === name).result.headOption
  }

  def pollBoxByToken(token: String): Future[Option[Box]] = db.run {
    boxQuery
      .filter(_.sendMethod === (POLL: BoxSendMethod))
      .filter(_.token === token)
      .result.headOption
  }

  def updateBoxOnlineStatusAction(boxId: Long, online: Boolean): DBIOAction[Unit, NoStream, Effect.Write] =
    boxQuery
      .filter(_.id === boxId)
      .map(_.online)
      .update(online)
      .map(_ => {})

  def updateBoxOnlineStatus(boxId: Long, online: Boolean): Future[Unit] = db.run(updateBoxOnlineStatusAction(boxId, online))

  def updateOutgoingTransactionAction(transaction: OutgoingTransaction): DBIOAction[Unit, NoStream, Effect.Write] =
    outgoingTransactionQuery.filter(_.id === transaction.id).update(transaction).map(_ => {})

  def updateOutgoingTransaction(transaction: OutgoingTransaction): Future[Unit] =
    db.run(updateOutgoingTransactionAction(transaction))

  def updateIncomingTransactionAction(transaction: IncomingTransaction): DBIOAction[Unit, NoStream, Effect.Write] =
    incomingTransactionQuery.filter(_.id === transaction.id).update(transaction).map(_ => {})

  def updateIncomingTransaction(transaction: IncomingTransaction): Future[Unit] =
    db.run(updateIncomingTransactionAction(transaction))

  def updateOutgoingTransactionImageAction(transaction: OutgoingTransaction): DBIOAction[Unit, NoStream, Effect.Write] =
    outgoingTransactionQuery.filter(_.id === transaction.id).update(transaction).map(_ => {})

  def updateOutgoingImageAction(image: OutgoingImage): DBIOAction[Unit, NoStream, Effect.Write] =
    outgoingImageQuery.filter(_.id === image.id).update(image).map(_ => {})

  def updateIncomingImageAction(image: IncomingImage): DBIOAction[Unit, NoStream, Effect.Write] =
    incomingImageQuery.filter(_.id === image.id).update(image).map(_ => {})

  def nextOutgoingTransactionImagesForBoxId(boxId: Long, n: Long): Future[Seq[OutgoingTransactionImage]] = db.run {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
    } yield (transaction, image)
    join
      .filter(_._1.boxId === boxId)
      .filterNot(_._1.status === (FAILED: TransactionStatus))
      .filter(_._2.sent === false)
      .sortBy(t => (t._1.created.asc, t._2.sequenceNumber.asc))
      .take(n)
      .result
      .map(_.map(OutgoingTransactionImage.tupled))
  }

  def outgoingTransactionByTransactionId(boxId: Long, outgoingTransactionId: Long): Future[Option[OutgoingTransaction]] = db.run {
    outgoingTransactionQuery
      .filter(_.id === outgoingTransactionId)
      .filter(_.boxId === boxId)
      .result.headOption
  }

  def outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(boxId: Long, outgoingTransactionId: Long, outgoingImageId: Long): Future[Option[OutgoingTransactionImage]] = db.run {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
    } yield (transaction, image)
    join
      .filter(_._1.id === outgoingTransactionId)
      .filter(_._1.boxId === boxId)
      .filter(_._2.id === outgoingImageId)
      .result.headOption
      .map(_.map(OutgoingTransactionImage.tupled))
  }

  def setOutgoingTransactionStatusAction(outgoingTransactionId: Long, status: TransactionStatus): DBIOAction[Unit, NoStream, Effect.Write] =
    outgoingTransactionQuery
      .filter(_.id === outgoingTransactionId)
      .map(_.status)
      .update(status)
      .map(_ => {})

  def setOutgoingTransactionStatus(outgoingTransactionId: Long, status: TransactionStatus): Future[Unit] =
    db.run(setOutgoingTransactionStatusAction(outgoingTransactionId, status))

  def setIncomingTransactionStatusAction(incomingTransactionId: Long, status: TransactionStatus): DBIOAction[Unit, NoStream, Effect.Write] =
    incomingTransactionQuery
      .filter(_.id === incomingTransactionId)
      .map(_.status)
      .update(status)
      .map(_ => {})

  def incomingTransactionByOutgoingTransactionIdAction(boxId: Long, outgoingTransactionId: Long): DBIOAction[Option[IncomingTransaction], NoStream, Effect.Read] =
    incomingTransactionQuery
      .filter(_.boxId === boxId)
      .filter(_.outgoingTransactionId === outgoingTransactionId)
      .result.headOption

  def incomingTransactionByOutgoingTransactionId(boxId: Long, outgoingTransactionId: Long): Future[Option[IncomingTransaction]] =
    db.run(incomingTransactionByOutgoingTransactionIdAction(boxId, outgoingTransactionId))

  def incomingImageByIncomingTransactionIdAndSequenceNumberAction(incomingTransactionId: Long, sequenceNumber: Long): DBIOAction[Option[IncomingImage], NoStream, Effect.Read] =
    incomingImageQuery
      .filter(_.incomingTransactionId === incomingTransactionId)
      .filter(_.sequenceNumber === sequenceNumber)
      .result.headOption

  def removeIncomingTransaction(incomingTransactionId: Long): Future[Unit] = db.run {
    incomingTransactionQuery.filter(_.id === incomingTransactionId).delete
      .map(_ => {})
  }

  def removeBox(boxId: Long): Future[Unit] = db.run {
    boxQuery.filter(_.id === boxId).delete
      .map(_ => {})
  }

  def removeOutgoingTransaction(outgoingTransactionId: Long): Future[Unit] = db.run {
    outgoingTransactionQuery.filter(_.id === outgoingTransactionId).delete
      .map(_ => {})
  }

  def listBoxes(startIndex: Long, count: Long): Future[Seq[Box]] = db.run {
    boxQuery.drop(startIndex).take(count).result
  }

  def listPendingOutgoingTransactionsForBox(boxId: Long): Future[Seq[OutgoingTransaction]] = db.run {
    outgoingTransactionQuery
      .filter(_.boxId === boxId)
      .filter(_.status === (WAITING: TransactionStatus))
      .result
  }

  def listOutgoingTransactions(startIndex: Long, count: Long): Future[Seq[OutgoingTransaction]] = db.run {
    outgoingTransactionQuery
      .sortBy(_.updated.desc)
      .drop(startIndex)
      .take(count)
      .result
  }

  def listOutgoingImages: Future[Seq[OutgoingImage]] = db.run {
    outgoingImageQuery.result
  }

  def listIncomingTransactions(startIndex: Long, count: Long): Future[Seq[IncomingTransaction]] = db.run {
    incomingTransactionQuery
      .sortBy(_.updated.desc)
      .drop(startIndex)
      .take(count)
      .result
  }

  def listIncomingImages: Future[Seq[IncomingImage]] = db.run {
    incomingImageQuery
      .result
  }

  def listOutgoingTransactionsInProcessAction: DBIOAction[Seq[OutgoingTransaction], NoStream, Effect.Read] =
    outgoingTransactionQuery
      .filter(_.status === (PROCESSING: TransactionStatus))
      .result

  val listIncomingTransactionsInProcessAction: DBIOAction[Seq[IncomingTransaction], NoStream, Effect.Read] =
    incomingTransactionQuery
      .filter(_.status === (PROCESSING: TransactionStatus))
      .result

  def listOutgoingImagesForOutgoingTransactionId(outgoingTransactionId: Long): Future[Seq[OutgoingImage]] = db.run {
    outgoingImageQuery.filter(_.outgoingTransactionId === outgoingTransactionId).result
  }

  def listIncomingImagesForIncomingTransactionId(incomingTransactionId: Long): Future[Seq[IncomingImage]] = db.run {
    incomingImageQuery.filter(_.incomingTransactionId === incomingTransactionId).result
  }

  def outgoingImagesByOutgoingTransactionId(outgoingTransactionId: Long): Future[Seq[OutgoingImage]] = db.run {
    val join = for {
      transaction <- outgoingTransactionQuery
      image <- outgoingImageQuery if transaction.id === image.outgoingTransactionId
    } yield (transaction, image)
    join.filter(_._1.id === outgoingTransactionId).map(_._2).result
  }

  def removeOutgoingImagesForImageIds(imageIds: Seq[Long]): Future[Int] = db.run {
    outgoingImageQuery.filter(_.imageId inSetBind imageIds).delete
  }

  def removeIncomingImagesForImageIds(imageIds: Seq[Long]): Future[Int] = db.run {
    incomingImageQuery.filter(_.imageId inSetBind imageIds).delete
  }

  def updateTransactionsStatusAction(now: Long, pollBoxOnlineStatusTimeoutMillis: Long): DBIOAction[Unit, NoStream, Effect.Read with Effect.Write] = {
    val inToWaitingAction =
      listIncomingTransactionsInProcessAction.flatMap { transactions =>
        DBIO.sequence {
          transactions.map { transaction =>
            if ((now - transaction.updated) > pollBoxOnlineStatusTimeoutMillis)
              setIncomingTransactionStatusAction(transaction.id, TransactionStatus.WAITING)
            else
              DBIO.successful(Unit)
          }
        }
      }

    val outToWaitingAction =
      listOutgoingTransactionsInProcessAction.flatMap { transactions =>
        DBIO.sequence {
          transactions.map { transaction =>
            if ((now - transaction.updated) > pollBoxOnlineStatusTimeoutMillis)
              setOutgoingTransactionStatusAction(transaction.id, TransactionStatus.WAITING)
            else
              DBIO.successful(Unit)
          }
        }
      }

    DBIO.seq(inToWaitingAction, outToWaitingAction).map(_ => {})
  }

  def updatePollBoxesOnlineStatusAction(now: Long, pollBoxesLastPollTimestamp: Map[Long, Long], pollBoxOnlineStatusTimeoutMillis: Long): DBIOAction[Seq[Unit], NoStream, Effect.Write] = {
    DBIO.sequence {
      pollBoxesLastPollTimestamp.map {
        case (boxId, lastPollTime) =>
          val online = (now - lastPollTime) < pollBoxOnlineStatusTimeoutMillis
          updateBoxOnlineStatusAction(boxId, online)
      }.toSeq
    }
  }

  def updateStatusForBoxesAndTransactions(now: Long, pollBoxesLastPollTimestamp: Map[Long, Long], pollBoxOnlineStatusTimeoutMillis: Long): Future[Unit] =
    db.run {
      DBIO.seq(
        updatePollBoxesOnlineStatusAction(now, pollBoxesLastPollTimestamp, pollBoxOnlineStatusTimeoutMillis),
        updateTransactionsStatusAction(now, pollBoxOnlineStatusTimeoutMillis))
        .transactionally
    }

  def updateOutgoingTransactionImage(updatedTransaction: OutgoingTransaction, updatedImage: OutgoingImage): Future[Unit] = {
    val action = updateOutgoingTransactionImageAction(updatedTransaction).flatMap(_ => updateOutgoingImageAction(updatedImage))
    db.run(action.transactionally)
  }

  def updateIncomingAction(box: Box, outgoingTransactionId: Long, sequenceNumber: Long, totalImageCount: Long, added: Boolean): DBIOAction[IncomingTransaction, NoStream, Effect.Read with Effect.Write with Effect.Write] = {
    incomingTransactionByOutgoingTransactionIdAction(box.id, outgoingTransactionId)
      .flatMap { incomingTransactionMaybe =>
        incomingTransactionMaybe
          .map(DBIO.successful)
          .getOrElse(insertIncomingTransactionAction(IncomingTransaction(-1, box.id, box.name, outgoingTransactionId, 0, 0, totalImageCount, System.currentTimeMillis, System.currentTimeMillis, TransactionStatus.WAITING)))
      }
      .flatMap { existingTransaction =>
        val receivedImageCount = math.min(totalImageCount, existingTransaction.receivedImageCount + 1)
        val addedImageCount = if (added) math.min(totalImageCount, existingTransaction.addedImageCount + 1) else existingTransaction.addedImageCount
        val incomingTransaction = existingTransaction.copy(
          receivedImageCount = receivedImageCount,
          addedImageCount = addedImageCount,
          totalImageCount = totalImageCount,
          updated = System.currentTimeMillis,
          status = TransactionStatus.PROCESSING)
        updateIncomingTransactionAction(incomingTransaction)
          .map(_ => incomingTransaction)
      }
  }

  def maybeFinalizeIncomingAction(incomingTransaction: IncomingTransaction): DBIOAction[IncomingTransaction, NoStream, Effect.Write] =
    if (incomingTransaction.receivedImageCount >= incomingTransaction.totalImageCount)
      setIncomingTransactionStatusAction(incomingTransaction.id, TransactionStatus.FINISHED)
        .map(_ => incomingTransaction.copy(status = TransactionStatus.FINISHED))
    else
      DBIO.successful(incomingTransaction)

  def updateIncoming(box: Box, outgoingTransactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageIdMaybe: Option[Long], added: Boolean): Future[IncomingTransaction] = {
    val action =
      updateIncomingAction(box, outgoingTransactionId, sequenceNumber, totalImageCount, added)
        .flatMap(incomingTransaction => imageIdMaybe
          .map(imageId => incomingImageByIncomingTransactionIdAndSequenceNumberAction(incomingTransaction.id, sequenceNumber)
            .flatMap(imageMaybe => imageMaybe
              .map(image => updateIncomingImageAction(image.copy(imageId = imageId)))
              .getOrElse(insertIncomingImageAction(IncomingImage(-1, incomingTransaction.id, imageId, sequenceNumber, !added)))))
          .getOrElse(DBIO.successful(Unit))
          .flatMap(_ => maybeFinalizeIncomingAction(incomingTransaction)))
    db.run(action.transactionally)
  }

  def addImagesToOutgoing(boxId: Long, boxName: String, profile: AnonymizationProfile, imageTagValuesSeq: Seq[ImageTagValues]): Future[OutgoingTransaction] = {
    val action =
      insertOutgoingTransactionAction(OutgoingTransaction(-1, boxId, boxName, profile, 0, imageTagValuesSeq.length, System.currentTimeMillis, System.currentTimeMillis, TransactionStatus.WAITING))
        .flatMap { outgoingTransaction =>
          DBIO.sequence {
            imageTagValuesSeq.zipWithIndex
              .map {
                case (imageTagValues, index) =>
                  val sequenceNumber = index + 1
                  insertOutgoingImageAction(OutgoingImage(-1, outgoingTransaction.id, imageTagValues.imageId, sequenceNumber, sent = false))
                    .flatMap { outgoingImage =>
                      DBIO.sequence {
                        imageTagValues.tagValues
                          .map { tagValue =>
                            insertOutgoingTagValueAction(OutgoingTagValue(-1, outgoingImage.id, tagValue))
                          }
                      }
                    }
              }
          }.map(_ => outgoingTransaction)
        }
    db.run(action.transactionally)
  }

}


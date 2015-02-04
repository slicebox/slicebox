package se.vgregion.box

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import BoxProtocol._

class BoxDAO(val driver: JdbcProfile) {
  import driver.simple._

  val toBox = (id: Long, name: String, token: String, baseUrl: String, sendMethod: String) => Box(id, name, token, baseUrl, BoxSendMethod.withName(sendMethod))
  val fromBox = (box: Box) => Option((box.id, box.name, box.token, box.baseUrl, box.sendMethod.toString))

  class BoxTable(tag: Tag) extends Table[Box](tag, "Box") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def token = column[String]("token")
    def baseUrl = column[String]("baseurl")
    def sendMethod = column[String]("sendmethod")
    def idxUniqueNAme = index("idx_unique_name", name, unique = true)
    def * = (id, name, token, baseUrl, sendMethod) <> (toBox.tupled, fromBox)
  }

  val boxQuery = TableQuery[BoxTable]

  val toOutboxEntry = (id: Long, remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, failed: Boolean) =>
    OutboxEntry(id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageId, failed)
  val fromOutboxEntry = (entry: OutboxEntry) => Option((entry.id, entry.remoteBoxId, entry.transactionId, entry.sequenceNumber, entry.totalImageCount, entry.imageId, entry.failed))

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
  
  def create(implicit session: Session): Unit =
    if (MTable.getTables("Box").list.isEmpty) {
      (boxQuery.ddl ++ outboxQuery.ddl ++ inboxQuery.ddl).create
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

  def pushBoxByBaseUrl(baseUrl: String)(implicit session: Session): Option[Box] =
    boxQuery
      .filter(_.sendMethod === BoxSendMethod.PUSH.toString)
      .filter(_.baseUrl === baseUrl)
      .list.headOption

  def updateInboxEntry(entry: InboxEntry)(implicit session: Session): Unit = 
    inboxQuery.filter(_.id === entry.id).update(entry)

  def nextOutboxEntryForRemoteBoxId(remoteBoxId: Long)(implicit session: Session): Option[OutboxEntry] = 
    outboxQuery
    .filter(_.remoteBoxId === remoteBoxId)
    .filter(_.failed === false)
    .sortBy(_.sequenceNumber.asc)
    .list.headOption
    
  def markOutboxTransactionAsFailed(transactionId: Long)(implicit session: Session): Unit =
    outboxQuery
      .filter(_.transactionId === transactionId)
      .map(_.failed)
      .update(true)
    
  def removeBox(boxId: Long)(implicit session: Session): Unit =
    boxQuery.filter(_.id === boxId).delete

  def removeOutboxEntry(entryId: Long)(implicit session: Session): Unit =
    outboxQuery.filter(_.id === entryId).delete

  def listBoxes(implicit session: Session): List[Box] =
    boxQuery.list

  def listOutboxEntries(implicit session: Session): List[OutboxEntry] =
    outboxQuery.list

  def listInboxEntries(implicit session: Session): List[InboxEntry] =
    inboxQuery.list

}
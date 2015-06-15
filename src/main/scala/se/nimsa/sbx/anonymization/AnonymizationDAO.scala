package se.nimsa.sbx.anonymization

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import AnonymizationProtocol._

class AnonymizationDAO(val driver: JdbcProfile) {
  import driver.simple._
  
  val toTransactionTagValue = (id: Long, imageFileId: Long, transactionId: Long, tag: Int, value: String) =>
    TransactionTagValue(id, imageFileId, transactionId, tag, value)
  val fromTransactionTagValue = (entry: TransactionTagValue) => Option((entry.id, entry.imageFileId, entry.transactionId, entry.tag, entry.value))

  class TransactionTagValueTable(tag: Tag) extends Table[TransactionTagValue](tag, "TransactionTagValue") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def imageFileId = column[Long]("imagefileid")
    def transactionId = column[Long]("transactionid")
    def dicomTag = column[Int]("tag")
    def value = column[String]("value")
    def * = (id, imageFileId, transactionId, dicomTag, value) <> (toTransactionTagValue.tupled, fromTransactionTagValue)
  }

  val transactionTagValueQuery = TableQuery[TransactionTagValueTable]

  val toAnonymizationKey = (id: Long, created: Long, remoteBoxId: Long, transactionId: Long, remoteBoxName: String, patientName: String, anonPatientName: String, patientID: String, anonPatientID: String, patientBirthDate: String, studyInstanceUID: String, anonStudyInstanceUID: String, studyDescription: String, studyID: String, accessionNumber: String, seriesInstanceUID: String, anonSeriesInstanceUID: String, frameOfReferenceUID: String, anonFrameOfReferenceUID: String) =>
    AnonymizationKey(id, created, remoteBoxId, transactionId, remoteBoxName, patientName, anonPatientName, patientID, anonPatientID, patientBirthDate, studyInstanceUID, anonStudyInstanceUID, studyDescription, studyID, accessionNumber, seriesInstanceUID, anonSeriesInstanceUID, frameOfReferenceUID, anonFrameOfReferenceUID)
  val fromAnonymizationKey = (entry: AnonymizationKey) =>
    Option((entry.id, entry.created, entry.remoteBoxId, entry.transactionId, entry.remoteBoxName, entry.patientName, entry.anonPatientName, entry.patientID, entry.anonPatientID, entry.patientBirthDate, entry.studyInstanceUID, entry.anonStudyInstanceUID, entry.studyDescription, entry.studyID, entry.accessionNumber, entry.seriesInstanceUID, entry.anonSeriesInstanceUID, entry.frameOfReferenceUID, entry.anonFrameOfReferenceUID))

  class AnonymizationKeyTable(tag: Tag) extends Table[AnonymizationKey](tag, "AnonymizationKey") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
    def remoteBoxId = column[Long]("remoteboxid")
    def transactionId = column[Long]("transactionid")
    def remoteBoxName = column[String]("remoteboxname")
    def patientName = column[String]("patientname")
    def anonPatientName = column[String]("anonpatientname")
    def patientID = column[String]("patientid")
    def anonPatientID = column[String]("anonpatientid")
    def patientBirthDate = column[String]("patientbirthdate")
    def studyInstanceUID = column[String]("studyinstanceuid")
    def anonStudyInstanceUID = column[String]("anonstudyinstanceuid")
    def studyDescription = column[String]("studydescription")
    def studyID = column[String]("studyid")
    def accessionNumber = column[String]("accessionnumber")
    def seriesInstanceUID = column[String]("seriesinstanceuid")
    def anonSeriesInstanceUID = column[String]("anonseriesinstanceuid")
    def frameOfReferenceUID = column[String]("frameofreferenceuid")
    def anonFrameOfReferenceUID = column[String]("anonframeofreferenceuid")
    def * = (id, created, remoteBoxId, transactionId, remoteBoxName, patientName, anonPatientName, patientID, anonPatientID, patientBirthDate, studyInstanceUID, anonStudyInstanceUID, studyDescription, studyID, accessionNumber, seriesInstanceUID, anonSeriesInstanceUID, frameOfReferenceUID, anonFrameOfReferenceUID) <> (toAnonymizationKey.tupled, fromAnonymizationKey)
  }

  val anonymizationKeyQuery = TableQuery[AnonymizationKeyTable]

  def columnExists(tableName: String, columnName: String)(implicit session: Session): Boolean = {
    val tables = MTable.getTables(tableName).list
    if (tables.isEmpty)
      false
    else
      !tables(0).getColumns.list.filter(_.name == columnName).isEmpty
  }

  def create(implicit session: Session): Unit =
    if (MTable.getTables("Box").list.isEmpty) {
      (transactionTagValueQuery.ddl ++ anonymizationKeyQuery.ddl).create
    }

  def drop(implicit session: Session): Unit =
    (transactionTagValueQuery.ddl ++ anonymizationKeyQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    transactionTagValueQuery.delete
    anonymizationKeyQuery.delete
  }
  
  def listTransactionTagValues(implicit session: Session): List[TransactionTagValue] =
    transactionTagValueQuery.list

  def insertTransactionTagValue(entry: TransactionTagValue)(implicit session: Session): TransactionTagValue = {
    val generatedId = (transactionTagValueQuery returning transactionTagValueQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def tagValuesByImageFileIdAndTransactionId(imageFileId: Long, transactionId: Long)(implicit session: Session): List[TransactionTagValue] =
    transactionTagValueQuery.filter(_.imageFileId === imageFileId).filter(_.transactionId === transactionId).list

  def removeTransactionTagValue(transactionTagValueId: Long)(implicit session: Session): Unit =
    transactionTagValueQuery.filter(_.id === transactionTagValueId).delete

  def removeTransactionTagValuesByTransactionId(transactionId: Long)(implicit session: Session): Unit =
    transactionTagValueQuery.filter(_.transactionId === transactionId).delete

  def anonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String])(implicit session: Session): List[AnonymizationKey] = {

    orderBy.foreach(columnName =>
      if (!columnExists("AnonymizationKey", columnName))
        throw new IllegalArgumentException(s"Property $columnName does not exist"))

    implicit val getResult = GetResult(r =>
      AnonymizationKey(r.nextLong, r.nextLong, r.nextLong, r.nextLong, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString))

    var query = """select * from "AnonymizationKey""""

    filter.foreach(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      query += s""" where 
        lcase("remoteboxname") like $filterValueLike or 
          lcase("patientname") like $filterValueLike or 
            lcase("anonpatientname") like $filterValueLike or 
              lcase("patientid") like $filterValueLike or 
                lcase("anonpatientid") like $filterValueLike or
                  lcase("accessionnumber") like $filterValueLike"""
    })

    orderBy.foreach(orderByValue =>
      query += s""" order by "$orderByValue" ${if (orderAscending) "asc" else "desc"}""")

    query += s""" limit $count offset $startIndex"""

    Q.queryNA(query).list
  }

  def insertAnonymizationKey(entry: AnonymizationKey)(implicit session: Session): AnonymizationKey = {
    val generatedId = (anonymizationKeyQuery returning anonymizationKeyQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def removeAnonymizationKey(anonymizationKeyId: Long)(implicit session: Session): Unit =
    anonymizationKeyQuery.filter(_.id === anonymizationKeyId).delete

  def anonymizationKeysForAnonPatient(anonPatientName: String, anonPatientID: String)(implicit session: Session): List[AnonymizationKey] =
    anonymizationKeyQuery
      .filter(_.anonPatientName === anonPatientName)
      .filter(_.anonPatientID === anonPatientID)
      .list

  def anonymizationKeysForPatient(patientName: String, patientID: String)(implicit session: Session): List[AnonymizationKey] =
    anonymizationKeyQuery
      .filter(_.patientName === patientName)
      .filter(_.patientID === patientID)
      .list

}
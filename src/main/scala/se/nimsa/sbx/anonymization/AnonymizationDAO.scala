package se.nimsa.sbx.anonymization

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import AnonymizationProtocol._

class AnonymizationDAO(val driver: JdbcProfile) {
  import driver.simple._
  
  val toAnonymizationKey = (id: Long, created: Long, patientName: String, anonPatientName: String, patientID: String, anonPatientID: String, patientBirthDate: String, studyInstanceUID: String, anonStudyInstanceUID: String, studyDescription: String, studyID: String, accessionNumber: String, seriesInstanceUID: String, anonSeriesInstanceUID: String, frameOfReferenceUID: String, anonFrameOfReferenceUID: String) =>
    AnonymizationKey(id, created, patientName, anonPatientName, patientID, anonPatientID, patientBirthDate, studyInstanceUID, anonStudyInstanceUID, studyDescription, studyID, accessionNumber, seriesInstanceUID, anonSeriesInstanceUID, frameOfReferenceUID, anonFrameOfReferenceUID)
  val fromAnonymizationKey = (entry: AnonymizationKey) =>
    Option((entry.id, entry.created, entry.patientName, entry.anonPatientName, entry.patientID, entry.anonPatientID, entry.patientBirthDate, entry.studyInstanceUID, entry.anonStudyInstanceUID, entry.studyDescription, entry.studyID, entry.accessionNumber, entry.seriesInstanceUID, entry.anonSeriesInstanceUID, entry.frameOfReferenceUID, entry.anonFrameOfReferenceUID))

  class AnonymizationKeyTable(tag: Tag) extends Table[AnonymizationKey](tag, "AnonymizationKey") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
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
    def * = (id, created, patientName, anonPatientName, patientID, anonPatientID, patientBirthDate, studyInstanceUID, anonStudyInstanceUID, studyDescription, studyID, accessionNumber, seriesInstanceUID, anonSeriesInstanceUID, frameOfReferenceUID, anonFrameOfReferenceUID) <> (toAnonymizationKey.tupled, fromAnonymizationKey)
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
    if (MTable.getTables("AnonymizationKey").list.isEmpty) {
      anonymizationKeyQuery.ddl.create
    }

  def drop(implicit session: Session): Unit =
    anonymizationKeyQuery.ddl.drop

  def clear(implicit session: Session): Unit = {
    anonymizationKeyQuery.delete
  }
  
  def anonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String])(implicit session: Session): List[AnonymizationKey] = {

    orderBy.foreach(columnName =>
      if (!columnExists("AnonymizationKey", columnName))
        throw new IllegalArgumentException(s"Property $columnName does not exist"))

    implicit val getResult = GetResult(r =>
      AnonymizationKey(r.nextLong, r.nextLong, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString))

    var query = """select * from "AnonymizationKey""""

    filter.foreach(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      query += s""" where 
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
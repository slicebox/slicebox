package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData
import scala.slick.jdbc.meta.MTable
import se.vgregion.dicom.MetaDataProtocol.MetaData

object Tables extends { // or just use object demo.Tables, which is hard-wired to the driver stated during generation
  val profile = scala.slick.driver.H2Driver
} with se.vgregion.tables.Tables

class SqlDbOps(isProduction: Boolean) extends DbOps {
  import Tables._
  import Tables.profile.simple._

  //  private val tableNameScpData = "SCPDATA"

  private val db =
    if (isProduction)
      Database.forURL("jdbc:h2:storage", driver = "org.h2.Driver")
    else
      Database.forURL("jdbc:h2:mem:storage;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  //  // The query interface for the ScpData table
  //  private val scpDataQuery = TableQuery[ScpDataEntries]

  db.withSession(implicit session => {
    if (MTable.getTables("SCPDATA").list(session).isEmpty)
      ddl.create
    if (MTable.getTables("METADATA").list(session).isEmpty)
      ddl.create
  })

  def insertScpData(scpData: ScpData) = {
    db.withSession(implicit session =>
      ScpData.unapply(scpData).map(data => Scpdata += ScpdataRow(scpData.name, scpData.aeTitle, scpData.port)))
  }

  def insertMetaData(metaData: MetaData) = {
    db.withSession(implicit session =>
      MetaData.unapply(metaData).map(data => Metadata += MetadataRow(metaData.patientName, metaData.patientId, metaData.studyInstanceUID, metaData.studyDate, metaData.seriesInstanceUID, metaData.seriesDate, metaData.sopInstanceUID, metaData.fileName)))
  }

  def removeScpDataByName(name: String) = {
    db.withSession(implicit session =>
      Scpdata.filter(_.name === name).delete)
  }

  def removeMetaDataByFileName(fileName: String) = {
    db.withSession(implicit session =>
      Metadata.filter(_.filename === fileName).delete)
  }

  def scpDataEntries: Seq[ScpData] =
    db.withSession(implicit session =>
      Scpdata.list.map(row => ScpData(row.name, row.aetitle, row.port)))

  def metaDataEntries: Seq[MetaData] = {
    db.withSession(implicit session =>
      Metadata.list.map(row => MetaData(row.patientname, row.patientid, row.studyinstanceuid, row.studydate, row.seriesinstanceuid, row.seriesdate, row.sopinstanceuid, row.filename)))    
  }

}
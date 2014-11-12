package se.vgregion.db

import scala.slick.driver.H2Driver.simple._
import se.vgregion.dicom.ScpProtocol.ScpData

class SqlDbOps extends DbOps {

  private val db = Database.forURL("jdbc:h2:mem:hello", driver = "org.h2.Driver")

  def insertScpData(scpData: ScpData) = {}
  
  def scpDataEntries: Seq[ScpData] = null
  
}
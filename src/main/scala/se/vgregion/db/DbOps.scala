package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData

trait DbOps {

  def insertScpData(scpData: ScpData): Unit
  
  def scpDataEntries: Seq[ScpData]
  
}
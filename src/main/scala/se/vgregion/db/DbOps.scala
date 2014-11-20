package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData
import se.vgregion.dicom.MetaDataProtocol.MetaData

trait DbOps {

  def insertScpData(scpData: ScpData): Unit
  
  def insertMetaData(metaData: MetaData): Unit
  
  def removeScpDataByName(name: String): Unit

  def removeMetaDataByFileName(fileName: String): Unit
  
  def scpDataEntries: Seq[ScpData]
  
  def metaDataEntries: Seq[MetaData]
  
}
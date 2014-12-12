package se.vgregion.dicom

import java.nio.file.Path
import org.dcm4che3.data.{ Attributes => Dcm4CheAttributes }

object DicomProtocol {

  // incoming

  case class AddDicomFile(path: Path)

  case class AddDicom(metaInformation: Dcm4CheAttributes, dataset: Dcm4CheAttributes)

  case object SynchronizeWithStorage
  
  // outgoing
      
}
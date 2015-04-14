package se.vgregion.dicom

trait DicomPropertyValue { 
  def property: DicomProperty
  def value: String 
}

object DicomPropertyValue {

  case class PatientName(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientName }
  case class PatientID(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientID }
  case class PatientBirthDate(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientBirthDate }
  case class PatientSex(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientSex }

  case class StudyInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyInstanceUID }
  case class StudyDescription(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyDescription }
  case class StudyDate(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyDate }
  case class StudyID(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyID }
  case class AccessionNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.AccessionNumber }
  case class PatientAge(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientAge }
  
  case class SeriesInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesInstanceUID }
  case class SeriesNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesNumber }
  case class SeriesDescription(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesDescription }
  case class SeriesDate(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesDate }
  case class Modality(value: String) extends DicomPropertyValue { def property = DicomProperty.Modality }
  case class ProtocolName(value: String) extends DicomPropertyValue { def property = DicomProperty.ProtocolName }
  case class BodyPartExamined(value: String) extends DicomPropertyValue { def property = DicomProperty.BodyPartExamined }

  case class SOPInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.SOPInstanceUID }
  case class ImageType(value: String) extends DicomPropertyValue { def property = DicomProperty.ImageType }
  case class InstanceNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.InstanceNumber }

  case class Manufacturer(value: String) extends DicomPropertyValue { def property = DicomProperty.Manufacturer }
  case class StationName(value: String) extends DicomPropertyValue { def property = DicomProperty.StationName }

  case class FrameOfReferenceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.FrameOfReferenceUID }
  
}

package se.vgregion.dicom

object DicomHierarchy {
	import DicomPropertyValue._
  import se.vgregion.model.Entity

  case class Patient(
    id: Long,
    patientName: PatientName,
    patientID: PatientID,
    patientBirthDate: PatientBirthDate,
    patientSex: PatientSex) extends Entity {
    
    override def equals(o: Any): Boolean = o match {
      case that: Patient => that.patientName == patientName && that.patientID == patientID
      case _ => false
    }
  }

  case class Study(
    id: Long,
    patientId: Long,
    studyInstanceUID: StudyInstanceUID,
    studyDescription: StudyDescription,
    studyDate: StudyDate,
    studyID: StudyID,
    accessionNumber: AccessionNumber,
    patientAge: PatientAge) extends Entity {
    
    override def equals(o: Any): Boolean = o match {
      case that: Study => that.studyInstanceUID == studyInstanceUID
      case _ => false
    }
  }

  case class Equipment(
    id: Long,
    manufacturer: Manufacturer,
    stationName: StationName) extends Entity {
    
    override def equals(o: Any): Boolean = o match {
      case that: Equipment => that.manufacturer == manufacturer && that.stationName == stationName
      case _ => false
    }
  }

  case class FrameOfReference(
    id: Long,
    frameOfReferenceUID: FrameOfReferenceUID) extends Entity {
    
    override def equals(o: Any): Boolean = o match {
      case that: FrameOfReference => that.frameOfReferenceUID == frameOfReferenceUID
      case _ => false
    }
  }

  case class Series(
    id: Long,
    studyId: Long,
    equipmentId: Long,
    frameOfReferenceId: Long,
    seriesInstanceUID: SeriesInstanceUID,
    seriesDescription: SeriesDescription,
    seriesDate: SeriesDate,
    modality: Modality,
    protocolName: ProtocolName,
    bodyPartExamined: BodyPartExamined) extends Entity {
    override def equals(o: Any): Boolean = o match {
      case that: Series => that.seriesInstanceUID == seriesInstanceUID
      case _ => false
    }
  }

  case class Image(
    id: Long,
    seriesId: Long,
    sopInstanceUID: SOPInstanceUID,
    imageType: ImageType) extends Entity {
    override def equals(o: Any): Boolean = o match {
      case that: Image => that.sopInstanceUID == sopInstanceUID
      case _ => false
    }
  }

}

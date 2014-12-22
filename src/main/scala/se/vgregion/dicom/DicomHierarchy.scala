package se.vgregion.dicom

object DicomHierarchy {
	import DicomPropertyValue._

  case class Patient(
    patientName: PatientName,
    patientID: PatientID,
    patientBirthDate: PatientBirthDate,
    patientSex: PatientSex) {
    override def equals(o: Any): Boolean = o match {
      case that: Patient => that.patientName == patientName && that.patientID == patientID
      case _ => false
    }
  }

  case class Study(
    patient: Patient,
    studyInstanceUID: StudyInstanceUID,
    studyDescription: StudyDescription,
    studyDate: StudyDate,
    studyID: StudyID,
    accessionNumber: AccessionNumber) {
    override def equals(o: Any): Boolean = o match {
      case that: Study => that.patient == patient && that.studyInstanceUID == studyInstanceUID
      case _ => false
    }
  }

  case class Equipment(
    manufacturer: Manufacturer,
    stationName: StationName)

  case class FrameOfReference(
    frameOfReferenceUID: FrameOfReferenceUID)

  case class Series(
    study: Study,
    equipment: Equipment,
    frameOfReference: FrameOfReference,
    seriesInstanceUID: SeriesInstanceUID,
    seriesDescription: SeriesDescription,
    seriesDate: SeriesDate,
    modality: Modality,
    protocolName: ProtocolName,
    bodyPartExamined: BodyPartExamined) {
    override def equals(o: Any): Boolean = o match {
      case that: Series => that.study == study && that.seriesInstanceUID == seriesInstanceUID
      case _ => false
    }
  }

  case class Image(
    series: Series,
    sopInstanceUID: SOPInstanceUID,
    imageType: ImageType) {
    override def equals(o: Any): Boolean = o match {
      case that: Image => that.series == series && that.sopInstanceUID == sopInstanceUID
      case _ => false
    }
  }

}

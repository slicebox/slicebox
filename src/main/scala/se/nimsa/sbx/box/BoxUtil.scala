package se.nimsa.sbx.box

import org.dcm4che3.data.VR
import java.util.Date
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomUtil._
import BoxProtocol._
import org.dcm4che3.data.Tag

object BoxUtil {

  def applyTagValues(dataset: Attributes, tagValues: Seq[TransactionTagValue]): Unit =
    tagValues.foreach(tagValue => {
      val vr = if (dataset.contains(tagValue.tag)) dataset.getVR(tagValue.tag) else VR.SH
      dataset.setString(tagValue.tag, vr, tagValue.value)
    })

  def createAnonymizationKey(remoteBoxId: Long, transactionId: Long, remoteBoxName: String, dataset: Attributes, anonDataset: Attributes): AnonymizationKey = {
    val patient = datasetToPatient(dataset)
    val study = datasetToStudy(dataset)
    val series = datasetToSeries(dataset)
    val equipment = datasetToEquipment(dataset)
    val frameOfReference = datasetToFrameOfReference(dataset)
    val anonPatient = datasetToPatient(anonDataset)
    val anonStudy = datasetToStudy(anonDataset)
    val anonSeries = datasetToSeries(anonDataset)
    val anonEquipment = datasetToEquipment(anonDataset)
    val anonFrameOfReference = datasetToFrameOfReference(anonDataset)
    AnonymizationKey(-1, new Date().getTime, remoteBoxId, transactionId, remoteBoxName,
      patient.patientName.value, anonPatient.patientName.value,
      patient.patientID.value, anonPatient.patientID.value,
      study.studyInstanceUID.value, anonStudy.studyInstanceUID.value,
      series.seriesInstanceUID.value, anonSeries.seriesInstanceUID.value,
      equipment.manufacturer.value, anonEquipment.manufacturer.value,
      equipment.stationName.value, anonEquipment.stationName.value,
      frameOfReference.frameOfReferenceUID.value, anonFrameOfReference.frameOfReferenceUID.value)
  }

  def reverseAnonymization(keys: List[AnonymizationKey], dataset: Attributes) = {
    if (isAnonymous(dataset)) {
      keys.headOption.foreach(key => {
        dataset.setString(Tag.PatientName, VR.PN, key.patientName)
        dataset.setString(Tag.PatientID, VR.LO, key.patientID)
        val anonStudy = datasetToStudy(dataset)
        val studyKeys = keys.filter(_.anonStudyInstanceUID == anonStudy.studyInstanceUID.value)
        studyKeys.headOption.foreach(studyKey => {
          dataset.setString(Tag.StudyInstanceUID, VR.UI, studyKey.studyInstanceUID)
          val anonSeries = datasetToSeries(dataset)
          val seriesKeys = studyKeys.filter(_.anonSeriesInstanceUID == anonSeries.seriesInstanceUID.value)
          seriesKeys.headOption.foreach(seriesKey => {
            dataset.setString(Tag.SeriesInstanceUID, VR.UI, seriesKey.seriesInstanceUID)
            val anonEquipment = datasetToEquipment(dataset)
            val equipmentKeys = seriesKeys.filter(seriesKey => seriesKey.anonManufacturer == anonEquipment.manufacturer.value && studyKey.anonStationName == anonEquipment.stationName.value)
            equipmentKeys.headOption.foreach(equipmentKey => {
              dataset.setString(Tag.Manufacturer, VR.LO, equipmentKey.manufacturer)
              dataset.setString(Tag.StationName, VR.SH, equipmentKey.stationName)
            })
            val anonFoR = datasetToFrameOfReference(dataset)
            val forKeys = seriesKeys.filter(seriesKey => seriesKey.anonFrameOfReferenceUID == anonFoR.frameOfReferenceUID.value)
            forKeys.headOption.foreach(forKey =>
              dataset.setString(Tag.FrameOfReferenceUID, VR.UI, forKey.frameOfReferenceUID))
          })
        })
      })
      dataset
    }
  }

  def harmonizeAnonymization(keys: List[AnonymizationKey], dataset: Attributes, anonDataset: Attributes) = {
    if (!isAnonymous(dataset)) {
      keys.headOption.foreach(key => {
        anonDataset.setString(Tag.PatientID, VR.LO, key.anonPatientID)
        val study = datasetToStudy(dataset)
        val studyKeys = keys.filter(_.studyInstanceUID == study.studyInstanceUID.value)
        studyKeys.headOption.foreach(studyKey => {
          anonDataset.setString(Tag.StudyInstanceUID, VR.UI, studyKey.anonStudyInstanceUID)
          val series = datasetToSeries(dataset)
          val seriesKeys = studyKeys.filter(_.seriesInstanceUID == series.seriesInstanceUID.value)
          seriesKeys.headOption.foreach(seriesKey => {
            anonDataset.setString(Tag.SeriesInstanceUID, VR.UI, seriesKey.anonSeriesInstanceUID)
            val equipment = datasetToEquipment(dataset)
            val equipmentKeys = seriesKeys.filter(seriesKey => seriesKey.manufacturer == equipment.manufacturer.value && studyKey.stationName == equipment.stationName.value)
            equipmentKeys.headOption.foreach(equipmentKey => {
              anonDataset.setString(Tag.Manufacturer, VR.LO, equipmentKey.anonManufacturer)
              anonDataset.setString(Tag.StationName, VR.SH, equipmentKey.anonStationName)
            })
            val foR = datasetToFrameOfReference(dataset)
            val forKeys = seriesKeys.filter(seriesKey => seriesKey.frameOfReferenceUID == foR.frameOfReferenceUID.value)
            forKeys.headOption.foreach(forKey =>
              anonDataset.setString(Tag.FrameOfReferenceUID, VR.UI, forKey.anonFrameOfReferenceUID))
          })
        })
      })
      anonDataset
    }
  }

}
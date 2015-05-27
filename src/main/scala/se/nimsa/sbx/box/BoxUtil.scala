package se.nimsa.sbx.box

import org.dcm4che3.data.VR
import java.util.Date
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomUtil._
import BoxProtocol._

object BoxUtil {

  def applyTagValues(dataset: Attributes, tagValues: Seq[TransactionTagValue]): Unit =
    tagValues.foreach(tagValue => {
      val vr = if (dataset.contains(tagValue.tag)) dataset.getVR(tagValue.tag) else VR.SH
      dataset.setString(tagValue.tag, vr, tagValue.value)
    })

  def createAnonymizationKey(remoteBoxId: Long, transactionId: Long, imageFileId: Long, remoteBoxName: String, dataset: Attributes, anonDataset: Attributes): AnonymizationKey = {
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
    AnonymizationKey(-1, new Date().getTime, remoteBoxId, transactionId, imageFileId, remoteBoxName,
        patient.patientName.value, anonPatient.patientName.value, 
        patient.patientID.value, anonPatient.patientID.value,
        study.studyInstanceUID.value, anonStudy.studyInstanceUID.value,
        series.seriesInstanceUID.value, anonSeries.seriesInstanceUID.value,
        equipment.manufacturer.value, anonEquipment.manufacturer.value,
        equipment.stationName.value, anonEquipment.stationName.value,
        frameOfReference.frameOfReferenceUID.value, anonFrameOfReference.frameOfReferenceUID.value)
  }
  
}
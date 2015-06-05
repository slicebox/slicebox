/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    val anonFrameOfReference = datasetToFrameOfReference(anonDataset)
    AnonymizationKey(-1, new Date().getTime, remoteBoxId, transactionId, remoteBoxName,
      patient.patientName.value, anonPatient.patientName.value,
      patient.patientID.value, anonPatient.patientID.value, patient.patientBirthDate.value,
      study.studyInstanceUID.value, anonStudy.studyInstanceUID.value,
      study.studyDescription.value, study.studyID.value, study.accessionNumber.value,
      series.seriesInstanceUID.value, anonSeries.seriesInstanceUID.value,
      frameOfReference.frameOfReferenceUID.value, anonFrameOfReference.frameOfReferenceUID.value)
  }

  def isEqual(key1: AnonymizationKey, key2: AnonymizationKey) =
    key1.remoteBoxId == key2.remoteBoxId && key1.transactionId == key2.transactionId &&
      key1.patientName == key2.patientName && key1.anonPatientName == key2.anonPatientName &&
      key1.patientID == key2.patientID && key1.anonPatientID == key2.anonPatientID &&
      key1.studyInstanceUID == key2.studyInstanceUID && key1.anonStudyInstanceUID == key2.anonStudyInstanceUID &&
      key1.seriesInstanceUID == key2.seriesInstanceUID && key1.anonSeriesInstanceUID == key2.anonSeriesInstanceUID &&
      key1.frameOfReferenceUID == key2.frameOfReferenceUID && key1.anonFrameOfReferenceUID == key2.anonFrameOfReferenceUID

  def reverseAnonymization(keys: List[AnonymizationKey], dataset: Attributes) = {
    if (isAnonymous(dataset)) {
      keys.headOption.foreach(key => {
        dataset.setString(Tag.PatientName, VR.PN, key.patientName)
        dataset.setString(Tag.PatientID, VR.LO, key.patientID)
        dataset.setString(Tag.PatientBirthDate, VR.DA, key.patientBirthDate)
        val anonStudy = datasetToStudy(dataset)
        val studyKeys = keys.filter(_.anonStudyInstanceUID == anonStudy.studyInstanceUID.value)
        studyKeys.headOption.foreach(studyKey => {
          dataset.setString(Tag.StudyInstanceUID, VR.UI, studyKey.studyInstanceUID)
          dataset.setString(Tag.StudyDescription, VR.LO, studyKey.studyDescription)
          dataset.setString(Tag.StudyID, VR.SH, studyKey.studyID)
          dataset.setString(Tag.AccessionNumber, VR.SH, studyKey.accessionNumber)
        })
      })
      setAnonymous(dataset, false)
    }
    dataset
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
            val foR = datasetToFrameOfReference(dataset)
            val forKeys = seriesKeys.filter(seriesKey => seriesKey.frameOfReferenceUID == foR.frameOfReferenceUID.value)
            forKeys.headOption.foreach(forKey =>
              anonDataset.setString(Tag.FrameOfReferenceUID, VR.UI, forKey.anonFrameOfReferenceUID))
          })
        })
      })
    }
    anonDataset
  }

}

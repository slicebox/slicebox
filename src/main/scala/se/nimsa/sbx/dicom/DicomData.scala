package se.nimsa.sbx.dicom

import org.dcm4che3.data.Attributes

case class DicomData(attributes: Attributes, metaInformation: Attributes)
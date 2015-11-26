package se.nimsa.sbx.util

import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import org.dcm4che3.data.{ Attributes, Tag, VR }
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import java.util.Date
import se.nimsa.sbx.dicom.DicomUtil
import java.nio.file.Paths
import java.io.File
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomHierarchy._
import scala.slick.jdbc.JdbcBackend.Session
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.metadata.PropertiesDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesType
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.app.GeneralProtocol._

object TestUtil {

  def insertMetaData(metaDataDao: MetaDataDAO)(implicit session: Session) = {
    val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
    val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y"))
    val study2 = Study(-1, -1, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("19990102"), StudyID("stid2"), AccessionNumber("acc2"), PatientAge("14Y"))
    val series1 = Series(-1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid1"))
    val series2 = Series(-1, -1, SeriesInstanceUID("seuid2"), SeriesDescription("sedesc2"), SeriesDate("19990102"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid2"))
    val series3 = Series(-1, -1, SeriesInstanceUID("seuid3"), SeriesDescription("sedesc3"), SeriesDate("19990103"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart1"), Manufacturer("manu2"), StationName("station2"), FrameOfReferenceUID("frid1"))
    val series4 = Series(-1, -1, SeriesInstanceUID("seuid4"), SeriesDescription("sedesc4"), SeriesDate("19990104"), Modality("NM"), ProtocolName("prot4"), BodyPartExamined("bodypart2"), Manufacturer("manu3"), StationName("station3"), FrameOfReferenceUID("frid2"))
    val image1 = Image(-1, -1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image2 = Image(-1, -1, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image3 = Image(-1, -1, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image4 = Image(-1, -1, SOPInstanceUID("souid4"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image5 = Image(-1, -1, SOPInstanceUID("souid5"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image6 = Image(-1, -1, SOPInstanceUID("souid6"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image7 = Image(-1, -1, SOPInstanceUID("souid7"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
    val image8 = Image(-1, -1, SOPInstanceUID("souid8"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))

    val dbPatient1 = metaDataDao.insert(pat1)
    val dbStudy1 = metaDataDao.insert(study1.copy(patientId = dbPatient1.id))
    val dbStudy2 = metaDataDao.insert(study2.copy(patientId = dbPatient1.id))
    val dbSeries1 = metaDataDao.insert(series1.copy(studyId = dbStudy1.id))
    val dbSeries2 = metaDataDao.insert(series2.copy(studyId = dbStudy1.id))
    val dbSeries3 = metaDataDao.insert(series3.copy(studyId = dbStudy2.id))
    val dbSeries4 = metaDataDao.insert(series4.copy(studyId = dbStudy2.id))
    val dbImage1 = metaDataDao.insert(image1.copy(seriesId = dbSeries1.id))
    val dbImage2 = metaDataDao.insert(image2.copy(seriesId = dbSeries1.id))
    val dbImage3 = metaDataDao.insert(image3.copy(seriesId = dbSeries2.id))
    val dbImage4 = metaDataDao.insert(image4.copy(seriesId = dbSeries2.id))
    val dbImage5 = metaDataDao.insert(image5.copy(seriesId = dbSeries3.id))
    val dbImage6 = metaDataDao.insert(image6.copy(seriesId = dbSeries3.id))
    val dbImage7 = metaDataDao.insert(image7.copy(seriesId = dbSeries4.id))
    val dbImage8 = metaDataDao.insert(image8.copy(seriesId = dbSeries4.id))
    
    (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8))
  }

  def insertProperties(seriesTypeDao: SeriesTypeDAO, propertiesDao: PropertiesDAO, dbSeries1: Series, dbSeries2: Series, dbSeries3: Series, dbSeries4: Series, dbImage1: Image, dbImage2: Image, dbImage3: Image, dbImage4: Image, dbImage5: Image, dbImage6: Image, dbImage7: Image, dbImage8: Image)(implicit session: Session) = {
    val seriesSource1 = SeriesSource(-1, Source(SourceType.USER, "user", 1))
    val seriesSource2 = SeriesSource(-1, Source(SourceType.BOX, "box", 1))
    val seriesSource3 = SeriesSource(-1, Source(SourceType.DIRECTORY, "directory", 1))
    val seriesSource4 = SeriesSource(-1, Source(SourceType.SCP, "scp", 1))
    val seriesType1 = SeriesType(-1, "Test Type 1")
    val seriesType2 = SeriesType(-1, "Test Type 2")
    
    val dbSeriesSource1 = propertiesDao.insertSeriesSource(seriesSource1.copy(id = dbSeries1.id))
    val dbSeriesSource2 = propertiesDao.insertSeriesSource(seriesSource2.copy(id = dbSeries2.id))
    val dbSeriesSource3 = propertiesDao.insertSeriesSource(seriesSource3.copy(id = dbSeries3.id))
    val dbSeriesSource4 = propertiesDao.insertSeriesSource(seriesSource4.copy(id = dbSeries4.id))

    val dbSeriesTag1 = propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag1"), dbSeries1.id)
    val dbSeriesTag2 = propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag2"), dbSeries1.id)
    val dbSeriesTag3 = propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag1"), dbSeries2.id)
    val dbSeriesTag4 = propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag2"), dbSeries3.id)
    
    val dbSeriesType1 = seriesTypeDao.insertSeriesType(seriesType1)
    val dbSeriesType2 = seriesTypeDao.insertSeriesType(seriesType2)
    
    val seriesSeriesType1 = SeriesSeriesType(dbSeries1.id, dbSeriesType1.id)
    val seriesSeriesType2 = SeriesSeriesType(dbSeries2.id, dbSeriesType1.id)
    val seriesSeriesType3 = SeriesSeriesType(dbSeries2.id, dbSeriesType2.id)
    val seriesSeriesType4 = SeriesSeriesType(dbSeries3.id, dbSeriesType2.id)

    val dbSeriesSeriesType1 = propertiesDao.insertSeriesSeriesType(seriesSeriesType1)
    val dbSeriesSeriesType2 = propertiesDao.insertSeriesSeriesType(seriesSeriesType2)
    val dbSeriesSeriesType3 = propertiesDao.insertSeriesSeriesType(seriesSeriesType3)
    val dbSeriesSeriesType4 = propertiesDao.insertSeriesSeriesType(seriesSeriesType4)
    
    ((dbSeriesSource1, dbSeriesSource2, dbSeriesSource3, dbSeriesSource4), (dbSeriesSeriesType1, dbSeriesSeriesType2, dbSeriesSeriesType3, dbSeriesSeriesType4))
  }
  
  def testImageFile = new File(getClass().getResource("test.dcm").toURI())
  def testSecondaryCaptureFile = new File(getClass().getResource("sc.dcm").toURI())
  def testImageDataset(withPixelData: Boolean = true) = DicomUtil.loadDataset(testImageFile.toPath, withPixelData)
  def testImageByteArray = DicomUtil.toByteArray(testImageFile.toPath)

  def jpegFile = new File(getClass().getResource("cat.jpg").toURI())
  def jpegByteArray = Files.readAllBytes(jpegFile.toPath)
  
  def invalidImageFile = new File(getClass().getResource("invalid.dcm").toURI())
  
  def createDataset(
    patientName: String = "pat name",
    patientID: String = "pat id",
    patientBirthDate: String = "20010101",
    patientSex: String = "F",
    studyInstanceUID: String = "study instance uid",
    studyDescription: String = "study description",
    studyID: String = "study id",
    accessionNumber: String = "accession number",
    seriesInstanceUID: String = "series instance uid",
    seriesDescription: String = "series description",
    stationName: String = "station name",
    manufacturer: String = "manufacturer",
    protocolName: String = "protocol name",
    frameOfReferenceUID: String = "frame of reference uid",
    sopInstanceUID: String = "sop instance uid") = {
    val dataset = new Attributes()
    dataset.setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2")
    dataset.setString(Tag.PatientName, VR.PN, patientName)
    dataset.setString(Tag.PatientID, VR.LO, patientID)
    dataset.setString(Tag.PatientBirthDate, VR.DA, patientBirthDate)
    dataset.setString(Tag.PatientSex, VR.CS, patientSex)
    dataset.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUID)
    dataset.setString(Tag.StudyDescription, VR.LO, studyDescription)
    dataset.setString(Tag.StudyID, VR.LO, studyID)
    dataset.setString(Tag.AccessionNumber, VR.SH, accessionNumber)
    dataset.setString(Tag.SeriesInstanceUID, VR.UI, seriesInstanceUID)
    dataset.setString(Tag.SeriesDescription, VR.UI, seriesDescription)
    dataset.setString(Tag.StationName, VR.LO, stationName)
    dataset.setString(Tag.Manufacturer, VR.LO, manufacturer)
    dataset.setString(Tag.ProtocolName, VR.LO, protocolName)
    dataset.setString(Tag.FrameOfReferenceUID, VR.UI, frameOfReferenceUID)
    dataset.setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUID)
    dataset
  }

  def createAnonymizationKey(
    dataset: Attributes,
    anonPatientName: String = "anon patient name",
    anonPatientID: String = "anon patient ID",
    anonStudyInstanceUID: String = "anon study instance UID",
    anonSeriesInstanceUID: String = "anon series instance UID",
    anonFrameOfReferenceUID: String = "anon frame of reference UID") =
    AnonymizationKey(-1, new Date().getTime,
      dataset.getString(Tag.PatientName), anonPatientName,
      dataset.getString(Tag.PatientID), anonPatientID,
      dataset.getString(Tag.PatientBirthDate),
      dataset.getString(Tag.StudyInstanceUID), anonStudyInstanceUID,
      dataset.getString(Tag.StudyDescription),
      dataset.getString(Tag.StudyID),
      dataset.getString(Tag.AccessionNumber),
      dataset.getString(Tag.SeriesInstanceUID), anonSeriesInstanceUID,
      dataset.getString(Tag.SeriesDescription),
      dataset.getString(Tag.ProtocolName),
      dataset.getString(Tag.FrameOfReferenceUID), anonFrameOfReferenceUID)

  def deleteFolder(path: Path) =
    Files.walkFileTree(path, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
        // try to delete the file anyway, even if its attributes could not be read, since delete-only access is theoretically possible
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult =
        if (exc == null) {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        } else {
          // directory iteration failed; propagate exception
          throw exc
        }
    })

}
package se.nimsa.sbx.util

import java.io._
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date

import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Flow, Keep, Sink, Source => StreamSource}
import akka.stream.testkit.TestSubscriber
import akka.util.ByteString
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import se.nimsa.dicom.data.DicomParts._
import se.nimsa.dicom.data.VR.VR
import se.nimsa.dicom.data.{Elements, Tag, tagToString}
import se.nimsa.dicom.streams.ElementFlows.elementFlow
import se.nimsa.dicom.streams.ElementSink.elementSink
import se.nimsa.dicom.streams.ParseFlow
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataProtocol.{SeriesSource, SeriesTag}
import se.nimsa.sbx.metadata.{MetaDataDAO, PropertiesDAO}
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.{SeriesSeriesType, SeriesType}
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

object TestUtil {

  private def parseSink(withPixelData: Boolean)(implicit ec: ExecutionContext): Sink[ByteString, Future[Elements]] =
    Flow[ByteString]
      .via(new ParseFlow(stopTag = if (withPixelData) None else Some(Tag.PixelData)))
      .via(elementFlow)
      .toMat(elementSink)(Keep.right)

  def loadDicomData(path: Path, withPixelData: Boolean)(implicit ec: ExecutionContext, materializer: Materializer): Elements =
    Await.result(FileIO.fromPath(path).runWith(parseSink(withPixelData)), 5.seconds)

  def loadDicomData(bytes: ByteString, withPixelData: Boolean)(implicit ec: ExecutionContext, materializer: Materializer): Elements =
    Await.result(StreamSource.single(bytes).runWith(parseSink(withPixelData)), 5.seconds)

  def toBytes(path: Path)(implicit ec: ExecutionContext, materializer: Materializer): ByteString = toBytes(loadDicomData(path, withPixelData = true))

  def toBytes(elements: Elements): ByteString = elements.toBytes()

  def createTestDb(name: String): DatabaseConfig[JdbcProfile] =
    DatabaseConfig.forConfig[JdbcProfile]("slicebox.database.in-memory", ConfigFactory.load().withValue(
      "slicebox.database.in-memory.db.url",
      ConfigValueFactory.fromAnyRef(s"jdbc:h2:mem:./$name")
    ))

  def createMultipartFormWithFile(file: File) = Multipart.FormData(
    BodyPart("file", HttpEntity.fromPath(
      ContentTypes.`application/octet-stream`, file.toPath), Map("filename" -> file.getName)))

  def insertMetaData(metaDataDao: MetaDataDAO)(implicit ec: ExecutionContext): Future[(Patient, (Study, Study), (Series, Series, Series, Series), (Image, Image, Image, Image, Image, Image, Image, Image))] = {
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

    for {
      dbPatient1 <- metaDataDao.insert(pat1)
      dbStudy1 <- metaDataDao.insert(study1.copy(patientId = dbPatient1.id))
      dbStudy2 <- metaDataDao.insert(study2.copy(patientId = dbPatient1.id))
      dbSeries1 <- metaDataDao.insert(series1.copy(studyId = dbStudy1.id))
      dbSeries2 <- metaDataDao.insert(series2.copy(studyId = dbStudy1.id))
      dbSeries3 <- metaDataDao.insert(series3.copy(studyId = dbStudy2.id))
      dbSeries4 <- metaDataDao.insert(series4.copy(studyId = dbStudy2.id))
      dbImage1 <- metaDataDao.insert(image1.copy(seriesId = dbSeries1.id))
      dbImage2 <- metaDataDao.insert(image2.copy(seriesId = dbSeries1.id))
      dbImage3 <- metaDataDao.insert(image3.copy(seriesId = dbSeries2.id))
      dbImage4 <- metaDataDao.insert(image4.copy(seriesId = dbSeries2.id))
      dbImage5 <- metaDataDao.insert(image5.copy(seriesId = dbSeries3.id))
      dbImage6 <- metaDataDao.insert(image6.copy(seriesId = dbSeries3.id))
      dbImage7 <- metaDataDao.insert(image7.copy(seriesId = dbSeries4.id))
      dbImage8 <- metaDataDao.insert(image8.copy(seriesId = dbSeries4.id))
    } yield {
      (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8))
    }
  }

  def insertProperties(seriesTypeDao: SeriesTypeDAO, propertiesDao: PropertiesDAO, dbSeries1: Series, dbSeries2: Series, dbSeries3: Series, dbSeries4: Series, dbImage1: Image, dbImage2: Image, dbImage3: Image, dbImage4: Image, dbImage5: Image, dbImage6: Image, dbImage7: Image, dbImage8: Image)(implicit ec: ExecutionContext): Future[((SeriesSource, SeriesSource, SeriesSource, SeriesSource), (SeriesSeriesType, SeriesSeriesType, SeriesSeriesType, SeriesSeriesType))] = {
    val seriesSource1 = SeriesSource(-1, Source(SourceType.USER, "user", 1))
    val seriesSource2 = SeriesSource(-1, Source(SourceType.BOX, "box", 1))
    val seriesSource3 = SeriesSource(-1, Source(SourceType.DIRECTORY, "directory", 1))
    val seriesSource4 = SeriesSource(-1, Source(SourceType.SCP, "scp", 1))
    val seriesType1 = SeriesType(-1, "Test Type 1")
    val seriesType2 = SeriesType(-1, "Test Type 2")

    for {
      dbSeriesSource1 <- propertiesDao.insertSeriesSource(seriesSource1.copy(id = dbSeries1.id))
      dbSeriesSource2 <- propertiesDao.insertSeriesSource(seriesSource2.copy(id = dbSeries2.id))
      dbSeriesSource3 <- propertiesDao.insertSeriesSource(seriesSource3.copy(id = dbSeries3.id))
      dbSeriesSource4 <- propertiesDao.insertSeriesSource(seriesSource4.copy(id = dbSeries4.id))

      _ <- propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag1"), dbSeries1.id)
      _ <- propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag2"), dbSeries1.id)
      _ <- propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag1"), dbSeries2.id)
      _ <- propertiesDao.addAndInsertSeriesTagForSeriesId(SeriesTag(-1, "Tag2"), dbSeries3.id)

      dbSeriesType1 <- seriesTypeDao.insertSeriesType(seriesType1)
      dbSeriesType2 <- seriesTypeDao.insertSeriesType(seriesType2)

      dbSeriesSeriesType1 <- seriesTypeDao.upsertSeriesSeriesType(SeriesSeriesType(dbSeries1.id, dbSeriesType1.id))
      dbSeriesSeriesType2 <- seriesTypeDao.upsertSeriesSeriesType(SeriesSeriesType(dbSeries2.id, dbSeriesType1.id))
      dbSeriesSeriesType3 <- seriesTypeDao.upsertSeriesSeriesType(SeriesSeriesType(dbSeries2.id, dbSeriesType2.id))
      dbSeriesSeriesType4 <- seriesTypeDao.upsertSeriesSeriesType(SeriesSeriesType(dbSeries3.id, dbSeriesType2.id))
    } yield {
      ((dbSeriesSource1, dbSeriesSource2, dbSeriesSource3, dbSeriesSource4), (dbSeriesSeriesType1, dbSeriesSeriesType2, dbSeriesSeriesType3, dbSeriesSeriesType4))
    }
  }

  def testImageFile = new File(getClass.getResource("test.dcm").toURI)

  def testImageFormData: Multipart.FormData = createMultipartFormWithFile(testImageFile)

  def testSecondaryCaptureFile = new File(getClass.getResource("sc.dcm").toURI)

  def testImageDicomData(withPixelData: Boolean = true)(implicit ec: ExecutionContext, materializer: Materializer): Elements = loadDicomData(testImageFile.toPath, withPixelData)

  def testImageBytes(implicit ec: ExecutionContext, materializer: Materializer): ByteString = toBytes(testImageFile.toPath)

  def jpegFile = new File(getClass.getResource("cat.jpg").toURI)

  def jpegByteArray: Array[Byte] = Files.readAllBytes(jpegFile.toPath)

  def invalidImageFile = new File(getClass.getResource("invalid.dcm").toURI)

  def createElements(patientName: String = "pat name",
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
                     sopInstanceUID: String = "sop instance uid"): Elements = {

    Elements.empty()
      .setString(Tag.MediaStorageSOPClassUID, "1.2.840.10008.5.1.4.1.1.2")
      .setString(Tag.TransferSyntaxUID, "1.2.840.10008.1.2.1")
      .setString(Tag.PatientName, patientName)
      .setString(Tag.PatientID, patientID)
      .setString(Tag.PatientBirthDate, patientBirthDate)
      .setString(Tag.PatientSex, patientSex)
      .setString(Tag.StudyInstanceUID, studyInstanceUID)
      .setString(Tag.StudyDescription, studyDescription)
      .setString(Tag.StudyID, studyID)
      .setString(Tag.AccessionNumber, accessionNumber)
      .setString(Tag.SeriesInstanceUID, seriesInstanceUID)
      .setString(Tag.SeriesDescription, seriesDescription)
      .setString(Tag.StationName, stationName)
      .setString(Tag.Manufacturer, manufacturer)
      .setString(Tag.ProtocolName, protocolName)
      .setString(Tag.FrameOfReferenceUID, frameOfReferenceUID)
      .setString(Tag.SOPInstanceUID, sopInstanceUID)
  }

  def createAnonymizationKey(elements: Elements,
                             imageId: Long = -1,
                             anonPatientName: String = "anon patient name",
                             anonPatientID: String = "anon patient ID",
                             anonStudyInstanceUID: String = "anon study instance UID",
                             anonSeriesInstanceUID: String = "anon series instance UID",
                             anonSOPInstanceUID: String = "anon SOP instance UID") =
    AnonymizationKey(-1,  new Date().getTime, imageId,
      elements.getString(Tag.PatientName).getOrElse(""), anonPatientName,
      elements.getString(Tag.PatientID).getOrElse(""), anonPatientID,
      elements.getString(Tag.StudyInstanceUID).getOrElse(""), anonStudyInstanceUID,
      elements.getString(Tag.SeriesInstanceUID).getOrElse(""), anonSeriesInstanceUID,
      elements.getString(Tag.SOPInstanceUID).getOrElse(""), anonSOPInstanceUID)

  def deleteFolder(path: Path): Path =
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

  type PartProbe = TestSubscriber.Probe[DicomPart]

  implicit class DicomPartProbe(probe: PartProbe) {
    def expectPreamble(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: PreamblePart => true
        case p => throw new RuntimeException(s"Expected DicomPreamble, got $p")
      }

    def expectValueChunk(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: ValueChunk => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk, got $p")
      }

    def expectValueChunk(length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case chunk: ValueChunk if chunk.bytes.length == length => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk with length = $length, got $p")
      }

    def expectValueChunk(bytes: ByteString): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case chunk: ValueChunk if chunk.bytes == bytes => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk with bytes = $bytes, got $p")
      }

    def expectItem(index: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: ItemPart if item.index == index => true
        case p => throw new RuntimeException(s"Expected DicomItem with index = $index, got $p")
      }

    def expectItem(index: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: ItemPart if item.index == index && item.length == length => true
        case p => throw new RuntimeException(s"Expected DicomItem with index = $index and length $length, got $p")
      }

    def expectItemDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: ItemDelimitationPart => true
        case p => throw new RuntimeException(s"Expected DicomSequenceItemDelimitation, got $p")
      }

    def expectFragments(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: FragmentsPart => true
        case p => throw new RuntimeException(s"Expected DicomFragments, got $p")
      }

    def expectHeader(tag: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: HeaderPart if h.tag == tag => true
        case p => throw new RuntimeException(s"Expected DicomHeader with tag = ${tagToString(tag)}, got $p")
      }

    def expectHeader(tag: Int, vr: VR, length: Long): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: HeaderPart if h.tag == tag && h.vr == vr && h.length == length => true
        case p => throw new RuntimeException(s"Expected DicomHeader with tag = ${tagToString(tag)}, VR = $vr and length = $length, got $p")
      }

    def expectSequence(tag: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: SequencePart if h.tag == tag => true
        case p => throw new RuntimeException(s"Expected DicomSequence with tag = ${tagToString(tag)}, got $p")
      }

    def expectSequence(tag: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: SequencePart if h.tag == tag && h.length == length => true
        case p => throw new RuntimeException(s"Expected DicomSequence with tag = ${tagToString(tag)} and length = $length, got $p")
      }

    def expectSequenceDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: SequenceDelimitationPart => true
        case p => throw new RuntimeException(s"Expected DicomSequenceDelimitation, got $p")
      }

    def expectUnknownPart(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: UnknownPart => true
        case p => throw new RuntimeException(s"Expected UnkownPart, got $p")
      }

    def expectDeflatedChunk(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DeflatedChunk => true
        case p => throw new RuntimeException(s"Expected DicomDeflatedChunk, got $p")
      }

    def expectDicomComplete(): PartProbe = probe
      .request(1)
      .expectComplete()

    def expectDicomError(): Throwable = probe
      .request(1)
      .expectError()

    def expectMetaPart(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: MetaPart => true
        case p => throw new RuntimeException(s"Expected MetaPart, got $p")
      }

    def expectHeaderAndValueChunkPairs(tags: Int*): PartProbe =
      tags.foldLeft(probe)((probe, tag) => probe.expectHeader(tag).expectValueChunk())
  }

}
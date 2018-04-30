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
import se.nimsa.dicom.DicomParts._
import se.nimsa.dicom.VR.VR
import se.nimsa.dicom._
import se.nimsa.dicom.streams.{ElementFolds, ParseFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._
import se.nimsa.sbx.metadata.MetaDataProtocol.{SeriesSource, SeriesTag}
import se.nimsa.sbx.metadata.{MetaDataDAO, PropertiesDAO}
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.{SeriesSeriesType, SeriesType}
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

object TestUtil {

  private def parseSink(withPixelData: Boolean): Sink[ByteString, Future[Elements]] =
    Flow[ByteString]
      .via(new ParseFlow(stopTag = if (withPixelData) Some(Tag.PixelData) else None))
      .via(ElementFolds.elementsFlow)
      .toMat(ElementFolds.elementsSink)(Keep.right)

  def loadDicomData(path: Path, withPixelData: Boolean)(implicit materializer: Materializer): Elements =
    Await.result(FileIO.fromPath(path).runWith(parseSink(withPixelData)), 5.seconds)

  def loadDicomData(bytes: ByteString, withPixelData: Boolean)(implicit materializer: Materializer): Elements =
    Await.result(StreamSource.single(bytes).runWith(parseSink(withPixelData)), 5.seconds)

  def toBytes(path: Path)(implicit materializer: Materializer): ByteString = toBytes(loadDicomData(path, withPixelData = true))

  def toBytes(elements: Elements): ByteString = elements.bytes

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

  def testImageDicomData(withPixelData: Boolean = true)(implicit materializer: Materializer): Elements = loadDicomData(testImageFile.toPath, withPixelData)

  def testImageBytes(implicit materializer: Materializer): ByteString = toBytes(testImageFile.toPath)

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

    Elements.empty
      .update(TagPath.fromTag(Tag.MediaStorageSOPClassUID), Element.explicitLE(Tag.MediaStorageSOPClassUID, VR.UI, ByteString("1.2.840.10008.5.1.4.1.1.2")))
      .update(TagPath.fromTag(Tag.TransferSyntaxUID), Element.explicitLE(Tag.TransferSyntaxUID, VR.UI, ByteString("1.2.840.10008.1.2.1")))
      .update(TagPath.fromTag(Tag.PatientName), Element.explicitLE(Tag.PatientName, VR.PN, ByteString(patientName)))
      .update(TagPath.fromTag(Tag.PatientID), Element.explicitLE(Tag.PatientID, VR.LO, ByteString(patientID)))
      .update(TagPath.fromTag(Tag.PatientBirthDate), Element.explicitLE(Tag.PatientBirthDate, VR.DA, ByteString(patientBirthDate)))
      .update(TagPath.fromTag(Tag.PatientSex), Element.explicitLE(Tag.PatientSex, VR.CS, ByteString(patientSex)))
      .update(TagPath.fromTag(Tag.StudyInstanceUID), Element.explicitLE(Tag.StudyInstanceUID, VR.UI, ByteString(studyInstanceUID)))
      .update(TagPath.fromTag(Tag.StudyDescription), Element.explicitLE(Tag.StudyDescription, VR.LO, ByteString(studyDescription)))
      .update(TagPath.fromTag(Tag.StudyID), Element.explicitLE(Tag.StudyID, VR.LO, ByteString(studyID)))
      .update(TagPath.fromTag(Tag.AccessionNumber), Element.explicitLE(Tag.AccessionNumber, VR.SH, ByteString(accessionNumber)))
      .update(TagPath.fromTag(Tag.SeriesInstanceUID), Element.explicitLE(Tag.SeriesInstanceUID, VR.UI, ByteString(seriesInstanceUID)))
      .update(TagPath.fromTag(Tag.SeriesDescription), Element.explicitLE(Tag.SeriesDescription, VR.LO, ByteString(seriesDescription)))
      .update(TagPath.fromTag(Tag.StationName), Element.explicitLE(Tag.StationName, VR.LO, ByteString(stationName)))
      .update(TagPath.fromTag(Tag.Manufacturer), Element.explicitLE(Tag.Manufacturer, VR.LO, ByteString(manufacturer)))
      .update(TagPath.fromTag(Tag.ProtocolName), Element.explicitLE(Tag.ProtocolName, VR.LO, ByteString(protocolName)))
      .update(TagPath.fromTag(Tag.FrameOfReferenceUID), Element.explicitLE(Tag.FrameOfReferenceUID, VR.UI, ByteString(frameOfReferenceUID)))
      .update(TagPath.fromTag(Tag.SOPInstanceUID), Element.explicitLE(Tag.SOPInstanceUID, VR.UI, ByteString(sopInstanceUID)))
  }

  def createAnonymizationKey(elements: Elements,
                             anonPatientName: String = "anon patient name",
                             anonPatientID: String = "anon patient ID",
                             anonStudyInstanceUID: String = "anon study instance UID",
                             anonSeriesInstanceUID: String = "anon series instance UID",
                             anonFrameOfReferenceUID: String = "anon frame of reference UID") =
    AnonymizationKey(-1, new Date().getTime,
      elements(Tag.PatientName).map(_.toSingleString()).getOrElse(""), anonPatientName,
      elements(Tag.PatientID).map(_.toSingleString()).getOrElse(""), anonPatientID,
      elements(Tag.PatientBirthDate).map(_.toSingleString()).getOrElse("1900-01-01"),
      elements(Tag.StudyInstanceUID).map(_.toSingleString()).getOrElse(""), anonStudyInstanceUID,
      elements(Tag.StudyDescription).map(_.toSingleString()).getOrElse(""),
      elements(Tag.StudyID).map(_.toSingleString()).getOrElse("Study ID"),
      elements(Tag.AccessionNumber).map(_.toSingleString()).getOrElse("12345"),
      elements(Tag.SeriesInstanceUID).map(_.toSingleString()).getOrElse(""), anonSeriesInstanceUID,
      elements(Tag.SeriesDescription).map(_.toSingleString()).getOrElse("Series Description"),
      elements(Tag.ProtocolName).map(_.toSingleString()).getOrElse("Protocol Name"),
      elements(Tag.FrameOfReferenceUID).map(_.toSingleString()).getOrElse("1.2.3.4.5"), anonFrameOfReferenceUID)

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
        case _: DicomPreamble => true
        case p => throw new RuntimeException(s"Expected DicomPreamble, got $p")
      }

    def expectValueChunk(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomValueChunk => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk, got $p")
      }

    def expectValueChunk(length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case chunk: DicomValueChunk if chunk.bytes.length == length => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk with length = $length, got $p")
      }

    def expectValueChunk(bytes: ByteString): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case chunk: DicomValueChunk if chunk.bytes == bytes => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk with bytes = $bytes, got $p")
      }

    def expectItem(index: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: DicomItem if item.index == index => true
        case p => throw new RuntimeException(s"Expected DicomItem with index = $index, got $p")
      }

    def expectItem(index: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: DicomItem if item.index == index && item.length == length => true
        case p => throw new RuntimeException(s"Expected DicomItem with index = $index and length $length, got $p")
      }

    def expectItemDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomSequenceItemDelimitation => true
        case p => throw new RuntimeException(s"Expected DicomSequenceItemDelimitation, got $p")
      }

    def expectFragments(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomFragments => true
        case p => throw new RuntimeException(s"Expected DicomFragments, got $p")
      }

    def expectFragmentsDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomFragmentsDelimitation => true
        case p => throw new RuntimeException(s"Expected DicomFragmentsDelimitation, got $p")
      }

    def expectHeader(tag: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: DicomHeader if h.tag == tag => true
        case p => throw new RuntimeException(s"Expected DicomHeader with tag = ${tagToString(tag)}, got $p")
      }

    def expectHeader(tag: Int, vr: VR, length: Long): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: DicomHeader if h.tag == tag && h.vr == vr && h.length == length => true
        case p => throw new RuntimeException(s"Expected DicomHeader with tag = ${tagToString(tag)}, VR = $vr and length = $length, got $p")
      }

    def expectSequence(tag: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: DicomSequence if h.tag == tag => true
        case p => throw new RuntimeException(s"Expected DicomSequence with tag = ${tagToString(tag)}, got $p")
      }

    def expectSequence(tag: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: DicomSequence if h.tag == tag && h.length == length => true
        case p => throw new RuntimeException(s"Expected DicomSequence with tag = ${tagToString(tag)} and length = $length, got $p")
      }

    def expectSequenceDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomSequenceDelimitation => true
        case p => throw new RuntimeException(s"Expected DicomSequenceDelimitation, got $p")
      }

    def expectUnknownPart(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomUnknownPart => true
        case p => throw new RuntimeException(s"Expected UnkownPart, got $p")
      }

    def expectDeflatedChunk(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DicomDeflatedChunk => true
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
        case _: DicomInfoPart => true
        case p => throw new RuntimeException(s"Expected DicomMetaPart, got $p")
      }

    def expectMetaPart(metaPart: DicomInfoPart): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case p: DicomInfoPart if p == metaPart => true
        case p => throw new RuntimeException(s"Expected DicomMetaPart $metaPart, got $p")
      }

    def expectHeaderAndValueChunkPairs(tags: Int*): PartProbe =
      tags.foldLeft(probe)((probe, tag) => probe.expectHeader(tag).expectValueChunk())
  }

}
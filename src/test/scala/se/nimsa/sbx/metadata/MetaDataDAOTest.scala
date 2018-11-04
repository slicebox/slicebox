package se.nimsa.sbx.metadata

import akka.util.Timeout
import org.h2.jdbc.JdbcSQLException
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll, Matchers}
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class MetaDataDAOTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

  /*
  The ExecutionContext provided by ScalaTest only works inside tests, but here we have async stuff in beforeEach and
  afterEach so we must roll our own EC.
  */
  lazy val ec: ExecutionContext = ExecutionContext.global

  val dbConfig = TestUtil.createTestDb("metadatadaotest")
  val dao = new MetaDataDAO(dbConfig)(ec)

  implicit val timeout: Timeout = Timeout(200.seconds)

  override def beforeAll(): Unit = await(dao.create())

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

  val pat1_copy = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("1000-01-01"), PatientSex("F"))
  val study3 = Study(-1, -1, StudyInstanceUID("stuid3"), StudyDescription("stdesc3"), StudyDate("19990103"), StudyID("stid3"), AccessionNumber("acc3"), PatientAge("13Y"))
  // the following series has a copied SeriesInstanceUID but unique parent study so should no be treated as copy
  val series1_copy = Series(-1, -1, SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid1"))
  val image9 = Image(-1, -1, SOPInstanceUID("souid9"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))

  "The meta data db" should "be emtpy before anything has been added" in {
    dao.images.map(_.size should be(0))
  }

  it should "contain one entry in each table after inserting one patient, study, series, equipment, frame of reference and image" in {
    for {
      dbPat <- dao.insert(pat1)
      dbStudy <- dao.insert(study1.copy(patientId = dbPat.id))
      dbSeries <- dao.insert(series1.copy(studyId = dbStudy.id))
      dbImage <- dao.insert(image1.copy(seriesId = dbSeries.id))

      images <- dao.images
      series <- dao.series
      studies <- dao.studies
      patients <- dao.patients(0, 20, None, orderAscending = false, None)
    } yield {
      images.size should be(1)
      series.size should be(1)
      studies.size should be(1)
      patients.size should be(1)

      dbPat.id should be >= 0L
      dbStudy.id should be >= 0L
      dbSeries.id should be >= 0L
      dbImage.id should be >= 0L
    }
  }

  it should "not support adding a study which links to a non-existing patient" in {
    recoverToSucceededIf[JdbcSQLException] {
      dao.insert(study1)
    }
  }

  it should "return the correct number of entities by each category" in {
    for {
      _ <- dao.clear()
      dbPat <- dao.insert(pat1)
      dbStudy1 <- dao.insert(study1.copy(patientId = dbPat.id))
      dbStudy2 <- dao.insert(study2.copy(patientId = dbPat.id))
      dbSeries1 <- dao.insert(series1.copy(studyId = dbStudy1.id))
      dbSeries2 <- dao.insert(series2.copy(studyId = dbStudy2.id))
      dbSeries3 <- dao.insert(series3.copy(studyId = dbStudy1.id))
      dbSeries4 <- dao.insert(series4.copy(studyId = dbStudy2.id))
      _ <- dao.insert(image1.copy(seriesId = dbSeries1.id))
      _ <- dao.insert(image2.copy(seriesId = dbSeries2.id))
      _ <- dao.insert(image3.copy(seriesId = dbSeries3.id))
      _ <- dao.insert(image4.copy(seriesId = dbSeries4.id))
      _ <- dao.insert(image5.copy(seriesId = dbSeries1.id))
      _ <- dao.insert(image6.copy(seriesId = dbSeries2.id))
      _ <- dao.insert(image7.copy(seriesId = dbSeries3.id))
      _ <- dao.insert(image8.copy(seriesId = dbSeries4.id))

      patients <- dao.patients(0, 20, None, orderAscending = false, None)
      studiesForPatient <- dao.studiesForPatient(0, 20, dbPat.id)
      seriesForStudy1 <- dao.seriesForStudy(0, 20, dbStudy1.id)
      seriesForStudy2 <- dao.seriesForStudy(0, 20, dbStudy2.id)
      imagesForSeries1 <- dao.imagesForSeries(0, 20, dbSeries1.id, None, orderAscending = false, None)
      imagesForSeries2 <- dao.imagesForSeries(0, 20, dbSeries2.id, None, orderAscending = false, None)
      imagesForSeries3 <- dao.imagesForSeries(0, 20, dbSeries3.id, None, orderAscending = false, None)
      imagesForSeries4 <- dao.imagesForSeries(0, 20, dbSeries4.id, None, orderAscending = false, None)
    } yield {
      patients.size should be(1)
      studiesForPatient.size should be(2)
      seriesForStudy1.size should be(2)
      seriesForStudy2.size should be(2)
      imagesForSeries1.size should be(2)
      imagesForSeries2.size should be(2)
      imagesForSeries3.size should be(2)
      imagesForSeries4.size should be(2)
    }
  }

  it should "return the correct number of patients for patients queries" in {
    for {
      p1 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1")))
      p2 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"), QueryProperty("patientSex", QueryOperator.EQUALS, "M")))
      p3 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"), QueryProperty("patientSex", QueryOperator.EQUALS, "F")))
      p4 <- dao.queryPatients(0, 1, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1")))
      p5 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1")))
      p6 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"), QueryProperty("studyDate", QueryOperator.EQUALS, "19990101")))
      p7 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"), QueryProperty("studyDate", QueryOperator.EQUALS, "20100101")))
      p8 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
      p9 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu1")))
      p10 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1")))
      p11 <- dao.queryPatients(0, 10, None, orderAscending = true, Seq(QueryProperty("studyDescription", QueryOperator.LIKE, "desc")))
      p12 <- dao.queryPatients(1, 1, None, orderAscending = true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu1")))
    } yield {
      // Queries on Patient properties
      p1.size should be(1)
      p2.size should be(1)
      p3.size should be(0)

      // Check that query returns Patient with all data
      p4.foreach { p =>
        p.id should be >= 0L
        p.patientName.value should be("p1")
        p.patientID.value should be("s1")
        p.patientBirthDate.value should be("2000-01-01")
        p.patientSex.value should be("M")
      }

      // Queries on Study properties
      p5.size should be(1)
      p6.size should be(1)
      p7.size should be(0)

      // Queries on Series properties
      p8.size should be(1)

      // Queries on Equipments properties
      p9.size should be(1)

      // Queries on FrameOfReferences properties
      p10.size should be(1)

      // Test like query
      p11.size should be(1)

      // Test paging
      p12.size should be(0)
    }
  }

  it should "return the correct number of studies for studies queries" in {
    for {
      s1 <- dao.queryStudies(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1")))
      s2 <- dao.queryStudies(0, 1, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1")))
      s3 <- dao.queryStudies(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1")))
      s4 <- dao.queryStudies(0, 10, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
      s5 <- dao.queryStudies(0, 10, None, orderAscending = true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2")))
      s6 <- dao.queryStudies(0, 10, None, orderAscending = true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1")))
    } yield {
      // Queries on Study properties
      s1.size should be(1)

      // Check that query returns Studies with all data
      s2.foreach(s => {
        s.id should be >= 0L
        s.patientId should be >= 0L
        s.studyInstanceUID.value should be("stuid1")
        s.studyDescription.value should be("stdesc1")
        s.studyDate.value should be("19990101")
        s.studyID.value should be("stid1")
        s.accessionNumber.value should be("acc1")
        s.patientAge.value should be("12Y")
      })

      // Queries on Patient properties
      s3.size should be(2)

      // Queries on Series properties
      s4.size should be(1)

      // Queries on Equipments properties
      s5.size should be(1)

      // Queries on FrameOfReferences properties
      s6.size should be(1)
    }
  }

  it should "return the correct number of series for series queries" in {
    for {
      s1 <- dao.querySeries(0, 10, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
      s2 <- dao.querySeries(0, 1, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
      s3 <- dao.querySeries(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1")))
      s4 <- dao.querySeries(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1")))
      s5 <- dao.querySeries(0, 10, None, orderAscending = true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2")))
      s6 <- dao.querySeries(0, 10, None, orderAscending = true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1")))
    } yield {
      // Queries on Series properties
      s1.size should be(1)

      // Check that query returns Studies with all data
      s2.foreach(s => {
        s.id should be >= 0L
        s.studyId should be >= 0L
        s.seriesDescription.value should be("sedesc1")
        s.seriesDate.value should be("19990101")
        s.modality.value should be("NM")
        s.protocolName.value should be("prot1")
        s.bodyPartExamined.value should be("bodypart1")
      })

      // Queries on Patient properties
      s3.size should be(4)

      // Queries on Studies properties
      s4.size should be(2)

      // Queries on Equipments properties
      s5.size should be(1)

      // Queries on FrameOfReferences properties
      s6.size should be(2)
    }
  }

  it should "return the correct number of images for image queries" in {
    for {
      i1 <- dao.queryImages(0, 10, None, orderAscending = true, Seq(QueryProperty("sopInstanceUID", QueryOperator.EQUALS, "souid1")))
      i2 <- dao.queryImages(0, 1, None, orderAscending = true, Seq(QueryProperty("sopInstanceUID", QueryOperator.EQUALS, "souid1")))
      i3 <- dao.queryImages(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1")))
      i4 <- dao.queryImages(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1")))
      i5 <- dao.queryImages(0, 10, None, orderAscending = true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2")))
      i6 <- dao.queryImages(0, 10, None, orderAscending = true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1")))
      i7 <- dao.queryImages(0, 10, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
    } yield {
      // Queries on Image properties
      i1.size should be(1)

      // Check that query returns Studies with all data
      i2.foreach(i => {
        i.id should be >= 0L
        i.seriesId should be >= 0L
        i.sopInstanceUID.value should be("souid1")
        i.imageType.value should be("PRIMARY/RECON/TOMO")
        i.instanceNumber.value should be("1")
      })

      // Queries on Patient properties
      i3.size should be(8)

      // Queries on Studies properties
      i4.size should be(4)

      // Queries on Equipments properties
      i5.size should be(2)

      // Queries on FrameOfReferences properties
      i6.size should be(4)

      // Queries on Series properties
      i7.size should be(2)
    }
  }

  it should "return the correct number of flat series for flat series queries" in {
    for {
      f1 <- dao.queryFlatSeries(0, 10, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
      f2 <- dao.queryFlatSeries(0, 1, None, orderAscending = true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1")))
      f3 <- dao.queryFlatSeries(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1")))
      f4 <- dao.queryFlatSeries(0, 10, None, orderAscending = true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1")))
      f5 <- dao.queryFlatSeries(0, 10, None, orderAscending = true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2")))
      f6 <- dao.queryFlatSeries(0, 10, None, orderAscending = true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1")))
    } yield {
      // Queries on Series properties
      f1.size should be(1)

      // Check that query returns Studies with all data
      f2.foreach(f => {
        f.id should be >= 0L
        f.series.studyId should be >= 0L
        f.series.seriesDescription.value should be("sedesc1")
        f.series.seriesDate.value should be("19990101")
        f.series.modality.value should be("NM")
        f.series.protocolName.value should be("prot1")
        f.series.bodyPartExamined.value should be("bodypart1")
      })

      // Queries on Patient properties
      f3.size should be(4)

      // Queries on Studies properties
      f4.size should be(2)

      // Queries on Equipments properties
      f5.size should be(1)

      // Queries on FrameOfReferences properties
      f6.size should be(2)
    }
  }

  it should "support listing flat series complete with series, study and patient information" in {
    for {
      flatSeries <- dao.flatSeries(0, 20, None, orderAscending = true, filter = None)
    } yield {
      flatSeries.length should be(4)
      flatSeries.head.series should not be null
      flatSeries.head.study should not be null
      flatSeries.head.patient should not be null
    }
  }

  it should "support accessing a single flat series by id" in {
    for {
      series <- dao.series
      flatSeries <- Future.sequence(series.map(s => dao.flatSeriesById(s.id).map((s, _))))
    } yield {
      flatSeries.foreach {
        case (s, fs) =>
          fs shouldBe defined
          fs.get.id shouldBe s.id
      }

      succeed
    }
  }

}

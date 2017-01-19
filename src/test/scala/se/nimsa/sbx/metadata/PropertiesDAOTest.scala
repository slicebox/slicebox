package se.nimsa.sbx.metadata

import akka.util.Timeout
import org.h2.jdbc.JdbcSQLException
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class PropertiesDAOTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  val dbConfig = TestUtil.createTestDb("propertiesdaotest")
  implicit val timeout = Timeout(30.seconds)

  val metaDataDao = new MetaDataDAO(dbConfig)
  val propertiesDao = new PropertiesDAO(dbConfig)
  val seriesTypeDao = new SeriesTypeDAO(dbConfig)

  override def beforeAll() =
    await(for {
      _ <- seriesTypeDao.create()
      _ <- metaDataDao.create()
      _ <- propertiesDao.create()
    } yield Unit)

  override def afterEach() =
    await(Future.sequence(Seq(
      propertiesDao.clear(),
      metaDataDao.clear(),
      seriesTypeDao.clear()
    )))

  "The properties db" should "be empty before anything has been added" in {
    for {
      ss <- propertiesDao.listSeriesSources
      st <- propertiesDao.listSeriesTags
      sst <- seriesTypeDao.listSeriesSeriesTypes
    } yield {
      ss should be(empty)
      st should be(empty)
      sst should be(empty)
    }
  }

  it should "cascade delete linked series sources when a patient is deleted" in {
    for {
      (dbPatient1, (_, _), (_, _, _, _), (_, _, _, _, _, _, _, _)) <- insertMetaDataAndProperties()
      ss1 <- propertiesDao.seriesSources
      _ <- metaDataDao.deletePatient(dbPatient1.id)
      ss2 <- propertiesDao.seriesSources
    } yield {
      ss1 should have length 4
      ss2 shouldBe empty
    }
  }

  it should "not support adding a series source which links to a non-existing series" in {
    recoverToSucceededIf[JdbcSQLException] {
      propertiesDao.insertSeriesSource(SeriesSource(666, Source(SourceType.USER, "user", 1)))
    }
  }

  it should "support filtering flat series by source" in {
    for {
      _ <- insertMetaDataAndProperties()
      f1 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq.empty)
      f2 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      f3 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 2)), Seq.empty, Seq.empty)
    } yield {
      f1.size should be(4)
      f2.size should be(1)
      f3.size should be(0)
    }
  }

  it should "support filtering patients by source" in {
    for {
      _ <- insertMetaDataAndProperties()
      p1 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq.empty)
      p2 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      p3 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 2)), Seq.empty, Seq.empty)
      p4 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.UNKNOWN, 1)), Seq.empty, Seq.empty)
    } yield {
      p1.size should be(1)
      p2.size should be(1)
      p3.size should be(0)
      p4.size should be(0)
    }
  }

  it should "support filtering studies by source" in {
    for {
      _ <- insertMetaDataAndProperties()
      p <- metaDataDao.patients.map(_.head)
      s1 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq.empty, Seq.empty)
      s2 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      s3 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq(SourceRef(SourceType.BOX, 2)), Seq.empty, Seq.empty)
      s4 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq(SourceRef(SourceType.UNKNOWN, 1)), Seq.empty, Seq.empty)
    } yield {
      s1.size should be(2)
      s2.size should be(1)
      s3.size should be(0)
      s4.size should be(0)
    }
  }

  it should "support filtering series by source" in {
    for {
      _ <- insertMetaDataAndProperties()
      (st1, st2) <- metaDataDao.studies.map(ss => ss.zip(ss.tail).head)
      s1 <- propertiesDao.seriesForStudy(0, 20, st1.id, Seq.empty, Seq.empty, Seq.empty)
      s2 <- propertiesDao.seriesForStudy(0, 20, st1.id, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      s3 <- propertiesDao.seriesForStudy(0, 20, st2.id, Seq(SourceRef(SourceType.SCP, 1)), Seq.empty, Seq.empty)
      s4 <- propertiesDao.seriesForStudy(0, 20, st2.id, Seq(SourceRef(SourceType.DIRECTORY, 1)), Seq.empty, Seq.empty)
      s5 <- propertiesDao.seriesForStudy(0, 20, st1.id, Seq(SourceRef(SourceType.BOX, 2)), Seq.empty, Seq.empty)
      s6 <- propertiesDao.seriesForStudy(0, 20, st1.id, Seq(SourceRef(SourceType.SCP, 2)), Seq.empty, Seq.empty)
    } yield {
      s1.size should be(2)
      s2.size should be(1)
      s3.size should be(1)
      s4.size should be(1)
      s5.size should be(0)
      s6.size should be(0)
    }
  }

  it should "support filtering flat series by series tag" in {
    for {
      _ <- insertMetaDataAndProperties()
      (st1, st2) <- propertiesDao.seriesTags.map(st => st.zip(st.tail).head)
      f1 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(st1.id, st2.id))
      f2 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(st1.id))
      f3 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(st1.id, 666))
      f4 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(666))
    } yield {
      f1.size should be(3)
      f2.size should be(2)
      f3.size should be(2)
      f4.size should be(0)
    }
  }

  it should "support filtering patients by series tag" in {
    for {
      _ <- insertMetaDataAndProperties()
      (st1, st2) <- propertiesDao.seriesTags.map(st => st.zip(st.tail).head)
      p1 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(st1.id, st2.id))
      p2 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(st1.id))
      p3 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(st1.id, 666))
      p4 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(666))
    } yield {
      p1.size should be(1)
      p2.size should be(1)
      p3.size should be(1)
      p4.size should be(0)
    }
  }

  it should "support filtering studies by series tag" in {
    for {
      _ <- insertMetaDataAndProperties()
      p <- metaDataDao.patients.map(_.head)
      (st1, st2) <- propertiesDao.seriesTags.map(st => st.zip(st.tail).head)
      s1 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq.empty, Seq(st1.id, st2.id))
      s2 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq.empty, Seq(st1.id))
      s3 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq.empty, Seq(st1.id, 666))
      s4 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq.empty, Seq(666))
    } yield {
      s1.size should be(2)
      s2.size should be(1)
      s3.size should be(1)
      s4.size should be(0)
    }
  }

  it should "support filtering series by series tag" in {
    for {
      _ <- insertMetaDataAndProperties()
      (st1, st2) <- propertiesDao.seriesTags.map(st => st.zip(st.tail).head)
      (stu1, stu2) <- metaDataDao.studies.map(ss => ss.zip(ss.tail).head)
      s1 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq.empty, Seq(st1.id, st2.id))
      s2 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq.empty, Seq(st1.id))
      s3 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq.empty, Seq(st1.id, 666))
      s4 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq.empty, Seq(666))
      s5 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq.empty, Seq(st1.id, st2.id))
      s6 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq.empty, Seq(st1.id))
      s7 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq.empty, Seq(666))
    } yield {
      s1.size should be(2)
      s2.size should be(2)
      s3.size should be(2)
      s4.size should be(0)
      s5.size should be(1)
      s6.size should be(0)
      s7.size should be(0)
    }
  }

  it should "support filtering patients by series type" in {
    for {
      _ <- insertMetaDataAndProperties()
      (st1, st2) <- seriesTypeDao.listSeriesTypes(0,2).map(st => st.zip(st.tail).head)
      p1 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq(st1.id), Seq.empty)
      p2 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq(st1.id, st2.id), Seq.empty)
      p3 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq(st1.id, st2.id, 666), Seq.empty)
      p4 <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq(666), Seq.empty)
    } yield {
      p1.size should be(1)
      p2.size should be(1)
      p3.size should be(1)
      p4.size should be(0)
    }
  }

  it should "support filtering studies by series type" in {
    for {
      _ <- insertMetaDataAndProperties()
      p <- metaDataDao.patients.map(_.head)
      (st1, st2) <- seriesTypeDao.listSeriesTypes(0,2).map(st => st.zip(st.tail).head)
      s1 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq(st1.id), Seq.empty)
      s2 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq(st1.id, st2.id), Seq.empty)
      s3 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq(st1.id, st2.id, 666), Seq.empty)
      s4 <- propertiesDao.studiesForPatient(0, 20, p.id, Seq.empty, Seq(666), Seq.empty)
    } yield {
      s1.size should be(1)
      s2.size should be(2)
      s3.size should be(2)
      s4.size should be(0)
    }
  }

  it should "support filtering series by series type" in {
    for {
      _ <- insertMetaDataAndProperties()
      (stu1, stu2) <- metaDataDao.studies.map(ss => ss.zip(ss.tail).head)
      (st1, st2) <- seriesTypeDao.listSeriesTypes(0,2).map(st => st.zip(st.tail).head)
      s1 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq(st1.id), Seq.empty)
      s2 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq(st1.id, st2.id), Seq.empty)
      s3 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq(st1.id, st2.id, 666), Seq.empty)
      s4 <- propertiesDao.seriesForStudy(0, 20, stu1.id, Seq.empty, Seq(666), Seq.empty)
      s5 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq(st1.id), Seq.empty)
      s6 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq(st1.id, st2.id), Seq.empty)
      s7 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq(st1.id, st2.id, 666), Seq.empty)
      s8 <- propertiesDao.seriesForStudy(0, 20, stu2.id, Seq.empty, Seq(666), Seq.empty)
    } yield {
      s1.size should be(2)
      s2.size should be(2)
      s3.size should be(2)
      s4.size should be(0)
      s5.size should be(0)
      s6.size should be(1)
      s7.size should be(1)
      s8.size should be(0)
    }
  }

  it should "support filtering flat series by series type" in {
    for {
      _ <- insertMetaDataAndProperties()
      (st1, st2) <- seriesTypeDao.listSeriesTypes(0,2).map(st => st.zip(st.tail).head)
      f1 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq(st1.id), Seq.empty)
      f2 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq(st1.id, st2.id), Seq.empty)
      f3 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq(st1.id, st2.id, 666), Seq.empty)
      f4 <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq(666), Seq.empty)
    } yield {
      f1.size should be(2)
      f2.size should be(3)
      f3.size should be(3)
      f4.size should be(0)
    }
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when listing patients" in {
    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq.empty)

      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq.empty, Seq.empty)

      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq.empty)

      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)

      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)

      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
      _ <- propertiesDao.patients(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when listing studies" in {
    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq.empty, Seq.empty, Seq.empty)
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.studiesForPatient(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when listing series" in {
    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq.empty, Seq.empty, Seq.empty)
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.seriesForStudy(0, 20, 1, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when listing flat series" in {
    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq.empty)

      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq.empty, Seq.empty)

      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq.empty)

      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq.empty)

      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq.empty)

      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq.empty, Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq.empty, Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, None, Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, None, orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
      _ <- propertiesDao.flatSeries(0, 20, Some("patientID"), orderAscending = true, Some("filter"), Seq(SourceRef(SourceType.BOX, 1)), Seq(1), Seq(1))
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying patients" in {
    val sr = Seq(SourceRef(SourceType.USER, 1))
    val qo = Some(QueryOrder("patientName", orderAscending = true))
    val qp = Seq(QueryProperty("modality", QueryOperator.EQUALS, "NM"))
    val qf1 = Some(QueryFilters(Seq.empty, Seq.empty, Seq.empty))
    val qf2 = Some(QueryFilters(Seq.empty, Seq.empty, Seq(1)))
    val qf3 = Some(QueryFilters(Seq.empty, Seq(1), Seq.empty))
    val qf4 = Some(QueryFilters(Seq.empty, Seq(1), Seq(1)))
    val qf5 = Some(QueryFilters(sr, Seq.empty, Seq.empty))
    val qf6 = Some(QueryFilters(sr, Seq.empty, Seq(1)))
    val qf7 = Some(QueryFilters(sr, Seq(1), Seq.empty))
    val qf8 = Some(QueryFilters(sr, Seq(1), Seq(1)))

    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, None)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, None)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, None)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, None)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf1)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf1)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf1)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf1)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf2)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf2)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf2)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf2)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf3)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf3)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf3)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf3)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf4)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf4)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf4)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf4)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf5)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf5)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf5)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf5)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf6)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf6)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf6)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf6)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf7)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf7)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf7)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf7)
      _ <- propertiesDao.queryPatients(0, 20, None, Seq.empty, qf8)
      _ <- propertiesDao.queryPatients(0, 20, qo, Seq.empty, qf8)
      _ <- propertiesDao.queryPatients(0, 20, None, qp, qf8)
      _ <- propertiesDao.queryPatients(0, 20, qo, qp, qf8)
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying studies" in {
    val sr = Seq(SourceRef(SourceType.USER, 1))
    val qo = Some(QueryOrder("studyDate", orderAscending = true))
    val qp = Seq(QueryProperty("patientId", QueryOperator.EQUALS, "1"), QueryProperty("modality", QueryOperator.LIKE, "NM"))
    val qf1 = Some(QueryFilters(Seq.empty, Seq.empty, Seq.empty))
    val qf2 = Some(QueryFilters(Seq.empty, Seq.empty, Seq(1)))
    val qf3 = Some(QueryFilters(Seq.empty, Seq(1), Seq.empty))
    val qf4 = Some(QueryFilters(Seq.empty, Seq(1), Seq(1)))
    val qf5 = Some(QueryFilters(sr, Seq.empty, Seq.empty))
    val qf6 = Some(QueryFilters(sr, Seq.empty, Seq(1)))
    val qf7 = Some(QueryFilters(sr, Seq(1), Seq.empty))
    val qf8 = Some(QueryFilters(sr, Seq(1), Seq(1)))

    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, None)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, None)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, None)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, None)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf1)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf1)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf1)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf1)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf2)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf2)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf2)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf2)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf3)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf3)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf3)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf3)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf4)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf4)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf4)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf4)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf5)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf5)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf5)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf5)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf6)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf6)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf6)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf6)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf7)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf7)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf7)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf7)
      _ <- propertiesDao.queryStudies(0, 20, None, Seq.empty, qf8)
      _ <- propertiesDao.queryStudies(0, 20, qo, Seq.empty, qf8)
      _ <- propertiesDao.queryStudies(0, 20, None, qp, qf8)
      _ <- propertiesDao.queryStudies(0, 20, qo, qp, qf8)
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying series" in {
    val sr = Seq(SourceRef(SourceType.USER, 1))
    val qo = Some(QueryOrder("seriesDate", orderAscending = true))
    val qp = Seq(QueryProperty("studyId", QueryOperator.EQUALS, "1"), QueryProperty("modality", QueryOperator.LIKE, "NM"))
    val qf1 = Some(QueryFilters(Seq.empty, Seq.empty, Seq.empty))
    val qf2 = Some(QueryFilters(Seq.empty, Seq.empty, Seq(1)))
    val qf3 = Some(QueryFilters(Seq.empty, Seq(1), Seq.empty))
    val qf4 = Some(QueryFilters(Seq.empty, Seq(1), Seq(1)))
    val qf5 = Some(QueryFilters(sr, Seq.empty, Seq.empty))
    val qf6 = Some(QueryFilters(sr, Seq.empty, Seq(1)))
    val qf7 = Some(QueryFilters(sr, Seq(1), Seq.empty))
    val qf8 = Some(QueryFilters(sr, Seq(1), Seq(1)))

    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, None)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, None)
      _ <- propertiesDao.querySeries(0, 20, None, qp, None)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, None)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf1)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf1)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf1)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf1)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf2)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf2)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf2)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf2)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf3)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf3)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf3)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf3)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf4)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf4)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf4)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf4)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf5)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf5)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf5)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf5)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf6)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf6)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf6)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf6)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf7)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf7)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf7)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf7)
      _ <- propertiesDao.querySeries(0, 20, None, Seq.empty, qf8)
      _ <- propertiesDao.querySeries(0, 20, qo, Seq.empty, qf8)
      _ <- propertiesDao.querySeries(0, 20, None, qp, qf8)
      _ <- propertiesDao.querySeries(0, 20, qo, qp, qf8)
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying flat series" in {
    val sr = Seq(SourceRef(SourceType.USER, 1))
    val qo = Some(QueryOrder("seriesDate", orderAscending = true))
    val qp = Seq(QueryProperty("studyId", QueryOperator.EQUALS, "1"), QueryProperty("modality", QueryOperator.LIKE, "NM"))
    val qf1 = Some(QueryFilters(Seq.empty, Seq.empty, Seq.empty))
    val qf2 = Some(QueryFilters(Seq.empty, Seq.empty, Seq(1)))
    val qf3 = Some(QueryFilters(Seq.empty, Seq(1), Seq.empty))
    val qf4 = Some(QueryFilters(Seq.empty, Seq(1), Seq(1)))
    val qf5 = Some(QueryFilters(sr, Seq.empty, Seq.empty))
    val qf6 = Some(QueryFilters(sr, Seq.empty, Seq(1)))
    val qf7 = Some(QueryFilters(sr, Seq(1), Seq.empty))
    val qf8 = Some(QueryFilters(sr, Seq(1), Seq(1)))

    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, None)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, None)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, None)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, None)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf1)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf1)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf1)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf1)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf2)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf2)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf2)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf2)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf3)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf3)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf3)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf3)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf4)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf4)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf4)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf4)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf5)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf5)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf5)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf5)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf6)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf6)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf6)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf6)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf7)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf7)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf7)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf7)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, Seq.empty, qf8)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, Seq.empty, qf8)
      _ <- propertiesDao.queryFlatSeries(0, 20, None, qp, qf8)
      _ <- propertiesDao.queryFlatSeries(0, 20, qo, qp, qf8)
    } yield succeed
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying images" in {
    val sr = Seq(SourceRef(SourceType.USER, 1))
    val qo = Some(QueryOrder("instanceNumber", orderAscending = true))
    val qp = Seq(QueryProperty("studyId", QueryOperator.EQUALS, "1"), QueryProperty("modality", QueryOperator.LIKE, "NM"))
    val qf1 = Some(QueryFilters(Seq.empty, Seq.empty, Seq.empty))
    val qf2 = Some(QueryFilters(Seq.empty, Seq.empty, Seq(1)))
    val qf3 = Some(QueryFilters(Seq.empty, Seq(1), Seq.empty))
    val qf4 = Some(QueryFilters(Seq.empty, Seq(1), Seq(1)))
    val qf5 = Some(QueryFilters(sr, Seq.empty, Seq.empty))
    val qf6 = Some(QueryFilters(sr, Seq.empty, Seq(1)))
    val qf7 = Some(QueryFilters(sr, Seq(1), Seq.empty))
    val qf8 = Some(QueryFilters(sr, Seq(1), Seq(1)))

    for {
      _ <- insertMetaDataAndProperties()
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, None)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, None)
      _ <- propertiesDao.queryImages(0, 20, None, qp, None)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, None)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf1)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf1)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf1)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf1)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf2)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf2)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf2)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf2)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf3)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf3)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf3)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf3)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf4)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf4)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf4)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf4)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf5)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf5)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf5)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf5)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf6)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf6)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf6)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf6)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf7)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf7)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf7)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf7)
      _ <- propertiesDao.queryImages(0, 20, None, Seq.empty, qf8)
      _ <- propertiesDao.queryImages(0, 20, qo, Seq.empty, qf8)
      _ <- propertiesDao.queryImages(0, 20, None, qp, qf8)
      _ <- propertiesDao.queryImages(0, 20, qo, qp, qf8)
    } yield succeed
  }

  it should "throw IllegalArgumentException when querying patients for properties (columns) that does not exist" in {
    val qf = Some(QueryFilters(Seq(SourceRef(SourceType.USER, 1)), Seq.empty, Seq.empty))
    val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))

    recoverToSucceededIf[IllegalArgumentException] {
      insertMetaDataAndProperties().flatMap(_ => propertiesDao.queryPatients(0, 20, None, qp, None))
    }.flatMap { _ =>
      recoverToSucceededIf[IllegalArgumentException] {
        propertiesDao.queryPatients(0, 20, None, qp, qf)
      }
    }
  }

  it should "throw IllegalArgumentException when querying studies for properties (columns) that does not exist" in {
    val qf = Some(QueryFilters(Seq(SourceRef(SourceType.USER, 1)), Seq.empty, Seq.empty))
    val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))

    recoverToSucceededIf[IllegalArgumentException] {
      insertMetaDataAndProperties().flatMap(_ => propertiesDao.queryStudies(0, 20, None, qp, None))
    }.flatMap { _ =>
      recoverToSucceededIf[IllegalArgumentException] {
        propertiesDao.queryStudies(0, 20, None, qp, qf)
      }
    }
  }

  it should "throw IllegalArgumentException when querying series for properties (columns) that does not exist" in {
    val qf = Some(QueryFilters(Seq(SourceRef(SourceType.USER, 1)), Seq.empty, Seq.empty))
    val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))

    recoverToSucceededIf[IllegalArgumentException] {
      insertMetaDataAndProperties().flatMap(_ => propertiesDao.querySeries(0, 20, None, qp, None))
    }.flatMap { _ =>
      recoverToSucceededIf[IllegalArgumentException] {
        propertiesDao.querySeries(0, 20, None, qp, qf)
      }
    }
  }

  it should "throw IllegalArgumentException when querying flat series for properties (columns) that does not exist" in {
    val qf = Some(QueryFilters(Seq(SourceRef(SourceType.USER, 1)), Seq.empty, Seq.empty))
    val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))

    recoverToSucceededIf[IllegalArgumentException] {
      insertMetaDataAndProperties().flatMap(_ => propertiesDao.queryFlatSeries(0, 20, None, qp, None))
    }.flatMap { _ =>
      recoverToSucceededIf[IllegalArgumentException] {
        propertiesDao.queryFlatSeries(0, 20, None, qp, qf)
      }
    }
  }

  it should "throw IllegalArgumentException when querying images for properties (columns) that does not exist" in {
    val qf = Some(QueryFilters(Seq(SourceRef(SourceType.USER, 1)), Seq.empty, Seq.empty))
    val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))

    recoverToSucceededIf[IllegalArgumentException] {
      insertMetaDataAndProperties().flatMap(_ => propertiesDao.queryImages(0, 20, None, qp, None))
    }.flatMap { _ =>
      recoverToSucceededIf[IllegalArgumentException] {
        propertiesDao.queryImages(0, 20, None, qp, qf)
      }
    }
  }

  it should "remove a series tag when the last occurrence of it has been removed" in {
    for {
      (_, (_, _), (dbSeries1, dbSeries2, dbSeries3, _), (_, _, _, _, _, _, _, _)) <- insertMetaDataAndProperties()
      seriesTags1 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTags1.head.id, dbSeries1.id)
      seriesTags2 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTags1(1).id, dbSeries1.id)
      seriesTags3 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTags1.head.id, dbSeries2.id)
      seriesTags4 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTags1(1).id, dbSeries3.id)
      seriesTags5 <- propertiesDao.listSeriesTags
    } yield {
      seriesTags1.size should be(2)
      seriesTags1.map(_.name) should be(List("Tag1", "Tag2"))
      seriesTags2.size should be(2)
      seriesTags3.size should be(2)
      seriesTags4.size should be(1)
      seriesTags5.size should be(0)
    }
  }

  it should "remove a series tag when deleting a series if the series tag attached to the series was the last of its kind" in {
    for {
      (_, (_, _), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (_, _, _, _, _, _, _, _)) <- insertMetaDataAndProperties()
      st1 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.deleteFully(dbSeries4)
      st2 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.deleteFully(dbSeries1)
      st3 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.deleteFully(dbSeries2)
      st4 <- propertiesDao.listSeriesTags
      _ <- propertiesDao.deleteFully(dbSeries3)
      st5 <- propertiesDao.listSeriesTags
    } yield {
      st1.size should be(2)
      st2.size should be(2)
      st3.size should be(2)
      st4.size should be(1)
      st5.size should be(0)
    }
  }

  def insertMetaDataAndProperties() =
    for {
      (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) <- insertMetaData(metaDataDao)
      _ <- insertProperties(seriesTypeDao, propertiesDao, dbSeries1, dbSeries2, dbSeries3, dbSeries4, dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)
    } yield {
      (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8))
    }

}

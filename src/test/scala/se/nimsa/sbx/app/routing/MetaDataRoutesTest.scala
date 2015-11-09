package se.nimsa.sbx.app.routing

import scala.slick.driver.H2Driver
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.user.UserProtocol.UserRole._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.util.TestUtil
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.storage.PropertiesDAO
import spray.http.MultipartFormData
import spray.http.BodyPart

class MetaDataRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:metadataroutestest;DB_CLOSE_DELAY=-1"

  val dao = new MetaDataDAO(H2Driver)
  val seriesTypeDao = new SeriesTypeDAO(H2Driver)
  val propertiesDao = new PropertiesDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      dao.clear
    }
  }

  "Meta data routes" should "return 200 OK and return an empty list of images when asking for all images" in {
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(0)
    }
  }

  it should "return 200 OK when listing patients with valid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then    
    GetAsUser("/api/metadata/patients?orderby=PatientID") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(1)
    }
  }

  it should "return 404 Not Found when requesting a patient that does not exist" in {
    // given nothing

    // then
    GetAsUser("/api/metadata/patients/1234") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 400 Bad Request when listing patients with invalid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then
    GetAsUser("/api/metadata/patients?orderby=syntaxerror") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 200 OK and return patient when querying patients" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then
    val queryProperties = Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))
    val query = Query(0, 10, None, false, queryProperties)

    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(1)
    }
  }

  it should "be able to do like querying of patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate(""), PatientSex("")))
      dao.insert(Patient(-1, PatientName("p2"), PatientID("s2"), PatientBirthDate(""), PatientSex("")))
    }

    // then
    val query = Query(0, 10, None, false, Seq(QueryProperty("PatientName", QueryOperator.LIKE, "%p%")))

    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]

      patients.size should be(2)
      patients(0).patientName.value should be("p1")
      patients(1).patientName.value should be("p2")
    }
  }

  it should "be able to sort when querying patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate(""), PatientSex("")))
      dao.insert(Patient(-1, PatientName("p2"), PatientID("s2"), PatientBirthDate(""), PatientSex("")))
    }

    // then
    val query = Query(0, 10, Some("PatientName"), false, Seq[QueryProperty]())

    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]

      patients.size should be(2)
      patients(0).patientName.value should be("p2")
      patients(1).patientName.value should be("p1")
    }
  }

  it should "be able to page results when querying patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate(""), PatientSex("")))
      dao.insert(Patient(-1, PatientName("p2"), PatientID("s2"), PatientBirthDate(""), PatientSex("")))
    }

    // then
    val query = Query(1, 1, Some("PatientName"), false, Seq[QueryProperty]())

    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]

      patients.size should be(1)
      patients(0).patientName.value should be("p1")
    }
  }

  it should "return 200 OK and return studies when querying studies" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then
    val queryProperties = Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))
    val query = Query(0, 10, None, false, queryProperties)

    PostAsUser("/api/metadata/studies/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Study]].size should be(1)
    }
  }

  it should "return 200 OK and return series when querying series" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then
    val queryProperties = Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))
    val query = Query(0, 10, None, false, queryProperties)

    PostAsUser("/api/metadata/series/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Series]].size should be(1)
    }
  }

  it should "return 200 OK and return images when querying images" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then
    val queryProperties = Seq(QueryProperty("InstanceNumber", QueryOperator.EQUALS, "1"))
    val query = Query(0, 10, None, false, queryProperties)

    PostAsUser("/api/metadata/images/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].size should be(8)
    }
  }

  it should "return 200 OK and return flat series when querying flat series" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then
    val queryProperties = Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))
    val query = Query(0, 10, None, false, queryProperties)

    PostAsUser("/api/metadata/flatseries/query", query) ~> sealRoute(routes) ~> check {
      status should be(OK)
      responseAs[List[FlatSeries]].size should be(1)
    }
  }

  it should "return 200 OK when listing flat series" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then    
    GetAsUser("/api/metadata/flatseries") ~> routes ~> check {
      status should be(OK)
      responseAs[List[FlatSeries]].size should be(4)
    }
  }

  it should "return 200 OK when listing flat series with valid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      TestUtil.insertMetaData(dao)
    }

    // then    
    GetAsUser("/api/metadata/flatseries?orderby=PatientID") ~> routes ~> check {
      status should be(OK)
      responseAs[List[FlatSeries]].size should be(4)
    }
  }
  it should "return 400 Bad Request when listing flat series with invalid orderby parameter" in {
    // given

    // then
    GetAsUser("/api/metadata/flatseries?orderby=syntaxerror") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 404 NotFound when trying to label a series which does not exist" in {
    PostAsUser(s"/api/metadata/series/666/seriestags", SeriesTag(-1, "Tag1")) ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "be possible to label a series with series tags" in {
    val someSeries =
      db.withSession { implicit session =>
        TestUtil.insertMetaData(dao)
        dao.series.head
      }

    PostAsUser(s"/api/metadata/series/${someSeries.id}/seriestags", SeriesTag(-1, "Tag1")) ~> routes ~> check {
      status should be(Created)
      responseAs[SeriesTag].name should be("Tag1")
    }
    PostAsUser(s"/api/metadata/series/${someSeries.id}/seriestags", SeriesTag(-1, "Tag2")) ~> routes ~> check {
      status should be(Created)
      responseAs[SeriesTag].name should be("Tag2")
    }

    GetAsUser("/api/metadata/seriestags") ~> routes ~> check {
      val tags = responseAs[List[SeriesTag]]
      status should be(OK)
      tags.size should be(2)
      tags.map(_.name) should contain("Tag1")
      tags.map(_.name) should contain("Tag2")
    }
  }

  it should "be possible to list series tag for a series" in {
    val someSeries =
      db.withSession { implicit session =>
        TestUtil.insertMetaData(dao)
        dao.series.head
      }

    PostAsUser(s"/api/metadata/series/${someSeries.id}/seriestags", SeriesTag(-1, "Tag1")) ~> routes ~> check {
      status should be(Created)
      responseAs[SeriesTag].name should be("Tag1")
    }
    PostAsUser(s"/api/metadata/series/${someSeries.id}/seriestags", SeriesTag(-1, "Tag2")) ~> routes ~> check {
      status should be(Created)
      responseAs[SeriesTag].name should be("Tag2")
    }

    GetAsUser(s"/api/metadata/series/${someSeries.id}/seriestags") ~> routes ~> check {
      status should be(OK)
      responseAs[List[SeriesTag]].length should be(2)
    }
  }

  it should "be possible to delete series tags for a series" in {
    val someSeries =
      db.withSession { implicit session =>
        TestUtil.insertMetaData(dao)
        dao.series.head
      }

    PostAsUser(s"/api/metadata/series/${someSeries.id}/seriestags", SeriesTag(-1, "Tag1")) ~> routes ~> check {
      status should be(Created)
      responseAs[SeriesTag].name should be("Tag1")
    }

    val seriesTag =
      GetAsUser(s"/api/metadata/series/${someSeries.id}/seriestags") ~> routes ~> check {
        responseAs[List[SeriesTag]].head
      }

    DeleteAsUser(s"/api/metadata/series/${someSeries.id}/seriestags/${seriesTag.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser(s"/api/metadata/series/${someSeries.id}/seriestags") ~> routes ~> check {
      responseAs[List[SeriesTag]].size should be (0)
    }
  }

  it should "return 200 OK with the list of series types when asked to list series types for a specific series" in {
    val addedSeriesType = db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st0"))
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
    }

    val addedSeriesTypeRule = db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id))
    }

    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRuleAttribute(SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 0x00100010, "PatientName", None, None, "anon270"))
    }

    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    val addedSeriesId = PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be(Created)
      responseAs[Image].seriesId
    }
    // adding an image will trigger a series type update

    Thread.sleep(3000) // let the series type update run

    GetAsUser(s"/api/metadata/series/$addedSeriesId/seriestypes") ~> routes ~> check {
      status should be(OK)
      val seriesTypes = responseAs[List[SeriesType]]
      seriesTypes.length should be(1)
      seriesTypes(0).name should be("st1")
    }
  }

}
package se.nimsa.sbx.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.BadRequest
import spray.testkit.ScalatestRouteTest
import scala.concurrent.duration.DurationInt
import spray.httpx.SprayJsonSupport._
import se.nimsa.sbx.dicom.DicomHierarchy.Patient
import se.nimsa.sbx.dicom.DicomProtocol._
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import se.nimsa.sbx.dicom.DicomMetaDataDAO
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._

class MetaDataRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:metadataroutestest;DB_CLOSE_DELAY=-1"
  
  val dao = new DicomMetaDataDAO(H2Driver)
  
  val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val pat2 = Patient(-1, PatientName("p2"), PatientID("s2"), PatientBirthDate("2001-01-01"), PatientSex("F"))
  val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y"))
  val equipment1 = Equipment(-1, Manufacturer("manu1"), StationName("station1"))
  val for1 = FrameOfReference(-1, FrameOfReferenceUID("frid1"))
  val series1 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val image1 = Image(-1, -1, SOPInstanceUID("sopuid1"), ImageType("imageType1"), InstanceNumber("1"))
  
  override def beforeEach() {
    db.withSession { implicit session =>
      dao.create
    }
  }
  
  override def afterEach() {
    db.withSession { implicit session =>
      dao.drop
    }
  }
  
  "Meta data routes" should "return 200 OK and return an empty list of images when asking for all images" in {
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be (0)
    }
  }

  it should "return 200 OK when listing patients with valid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then    
    GetAsUser("/api/metadata/patients?orderby=PatientID") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be (1)
    }
  }
  
  it should "return 404 Not Found when requesting a patient that does not exist" in {
    // given nothing
    
    // then
    GetAsUser("/api/metadata/patients/1234") ~> routes ~> check {
      status should be (NotFound)
    }
  }
  
  it should "return 400 Bad Request when listing patients with invalid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then
    GetAsUser("/api/metadata/patients?orderby=syntaxerror") ~> routes ~> check {
      status should be(BadRequest)
    }
  }
  
  it should "return 200 OK and return patient when querying patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then
    val queryProperties = Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be (1)
    }
  }
  
  it should "be able to do like querying of patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
      dao.insert(pat2)
    }
    
    // then
    val query = Query(0, 10, Some("PatientName"), false, Seq(QueryProperty("PatientName", QueryOperator.LIKE, "%p%")))
    
    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]
      
      patients.size should be(2)
      patients(0).patientName.value should be("p2")
      patients(1).patientName.value should be("p1")
    }
  }
  
  it should "be able to sort when querying patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
      dao.insert(pat2)
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
      dao.insert(pat1)
      dao.insert(pat2)
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
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
    }
    
    // then
    val queryProperties = Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/studies/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Study]].size should be (1)
    }
  }
  
  it should "return 200 OK and return series when querying series" in {
    // given
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
      val dbEquipment = dao.insert(equipment1)
      val dbFor = dao.insert(for1)
      dao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
    }
    
    // then
    val queryProperties = Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/series/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Series]].size should be (1)
    }
  }
  
  it should "return 200 OK and return images when querying images" in {
    // given
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
      val dbEquipment = dao.insert(equipment1)
      val dbFor = dao.insert(for1)
      val dbSeries = dao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
      dao.insert(image1.copy(seriesId = dbSeries.id))
    }
    
    // then
    val queryProperties = Seq(QueryProperty("InstanceNumber", QueryOperator.EQUALS, "1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/images/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].size should be (1)
    }
  }
  
  it should "return 200 OK when listing flat series" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then    
    GetAsUser("/api/metadata/flatseries") ~> routes ~> check {
      status should be(OK)
      responseAs[List[FlatSeries]].size should be (0)
    }
  }
  
  it should "return 200 OK when listing flat series with valid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then    
    GetAsUser("/api/metadata/flatseries?orderby=PatientID") ~> routes ~> check {
      status should be(OK)
      responseAs[List[FlatSeries]].size should be (0)
    }
  }
  it should "return 400 Bad Request when listing flat series with invalid orderby parameter" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then
    GetAsUser("/api/metadata/flatseries?orderby=syntaxerror") ~> routes ~> check {
      status should be(BadRequest)
    }
  }
  
}
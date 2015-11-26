package se.nimsa.sbx.seriestype

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.scalatest._

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.util.Timeout.durationToTimeout
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._

class SeriesTypeServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("SeriesTypeServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:seriestypeserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val seriesTypeDao = new SeriesTypeDAO(dbProps.driver)
  val metaDataDao = new MetaDataDAO(dbProps.driver)

  db.withSession { implicit session =>
    metaDataDao.create
    seriesTypeDao.create
      seriesTypeDao.create
  }

  val seriesTypeService = system.actorOf(SeriesTypeServiceActor.props(dbProps, 1.minute), name = "SeriesTypeService")

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def afterEach() {
    db.withSession { implicit session =>
      seriesTypeDao.clear
    }
  }

  "A SeriesTypeServiceActor" should {

    "return all series types in database" in {
      db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      seriesTypeService ! GetSeriesTypes

      expectMsgPF() {
        case SeriesTypes(seriesTypes) =>
          seriesTypes.size should be(1)
          seriesTypes(0).name should be("st1")
      }
    }

    "be able to add new series type" in {
      val seriesType = SeriesType(-1, "s1")

      seriesTypeService ! AddSeriesType(seriesType)

      expectMsgPF() {
        case SeriesTypeAdded(returnedSeriesType) =>
          returnedSeriesType.id should be > (0L)
          returnedSeriesType.name should be(seriesType.name)
      }

      seriesTypeService ! GetSeriesTypes

      expectMsgPF() {
        case SeriesTypes(seriesTypes) =>
          seriesTypes.size should be(1)
          seriesTypes(0).name should be(seriesType.name)
      }
    }

    "not be able to add series type with duplicate name" in {
      val seriesType = SeriesType(-1, "s1")

      seriesTypeService ! AddSeriesType(seriesType)
      expectMsgPF() {
        case SeriesTypeAdded(_) => true
      }

      seriesTypeService ! AddSeriesType(seriesType)
      expectMsgPF() {
        case Failure(e) if e.isInstanceOf[IllegalArgumentException] => true
      }

      seriesTypeService ! GetSeriesTypes

      expectMsgPF() {
        case SeriesTypes(seriesTypes) =>
          seriesTypes.size should be(1)
      }
    }

    "be able to update existing series type" in {
      val seriesType = SeriesType(-1, "s1")

      seriesTypeService ! AddSeriesType(seriesType)

      val addedSeriesType = expectMsgPF() {
        case SeriesTypeAdded(seriesType) =>
          seriesType
      }

      val updatedSeriesType = SeriesType(addedSeriesType.id, "s2");
      seriesTypeService ! UpdateSeriesType(updatedSeriesType)
      expectMsg(SeriesTypeUpdated)

      seriesTypeService ! GetSeriesTypes

      expectMsgPF() {
        case SeriesTypes(seriesTypes) =>
          seriesTypes.size should be(1)
          seriesTypes(0).name should be(updatedSeriesType.name)
      }
    }

    "be able to delete existing series type" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      seriesTypeService ! RemoveSeriesType(addedSeriesType.id)
      expectMsgPF() {
        case SeriesTypeRemoved(seriesTypeId) =>
          seriesTypeId should be(addedSeriesType.id)
      }

      seriesTypeService ! GetSeriesTypes

      expectMsgPF() {
        case SeriesTypes(seriesTypes) =>
          seriesTypes.size should be(0)
      }
    }

    "be able to add and get series type rule" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      val seriesTypeRule = SeriesTypeRule(-1, addedSeriesType.id)

      seriesTypeService ! AddSeriesTypeRule(seriesTypeRule)

      expectMsgPF() {
        case SeriesTypeRuleAdded(returnedSeriesTypeRule) =>
          returnedSeriesTypeRule.id should be > (0L)
          returnedSeriesTypeRule.seriesTypeId should be(addedSeriesType.id)
      }

      seriesTypeService ! GetSeriesTypeRules(addedSeriesType.id)

      expectMsgPF() {
        case SeriesTypeRules(seriesTypeRules) =>
          seriesTypeRules.size should be(1)
          seriesTypeRules(0).seriesTypeId should be(addedSeriesType.id)
      }
    }

    "be able to delete existing series type rule" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      val addedSeriesTypeRule = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id))
      }

      seriesTypeService ! RemoveSeriesTypeRule(addedSeriesTypeRule.id)
      expectMsgPF() {
        case SeriesTypeRuleRemoved(seriesTypeRuleId) =>
          seriesTypeRuleId should be(addedSeriesTypeRule.id)
      }

      seriesTypeService ! GetSeriesTypeRules(addedSeriesType.id)

      expectMsgPF() {
        case SeriesTypeRules(seriesTypeRules) =>
          seriesTypeRules.size should be(0)
      }
    }

    "deletes series type rules when a series type is deleted" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      val addedSeriesTypeRule = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id))
      }

      seriesTypeService ! RemoveSeriesType(addedSeriesType.id)

      expectMsgPF() {
        case SeriesTypeRemoved(seriesTypeId) => true
      }

      seriesTypeService ! GetSeriesTypeRules(addedSeriesType.id)

      expectMsgPF() {
        case SeriesTypeRules(seriesTypeRules) =>
          seriesTypeRules.size should be(0)
      }
    }

    "be able to add and get series type rule attribute" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      val addedSeriesTypeRule = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id))
      }

      val seriesTypeRuleAttribute = SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test")

      seriesTypeService ! AddSeriesTypeRuleAttribute(seriesTypeRuleAttribute)

      expectMsgPF() {
        case SeriesTypeRuleAttributeAdded(returnedSeriesTypeRuleAttribute) =>
          returnedSeriesTypeRuleAttribute.id should be > (0L)
          returnedSeriesTypeRuleAttribute.seriesTypeRuleId should be(addedSeriesTypeRule.id)
      }

      seriesTypeService ! GetSeriesTypeRuleAttributes(addedSeriesTypeRule.id)

      expectMsgPF() {
        case SeriesTypeRuleAttributes(seriesTypeRuleAttributes) =>
          seriesTypeRuleAttributes.size should be(1)
          seriesTypeRuleAttributes(0).seriesTypeRuleId should be(addedSeriesTypeRule.id)
      }
    }

    "be able to delete existing series type rule attribute" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      val addedSeriesTypeRule = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id))
      }

      val addedSeriesTypeRuleAttribute = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRuleAttribute(SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test"))
      }

      seriesTypeService ! RemoveSeriesTypeRuleAttribute(addedSeriesTypeRuleAttribute.id)
      expectMsgPF() {
        case SeriesTypeRuleAttributeRemoved(seriesTypeRuleAttributeId) =>
          seriesTypeRuleAttributeId should be(addedSeriesTypeRuleAttribute.id)
      }

      seriesTypeService ! GetSeriesTypeRuleAttributes(addedSeriesTypeRule.id)

      expectMsgPF() {
        case SeriesTypeRuleAttributes(seriesTypeRuleAttributes) =>
          seriesTypeRuleAttributes.size should be(0)
      }
    }

    "deletes series type rule attributes when a series type rule is deleted" in {
      val addedSeriesType = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
      }

      val addedSeriesTypeRule = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id))
      }

      val addedSeriesTypeRuleAttribute = db.withSession { implicit session =>
        seriesTypeDao.insertSeriesTypeRuleAttribute(SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test"))
      }

      seriesTypeService ! RemoveSeriesTypeRule(addedSeriesTypeRule.id)
      expectMsgPF() {
        case SeriesTypeRuleRemoved(seriesTypeRuleId) => true
      }

      seriesTypeService ! GetSeriesTypeRuleAttributes(addedSeriesTypeRule.id)

      expectMsgPF() {
        case SeriesTypeRuleAttributes(seriesTypeRuleAttributes) =>
          seriesTypeRuleAttributes.size should be(0)
      }
    }
  }
}
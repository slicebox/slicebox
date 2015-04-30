package se.nimsa.sbx.seriestype

import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import akka.testkit.ImplicitSender
import se.nimsa.sbx.app.DbProps
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import scala.slick.driver.H2Driver
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.jdbc.JdbcBackend.Database
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import akka.actor.Status.Failure

class SeriesTypeServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("SeriesTypeServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:seriestypeserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)
  
  val seriesTypeDao = new SeriesTypeDAO(H2Driver)
  
  db.withSession { implicit session =>
    seriesTypeDao.create
  }
  
  val seriesTypeService = system.actorOf(SeriesTypeServiceActor.props(dbProps), name = "SeriesTypeService")
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
  override def beforeEach() {
    db.withSession { implicit session =>
      seriesTypeDao.create
    }
  }
  
  override def afterEach() {
    db.withSession { implicit session =>
      seriesTypeDao.drop
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
        case Failure(_) => true
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
  }
}
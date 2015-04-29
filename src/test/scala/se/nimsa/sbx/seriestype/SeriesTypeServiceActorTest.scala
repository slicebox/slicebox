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

    "return all servie types in database" in {
      db.withSession { implicit session =>
        
        seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))

        seriesTypeService ! GetSeriesTypes
        
        expectMsgPF() {
          case SeriesTypes(serviceTypes) =>
            serviceTypes.size should be(1)
            serviceTypes(0).name should be("st1")
        }
      }
    }
  }
}
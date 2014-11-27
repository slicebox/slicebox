package se.vgregion.db

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import akka.actor.Props
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.jdbc.JdbcBackend.Database
import scala.slick.driver.H2Driver
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.db.DbProtocol._

class DbActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("DbDataTestSystem"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  private val db = Database.forURL("jdbc:h2:mem:dbtest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  
  val dbActor = system.actorOf(Props(classOf[DbActor], db, new DAO(H2Driver)))

  val pat1 = Patient("p1", "s1")
  val pat2 = Patient("p2", "s2")
  
  val study1 = Study(pat1, "19990101", "stuid1")
  val study2 = Study(pat2, "20000101", "stuid2")

  val series1 = Series(study1, "19990101", "souid1")
  val series2 = Series(study2, "20000101", "souid2")
  val series3 = Series(study1, "19990102", "souid3")

  val image1 = Image(series1, "souid1", "file1")
  val image2 = Image(series2, "souid2", "file2")
  val image3 = Image(series3, "souid3", "file3")
  
  dbActor ! CreateTables
  
  "A DbActor" must {

    "return an empty list when no metadata exists" in {
      dbActor ! GetImageEntries
      expectMsg(Images(Seq.empty[Image]))
    }

    "return a list of size three when three entries are added" in {
      dbActor ! InsertImage(image1)
      dbActor ! InsertImage(image2)
      dbActor ! InsertImage(image3)
      expectNoMsg
      dbActor ! GetImageEntries
      expectMsgPF() {
        case Images(images) if images.size == 3 => true
      }
    }
    
    "produce properly grouped entries" in {
      dbActor ! GetPatientEntries
      expectMsg(Patients(Seq(pat1, pat2)))

      dbActor ! GetStudyEntries(pat1)
      expectMsg(Studies(Seq(study1)))      
      dbActor ! GetStudyEntries(pat2)
      expectMsg(Studies(Seq(study2)))
      
      dbActor ! GetSeriesEntries(study1)
      expectMsg(SeriesCollection(Seq(series1, series3)))
      dbActor ! GetSeriesEntries(study2)
      expectMsg(SeriesCollection(Seq(series2)))
      
      dbActor ! GetImageEntries(series1)
      expectMsg(Images(Seq(image1)))
      dbActor ! GetImageEntries(series2)
      expectMsg(Images(Seq(image2)))
      dbActor ! GetImageEntries(series3)
      expectMsg(Images(Seq(image3)))
    }
  }

}

package se.nimsa.sbx.forwarding

import java.nio.file.Files
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest._
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.TestUtil
import akka.actor.Props
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import se.nimsa.sbx.app.GeneralProtocol._
import ForwardingProtocol._

class ForwardingServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("ForwardingServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:forwardingserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val forwardingDao = new ForwardingDAO(H2Driver)

  db.withSession { implicit session =>
    forwardingDao.create
  }

  override def afterEach() =
    db.withSession { implicit session =>
      forwardingDao.clear
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  val forwardingService = system.actorOf(Props(new ForwardingServiceActor(dbProps, 1000.hours)(Timeout(30.seconds))), name = "ForwardingService")

  "A ForwardingServiceActor" should {

    "support adding and listing forwarding rules" in {

      forwardingService ! GetForwardingRules
      expectMsg(ForwardingRules(List.empty))

      val rule1 = ForwardingRule(-1, Source(SourceType.SCP, "My SCP", 1), Destination(DestinationType.BOX, "Remote box", 1), false)
      val rule2 = ForwardingRule(-1, Source(SourceType.USER, "Admin", 1), Destination(DestinationType.BOX, "Remote box", 1), false)

      forwardingService ! AddForwardingRule(rule1)
      forwardingService ! AddForwardingRule(rule2)

      val dbRule1 = (expectMsgType[ForwardingRuleAdded]).forwardingRule
      val dbRule2 = (expectMsgType[ForwardingRuleAdded]).forwardingRule

      forwardingService ! GetForwardingRules
      expectMsg(ForwardingRules(List(dbRule1, dbRule2)))
    }

    "support deleting forwading rules" in {
      val rule1 = ForwardingRule(-1, Source(SourceType.SCP, "My SCP", 1), Destination(DestinationType.BOX, "Remote box", 1), false)
      
      forwardingService ! AddForwardingRule(rule1)
      val dbRule1 = (expectMsgType[ForwardingRuleAdded]).forwardingRule

      
      forwardingService ! GetForwardingRules
      expectMsg(ForwardingRules(List(dbRule1)))
      
      forwardingService ! RemoveForwardingRule(dbRule1.id)
      expectMsg(ForwardingRuleRemoved(dbRule1.id))
      
      forwardingService ! GetForwardingRules
      expectMsg(ForwardingRules(List.empty))
    }
  }

  
}
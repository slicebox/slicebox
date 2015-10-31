package se.nimsa.sbx.user

import java.nio.file.Files
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestActorRef
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.TestUtil
import UserProtocol._

class UserServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("UserServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:userserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val dao = new UserDAO(H2Driver)

  db.withSession { implicit session =>
    dao.create
  }

  val userService = TestActorRef(new UserServiceActor(dbProps, "admin", "admin", 1000000))
  val userActor = userService.underlyingActor

  override def afterEach() =
    db.withSession { implicit session =>
      dao.clear
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "A UserServiceActor" should {

    "cleanup expired sessions regularly" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER))
        dao.insertSession(ApiSession(-1, user.id, "token1", "ip1", "user agent1", System.currentTimeMillis))
        dao.insertSession(ApiSession(-1, user.id, "token2", "ip2", "user agent2", 0))

        dao.listUsers should have length 2
        dao.listSessions should have length 2

        userActor.removeExpiredSessions()

        dao.listSessions should have length 1
      }
    }

    "refresh a non-expired session defined by a token, ip and user agent" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER))
        val sessionTime = System.currentTimeMillis - 1000
        dao.insertSession(ApiSession(-1, user.id, "token", "ip", "user agent", sessionTime))

        userActor.getAndRefreshUser(AuthKey(Some("token"), Some("ip"), Some("user agent")))

        val optionalSession = dao.userSessionByTokenIpAndUserAgent("token", "ip", "user agent")
        optionalSession.isDefined shouldBe true
        optionalSession.get._2.lastUpdated shouldBe >(sessionTime)
      }
    }

    "not refresh an expired session defined by a token, ip and user agent" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER))
        val sessionTime = 1000
        dao.insertSession(ApiSession(-1, user.id, "token", "ip", "user agent", sessionTime))

        userActor.getAndRefreshUser(AuthKey(Some("token"), Some("ip"), Some("user agent")))

        val optionalSession = dao.userSessionByTokenIpAndUserAgent("token", "ip", "user agent")
        optionalSession.isDefined shouldBe true
        optionalSession.get._2.lastUpdated shouldBe (sessionTime)
      }
    }

    "create a session if none exists and update it if one exists" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER))

        dao.listSessions should have length 0
        
        val session1 = userActor.createOrUpdateSession(user, "ip", "userAgent")
        dao.listSessions should have length 1
        
        Thread.sleep(100)
        
        val session2 = userActor.createOrUpdateSession(user, "ip", "userAgent")
        dao.listSessions should have length 1
        session2.lastUpdated shouldBe > (session1.lastUpdated)
      }
    }
    
    "remove a session based on user id, IP and user agent when logging out" in {
      db.withSession { implicit session =>
        val user = dao.insert(ApiUser(-1, "user", UserRole.USER))
        val session1 = userActor.createOrUpdateSession(user, "ip", "userAgent")
        dao.listSessions should have length 1
        userActor.deleteSession(user, AuthKey(Some(session1.token), Some("Other IP"), Some(session1.userAgent)))
        dao.listSessions should have length 1
        userActor.deleteSession(user, AuthKey(Some(session1.token), Some(session1.ip), Some(session1.userAgent)))
        dao.listSessions should have length 0
      }      
    }

  }

}
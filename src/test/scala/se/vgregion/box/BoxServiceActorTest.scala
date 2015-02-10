package se.vgregion.box

import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import java.nio.file.Files
import scala.slick.driver.H2Driver
import se.vgregion.app.DbProps
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Props
import se.vgregion.util.TestUtil
import se.vgregion.box.BoxProtocol._

class BoxServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)
  
  val storage = Files.createTempDirectory("slicebox-test-storage-")
  
  val boxDao = new BoxDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
  }
  
  val boxServiceActorRef = _system.actorOf(Props(new BoxServiceActor(dbProps, storage, "testhost", 1234)))
  
  override def beforeEach() {
    db.withSession { implicit session =>
      boxDao.listBoxes.foreach(box => {
        boxDao.removeBox(box.id)
      })
      
      boxDao.listOutboxEntries.foreach(outboxEntry => {
        boxDao.removeOutboxEntry(outboxEntry.id)
      })
      
      boxDao.listInboxEntries.foreach(inboxEntry => {
        boxDao.removeInboxEntry(inboxEntry.id)
      })
    }
  }
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }
  
  "A BoxServiceActor" should {

    "create inbox entry for first file in transaction" in {
      db.withSession { implicit session =>
      
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL))
        
        boxServiceActorRef ! UpdateInbox(remoteBox.token, 123, 1, 2)
        
        expectMsg(InboxUpdated(remoteBox.token, 123, 1, 2))
        
        val inboxEntries = boxDao.listInboxEntries
        
        inboxEntries.size should be(1)
        inboxEntries.foreach(inboxEntry => {
          inboxEntry.remoteBoxId should be(remoteBox.id)
          inboxEntry.transactionId should be(123)
          inboxEntry.receivedImageCount should be(1)
          inboxEntry.totalImageCount should be(2)
          
        })
      }
    }
    
    "inbox entry is updated for next file in transaction" in {
      db.withSession { implicit session =>
      
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL))
        
        boxServiceActorRef ! UpdateInbox(remoteBox.token, 123, 1, 3)
        expectMsg(InboxUpdated(remoteBox.token, 123, 1, 3))
        
        boxServiceActorRef ! UpdateInbox(remoteBox.token, 123, 2, 3)
        expectMsg(InboxUpdated(remoteBox.token, 123, 2, 3))
        
        val inboxEntries = boxDao.listInboxEntries
        
        inboxEntries.size should be(1)
        inboxEntries.foreach(inboxEntry => {
          inboxEntry.remoteBoxId should be(remoteBox.id)
          inboxEntry.transactionId should be(123)
          inboxEntry.receivedImageCount should be(2)
          inboxEntry.totalImageCount should be(3)
          
        })
      }
    }
  }
}
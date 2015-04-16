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

  val boxServiceActorRef = _system.actorOf(Props(new BoxServiceActor(dbProps, storage, "http://testhost:1234")))

  override def beforeEach() {
    db.withSession { implicit session =>
      boxDao.listBoxes.foreach(box =>
        boxDao.removeBox(box.id))

      boxDao.listOutboxEntries.foreach(outboxEntry =>
        boxDao.removeOutboxEntry(outboxEntry.id))

      boxDao.listInboxEntries.foreach(inboxEntry =>
        boxDao.removeInboxEntry(inboxEntry.id))

      boxDao.listAttributeValueMappingEntries.foreach(avm =>
        boxDao.removeAttributeValueMappingEntry(avm.id))
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "A BoxServiceActor" should {

    "create inbox entry for first file in transaction" in {
      db.withSession { implicit session =>

        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

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

        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

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

    "returns OuboxEmpty for poll message when outbox is empty" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxServiceActorRef ! PollOutbox(remoteBox.token)

        expectMsg(OutboxEmpty)
      }
    }

    "returns first outbox entry when receiving poll message" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBox.id, 987, 1, 2, 123, false))

        boxServiceActorRef ! PollOutbox(remoteBox.token)

        expectMsgPF() {
          case OutboxEntry(id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageId, failed) =>
            remoteBoxId should be(remoteBox.id)
            transactionId should be(987)
            sequenceNumber should be(1)
            totalImageCount should be(2)
            imageId should be(123)
        }
      }
    }

    "remove all attribute value mappings when all outbox entries for a transaction have been removed" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        val mappings = Seq(
          AttributeValueMapping(0x00101010, "A", "B"),
          AttributeValueMapping(0x00101012, "C", "D"),
          AttributeValueMapping(0x00101014, "E", "F"))

        val imageIds = Seq(123L, 124L, 125L)

        boxServiceActorRef ! SendImagesToRemoteBox(remoteBox.id, BoxSendData(imageIds, mappings))

        expectMsgPF() {
          case ImagesSent(remoteBoxId, entityIds) =>
            remoteBoxId should be(remoteBox.id)
            entityIds should be(imageIds)
        }

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(3)

        val transactionId = outboxEntries(0).transactionId
        outboxEntries.map(_.transactionId).forall(_ == transactionId) should be (true)
        
        boxDao.listAttributeValueMappingEntries.size should be(3)
        boxDao.attributeValueMappingsByTransactionId(transactionId).size should be(3)

        outboxEntries.map(_.id).foreach(id => boxServiceActorRef ! RemoveOutboxEntry(id))
        
        expectMsgType[OutboxEntryRemoved]
        expectMsgType[OutboxEntryRemoved]
        expectMsgType[OutboxEntryRemoved]
        
        boxDao.listAttributeValueMappingEntries.isEmpty should be (true)        
        boxDao.attributeValueMappingsByTransactionId(transactionId).isEmpty should be (true)
      }
    }

    "remove all attribute value mappings when last outbox entry has been processed" in {
      db.withSession { implicit session =>
        val token = "abc"
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", token, "https://someurl.com", BoxSendMethod.POLL, false))

        val mappings = Seq(
          AttributeValueMapping(0x00101010, "A", "B"),
          AttributeValueMapping(0x00101012, "C", "D"),
          AttributeValueMapping(0x00101014, "E", "F"))

        val imageIds = Seq(123L, 124L, 125L)

        boxServiceActorRef ! SendImagesToRemoteBox(remoteBox.id, BoxSendData(imageIds, mappings))

        expectMsgPF() {
          case ImagesSent(remoteBoxId, entityIds) =>
            remoteBoxId should be(remoteBox.id)
            entityIds should be(imageIds)
        }

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(3)

        val transactionId = outboxEntries(0).transactionId
        outboxEntries.map(_.transactionId).forall(_ == transactionId) should be (true)
        
        boxDao.listAttributeValueMappingEntries.size should be(3)
        boxDao.attributeValueMappingsByTransactionId(transactionId).size should be(3)

        outboxEntries.foreach(entry => boxServiceActorRef ! DeleteOutboxEntry(token, entry.transactionId, entry.sequenceNumber))
        outboxEntries.foreach(entry => boxServiceActorRef ! DeleteOutboxEntry(token, entry.transactionId, entry.sequenceNumber))
        
        expectMsg(OutboxEntryDeleted)
        expectMsg(OutboxEntryDeleted)
        expectMsg(OutboxEntryDeleted)
        
        boxDao.listAttributeValueMappingEntries.isEmpty should be (true)        
        boxDao.attributeValueMappingsByTransactionId(transactionId).isEmpty should be (true)
      }
    }

  }
}
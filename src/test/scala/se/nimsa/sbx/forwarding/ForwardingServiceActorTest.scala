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
import akka.actor.Actor
import akka.actor.Props
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
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

  val forwardingService = system.actorOf(Props(new ForwardingServiceActor(dbProps, 1000.hours)(Timeout(30.seconds))), name = "ForwardingService")

  case object ResetDeletedImages
  case object GetDeletedImages
  val storageService = system.actorOf(Props(new Actor {
    var deletedImages = Seq.empty[Long]
    def receive = {
      case GetSourceForSeries(seriesId) =>
        sender ! Some(SeriesSource(22, SourceTypeId(SourceType.USER, 35)))
      case DeleteImage(imageId) =>
        deletedImages = deletedImages :+ imageId
        sender ! ImageDeleted(imageId)
      case ResetDeletedImages =>
        deletedImages = Seq.empty[Long]
      case GetDeletedImages =>
        sender ! deletedImages
    }
  }), name = "StorageService")

  val boxService = system.actorOf(Props(new Actor {
    def receive = {
      case SendToRemoteBox(remoteBoxId, tagValues) =>
        sender ! ImagesAddedToOutbox(remoteBoxId, tagValues.map(_.imageId))
    }
  }), name = "BoxService")

  override def afterEach() =
    db.withSession { implicit session =>
      forwardingDao.clear
      storageService ! ResetDeletedImages
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "A ForwardingServiceActor" should {

    "support adding and listing forwarding rules" in {

      forwardingService ! GetForwardingRules
      expectMsg(ForwardingRules(List.empty))

      val rule1 = scpToBoxRule
      val rule2 = userToBoxRule

      forwardingService ! AddForwardingRule(rule1)
      forwardingService ! AddForwardingRule(rule2)

      val dbRule1 = (expectMsgType[ForwardingRuleAdded]).forwardingRule
      val dbRule2 = (expectMsgType[ForwardingRuleAdded]).forwardingRule

      forwardingService ! GetForwardingRules
      expectMsg(ForwardingRules(List(dbRule1, dbRule2)))
    }

    "support deleting forwading rules" in {
      val rule1 = scpToBoxRule

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

  "not forward an added image if there are no forwarding rules" in {
    forwardingService ! ImageAdded(image1)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) =>
        images should be(empty)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingRules should be(empty)
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
  }

  "not forward an added image if there are no matching forwarding rules" in {
    val rule = scpToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1)
    expectMsg(ImagesAddedToForwardingQueue(List.empty))

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
  }

  "forward an added image if there are matching forwarding rules" in {
    val rule = userToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) => images.length should be (1)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(1)
      forwardingDao.listForwardingTransactionImages.length should be(1)
    }
  }

  "not send queued images if the corresponding transaction was recently updated" in {
    val rule = userToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) => images.length should be (1)
    }

    forwardingService ! PollForwardingQueue
    expectMsg(TransactionsEnroute(List.empty))

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(1)
      forwardingDao.listForwardingTransactionImages.length should be(1)
    }
  }

  "send queued images if the corresponding transaction has expired (i.e. has not been updated in a while)" in {
    val rule = userToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) => images.length should be (1)
    }

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be (1)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(1)
      forwardingDao.listForwardingTransactionImages.length should be(1)
      val transaction = forwardingDao.listForwardingTransactions.head
      transaction.enroute should be(true)
      transaction.delivered should be(false)
    }
  }

  "mark forwarding transaction as delivered after images have been sent" in {
    val rule = userToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) => images.length should be (1)
    }

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be (1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be (defined)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(1)
      forwardingDao.listForwardingTransactionImages.length should be(1)
      val transaction = forwardingDao.listForwardingTransactions.head
      transaction.enroute should be(false)
      transaction.delivered should be(true)
    }
  }

  "remove transaction, transaction images and stored images when a forwarding transaction is finalized" in {
    val rule = userToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) => images.length should be (1)
    }

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be (1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be (defined)
    }

    forwardingService ! FinalizeSentTransactions
    expectMsgPF() {
      case TransactionsFinalized(transactionsToRemove, idsOfDeletedImages) =>
        transactionsToRemove.length should be(1)
        idsOfDeletedImages.length should be(1)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
    storageService ! GetDeletedImages
    expectMsg(Seq(image.id))
  }

  "remove transaction, transaction images but not stored images when a forwarding transaction is finalized for a rule with keepImages set to true" in {
    val rule = userToBoxRuleKeepImages

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image)
    expectMsgPF() {
      case ImagesAddedToForwardingQueue(images) => images.length should be (1)
    }

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be (1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be (defined)
    }

    forwardingService ! FinalizeSentTransactions
    expectMsgPF() {
      case TransactionsFinalized(transactionsToRemove, idsOfDeletedImages) =>
        transactionsToRemove.length should be (1)
        idsOfDeletedImages should be (empty)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
    storageService ! GetDeletedImages
    expectMsg(Seq.empty)
  }
  
  
  def scpToBoxRule = ForwardingRule(-1, Source(SourceType.SCP, "My SCP", 1), Destination(DestinationType.BOX, "Remote box", 1), false)
  def userToBoxRule = ForwardingRule(-1, Source(SourceType.USER, "Admin", 35), Destination(DestinationType.BOX, "Remote box", 1), false)
  def userToBoxRuleKeepImages = ForwardingRule(-1, Source(SourceType.USER, "Admin", 35), Destination(DestinationType.BOX, "Remote box", 1), true)
  def image1 = Image(-1, 22, SOPInstanceUID("sopuid1"), ImageType("it"), InstanceNumber("in"))

  def expireTransaction(index: Int) =
    db.withSession { implicit session =>
      val transaction = forwardingDao.listForwardingTransactions(session)(index)
      forwardingDao.updateForwardingTransaction(transaction.copy(lastUpdated = 0))
    }
}
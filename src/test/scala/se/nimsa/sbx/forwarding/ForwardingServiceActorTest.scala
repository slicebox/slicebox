package se.nimsa.sbx.forwarding

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class ForwardingServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("ForwardingServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:forwardingserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val forwardingDao = new ForwardingDAO(H2Driver)

  db.withSession { implicit session =>
    forwardingDao.create
  }

  case class SetSource(source: Source)
  val metaDataService = system.actorOf(Props(new Actor {
    var source: Option[Source] = None
    def receive = {
      case GetImage(imageId) =>
        sender ! (imageId match {
          case 1 => Some(image1)
          case 2 => Some(image2)
          case 3 => Some(image3)
          case _ => None
        })
      case GetSourceForSeries(seriesId) =>
        sender ! source.map(SeriesSource(-1, _))
      case DeleteMetaData(imageId) =>
        sender ! MetaDataDeleted(None, None, None, None)
      case SetSource(newSource) =>
        source = Option(newSource)
    }
  }), name = "MetaDataService")

  case object ResetDeletedImages
  case object GetDeletedImages
  val storageService = system.actorOf(Props(new Actor {
    var deletedImages = Seq.empty[Long]

    def receive = {
      case DeleteDicomData(image) =>
        deletedImages = deletedImages :+ image.id
        sender ! DicomDataDeleted(image)
      case ResetDeletedImages =>
        deletedImages = Seq.empty[Long]
      case GetDeletedImages =>
        sender ! deletedImages
    }
  }), name = "StorageService")

  val boxService = system.actorOf(Props(new Actor {

    def receive = {
      case SendToRemoteBox(box, tagValues) =>
        sender ! ImagesAddedToOutgoing(box.id, tagValues.map(_.imageId))
    }
  }), name = "BoxService")

  val forwardingService = system.actorOf(Props(new ForwardingServiceActor(dbProps, 1000.hours)(Timeout(30.seconds))), name = "ForwardingService")

  override def afterEach() =
    db.withSession { implicit session =>
      forwardingDao.clear
      metaDataService ! SetSource(null)
      storageService ! ResetDeletedImages
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A ForwardingServiceActor" should {

    "support adding and listing forwarding rules" in {

      forwardingService ! GetForwardingRules(0, 1)
      expectMsg(ForwardingRules(List.empty))

      val rule1 = scpToBoxRule
      val rule2 = userToBoxRule

      forwardingService ! AddForwardingRule(rule1)
      forwardingService ! AddForwardingRule(rule2)

      val dbRule1 = expectMsgType[ForwardingRuleAdded].forwardingRule
      val dbRule2 = expectMsgType[ForwardingRuleAdded].forwardingRule

      forwardingService ! GetForwardingRules(0, 10)
      expectMsg(ForwardingRules(List(dbRule1, dbRule2)))
    }

    "support deleting forwarding rules" in {
      val rule1 = scpToBoxRule

      forwardingService ! AddForwardingRule(rule1)
      val dbRule1 = expectMsgType[ForwardingRuleAdded].forwardingRule

      forwardingService ! GetForwardingRules(0, 10)
      expectMsg(ForwardingRules(List(dbRule1)))

      forwardingService ! RemoveForwardingRule(dbRule1.id)
      expectMsg(ForwardingRuleRemoved(dbRule1.id))

      forwardingService ! GetForwardingRules(0, 1)
      expectMsg(ForwardingRules(List.empty))
    }
  }

  "not forward an added image if there are no forwarding rules" in {
    forwardingService ! ImageAdded(image1, scpSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(image, applicableRules) =>
        image shouldBe image1
        applicableRules shouldBe empty
    }
    expectNoMsg
    db.withSession { implicit session =>
      forwardingDao.listForwardingRules(0, 1) should be(empty)
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
  }

  "not forward an added image if there are no matching forwarding rules" in {
    val rule = scpToBoxRule

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1, userSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(image, applicableRules) =>
        image shouldBe image1
        applicableRules shouldBe empty
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
  }

  "forward an added image if there are matching forwarding rules" in {
    val rule = userToBoxRule
    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1, userSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(image, applicableRules) =>
        image shouldBe image1
        applicableRules should have length 1
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(1)
      forwardingDao.listForwardingTransactionImages.length should be(1)
    }
  }

  "create multiple transactions when there are multiple rules with the same source and an image with that source is received" in {
    val rule1 = userToBoxRule
    val rule2 = userToAnotherBoxRule

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule1)
    forwardingService ! AddForwardingRule(rule2)
    expectMsgType[ForwardingRuleAdded]
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1, userSource, overwrite = false)
    expectMsgPF() {
      case ImageRegisteredForForwarding(image, applicableRules) =>
        image shouldBe image1
        applicableRules should have length 2
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(2)
      forwardingDao.listForwardingTransactionImages.length should be(2)
    }
  }

  "not send queued images if the corresponding transaction was recently updated" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    forwardingService ! PollForwardingQueue
    expectMsg(TransactionsEnroute(List.empty))

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions.length should be(1)
      forwardingDao.listForwardingTransactionImages.length should be(1)
    }
  }

  "send queued images if the corresponding transaction has expired (i.e. has not been updated in a while)" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
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

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be(defined)
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

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be(defined)
    }

    forwardingService ! FinalizeSentTransactions
    expectMsgPF() {
      case TransactionsFinalized(removedTransactions) =>
        removedTransactions.length should be(1)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }

    // wait for deletion of images to finish
    expectNoMsg

    storageService ! GetDeletedImages
    expectMsg(Seq(image.id))
  }

  "remove transaction, transaction images but not stored images when a forwarding transaction is finalized for a rule with keepImages set to true" in {
    val rule = userToBoxRuleKeepImages

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    val image = image1
    forwardingService ! ImageAdded(image, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImagesSent(rule.destination, Seq(image.id))
    expectMsgPF() {
      case TransactionMarkedAsDelivered(transactionMaybe) => transactionMaybe should be(defined)
    }

    forwardingService ! FinalizeSentTransactions
    expectMsgPF() {
      case TransactionsFinalized(removedTransactions) =>
        removedTransactions.length should be(1)
    }

    db.withSession { implicit session =>
      forwardingDao.listForwardingTransactions should be(empty)
      forwardingDao.listForwardingTransactionImages should be(empty)
    }
    storageService ! GetDeletedImages
    expectMsg(Seq.empty)
  }

  "create a new transaction for a newly added image as soon as a transaction has been marked as enroute" in {
    val rule = userToBoxRule

    metaDataService ! SetSource(userSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image1, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    expireTransaction(0)

    forwardingService ! PollForwardingQueue
    expectMsgPF() {
      case TransactionsEnroute(transactions) => transactions.length should be(1)
    }

    forwardingService ! ImageAdded(image2, userSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    db.withSession { implicit session =>
      val transactions = forwardingDao.listForwardingTransactions
      transactions.length should be(2)
      val transaction1 = transactions.head
      val transaction2 = transactions(1)
      transaction1.enroute should be(true)
      transaction1.delivered should be(false)
      transaction2.enroute should be(false)
      transaction2.delivered should be(false)
    }

  }

  "create a single transaction when forwarding multiple transactions in succession with a box source" in {
    val rule = boxToBoxRule

    metaDataService ! SetSource(boxSource)

    forwardingService ! AddForwardingRule(rule)
    expectMsgType[ForwardingRuleAdded]

    forwardingService ! ImageAdded(image3, boxSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    forwardingService ! ImageAdded(image1, boxSource, overwrite = false)
    expectMsgType[ImageRegisteredForForwarding]

    db.withSession { implicit session =>
      val transactions = forwardingDao.listForwardingTransactions
      transactions.length should be(1)
    }
  }

  def scpSource = Source(SourceType.SCP, "My SCP", 1)
  def userSource = Source(SourceType.USER, "Admin", 35)
  def boxSource = Source(SourceType.BOX, "Source box", 11)
  def boxDestination = Destination(DestinationType.BOX, "Remote box", 1)
  def boxDestination2 = Destination(DestinationType.BOX, "Another remote box", 2)

  def scpToBoxRule = ForwardingRule(-1, scpSource, boxDestination, keepImages = false)
  def userToBoxRule = ForwardingRule(-1, userSource, boxDestination, keepImages = false)
  def userToAnotherBoxRule = ForwardingRule(-1, userSource, boxDestination2, keepImages = false)
  def boxToBoxRule = ForwardingRule(-1, boxSource, boxDestination, keepImages = false)
  def userToBoxRuleKeepImages = ForwardingRule(-1, userSource, boxDestination, keepImages = true)

  def image1 = Image(1, 22, SOPInstanceUID("sopuid1"), ImageType("it"), InstanceNumber("in1"))
  def image2 = Image(2, 22, SOPInstanceUID("sopuid2"), ImageType("it"), InstanceNumber("in2"))
  def image3 = Image(3, 22, SOPInstanceUID("sopuid3"), ImageType("it"), InstanceNumber("in3"))

  def expireTransaction(index: Int) =
    db.withSession { implicit session =>
      val transaction = forwardingDao.listForwardingTransactions(session)(index)
      forwardingDao.updateForwardingTransaction(transaction.copy(updated = 0))
    }
}
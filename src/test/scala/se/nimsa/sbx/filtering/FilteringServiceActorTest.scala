package se.nimsa.sbx.filtering

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.dicom.data.TagPath
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.filtering.FilteringProtocol.TagFilterType.WHITELIST
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.duration.DurationInt

class FilteringServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  val dbConfig = TestUtil.createTestDb("filteringserviceactortest")

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)
  val db = dbConfig.db
  val filteringDao = new FilteringDAO(dbConfig)

  await(filteringDao.create())

  val filteringService = system.actorOf(Props(new FilteringServiceActor(filteringDao)(Timeout(30.seconds))), name = "FilteringService")

  def this() = this(ActorSystem("FilteringServiceActorTestSystem"))

  override def afterEach() = {
    await(filteringDao.clear())
  }

  def dump() = {
    println(s"db contents:")
    val cs = await(filteringDao.dump())
    println(cs)
    println(s"No more in db")
  }

  override def afterAll = TestKit.shutdownActorSystem(system)

  def getTagFilterSpec1 = FilteringProtocol.TagFilterSpec(-1, "filter1", WHITELIST, Seq(TagPath.fromTag(0x0008000d)))

  def getTagFilterSpec2 = FilteringProtocol.TagFilterSpec(-1, "filter2", WHITELIST, Seq(TagPath.fromTag(0x00100010), TagPath.fromTag(0x00100020)))

  case class SetSource(source: Source)

  "A FilteringServiceActor" should {

    "support adding and listing TagFilters" in {

      filteringService ! GetTagFilters(0, 1)
      expectMsg(TagFilterSpecs(List.empty))

      val tagFilterSpec1 = getTagFilterSpec1
      val tagFilterSpec2 = getTagFilterSpec2

      filteringService ! AddTagFilter(tagFilterSpec1)
      filteringService ! AddTagFilter(tagFilterSpec2)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification
      val filter2 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilters(0, 10)
      expectMsg(TagFilterSpecs(List(filter1.copy(tags = Seq()), filter2.copy(tags = Seq()))))
    }

    "Return complete TagFilterSpec" in {
      val tagFilterSpec1 = getTagFilterSpec1

      filteringService ! AddTagFilter(tagFilterSpec1)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilter(filter1.id)
      expectMsg(Some(filter1))

    }

    "Delete tagFilters" in {

      filteringService ! GetTagFilters(0, 1)
      expectMsg(TagFilterSpecs(List.empty))

      val tagFilterSpec1 = getTagFilterSpec1
      val tagFilterSpec2 = getTagFilterSpec2

      filteringService ! AddTagFilter(tagFilterSpec1)
      filteringService ! AddTagFilter(tagFilterSpec2)

      val filter1 = expectMsgType[TagFilterAdded].filterSpecification
      val filter2 = expectMsgType[TagFilterAdded].filterSpecification

      filteringService ! GetTagFilters(0, 10)
      val msg = expectMsgType[TagFilterSpecs]
      msg.tagFilterSpecs.size shouldBe 2

      filteringService ! RemoveTagFilter(filter1.id)
      filteringService ! RemoveTagFilter(filter2.id)

      expectMsg(TagFilterRemoved(filter1.id))
      expectMsg(TagFilterRemoved(filter2.id))

      filteringService ! GetTagFilters(0, 10)
      expectMsg(TagFilterSpecs(List.empty))

    }
  }
}


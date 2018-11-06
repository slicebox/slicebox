package se.nimsa.sbx.filtering

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.filtering.FilteringProtocol.TagFilterType.{BLACKLIST, WHITELIST}
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class FilteringServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  val dbConfig = TestUtil.createTestDb("filteringserviceactortest")

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  val db = dbConfig.db
  val filteringDao = new FilteringDAO(dbConfig)

  await(filteringDao.create())

  val filteringService: ActorRef = system.actorOf(Props(new FilteringServiceActor(filteringDao)), name = "FilteringService")

  def this() = this(ActorSystem("FilteringServiceActorTestSystem"))

  override def afterEach(): Unit = {
    await(filteringDao.clear())
  }

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val tagFilter1 = TagFilter(-1, "filter1", WHITELIST)
  val tagFilter2 = TagFilter(-1, "filter2", WHITELIST)
  val tagFilter3 = TagFilter(-1, "filter3", BLACKLIST)

  "A FilteringServiceActor" should {

    "return all filtering specifications for a source" in {
      filteringService ! AddTagFilter(tagFilter1)
      val filter1 = expectMsgType[TagFilterAdded].tagFilter
      filteringService ! AddTagFilterTagPath(TagFilterTagPath(-1, filter1.id, TagPath.fromTag(Tag.PatientName)))
      val filterTagPath1 = expectMsgType[TagFilterTagPathAdded].tagFilterTagPath

      filteringService ! AddTagFilter(tagFilter2)
      val filter2 = expectMsgType[TagFilterAdded].tagFilter
      filteringService ! AddTagFilterTagPath(TagFilterTagPath(-1, filter2.id, TagPath.fromTag(Tag.PatientID)))
      val filterTagPath2 = expectMsgType[TagFilterTagPathAdded].tagFilterTagPath

      filteringService ! AddTagFilter(tagFilter3)
      val filter3 = expectMsgType[TagFilterAdded].tagFilter
      filteringService ! AddTagFilterTagPath(TagFilterTagPath(-1, filter3.id, TagPath.fromTag(Tag.StudyDate)))
      expectMsgType[TagFilterTagPathAdded]

      filteringService ! AddSourceTagFilter(SourceTagFilter(-1, SourceType.BOX, "my box", 23, filter1.id, filter1.name))
      expectMsgType[SourceTagFilterAdded]
      filteringService ! AddSourceTagFilter(SourceTagFilter(-1, SourceType.BOX, "my box", 23, filter2.id, filter2.name))
      expectMsgType[SourceTagFilterAdded]
      filteringService ! AddSourceTagFilter(SourceTagFilter(-1, SourceType.USER, "john", 42, filter3.id, filter3.name))
      expectMsgType[SourceTagFilterAdded]

      filteringService ! GetFilterSpecsForSource(SourceRef(SourceType.BOX, 23))
      val specs = expectMsgType[Seq[TagFilterSpec]]

      specs shouldBe Seq(
        TagFilterSpec(filter1.name, filter1.tagFilterType, Seq(filterTagPath1.tagPath)),
        TagFilterSpec(filter2.name, filter2.tagFilterType, Seq(filterTagPath2.tagPath)))
    }

    "delete filter association when system event SourceDeleted for corresponding source is received" in {
      filteringService ! AddTagFilter(tagFilter1)
      val filter1 = expectMsgType[TagFilterAdded].tagFilter
      val source = Source(SourceType.BOX, "my box", 1)
      filteringService ! AddSourceTagFilter(SourceTagFilter(-1, source.sourceType, source.sourceName, source.sourceId, filter1.id, filter1.name))
      val sourceTagFilter1 = expectMsgType[SourceTagFilterAdded].sourceTagFilter
      filteringService ! AddSourceTagFilter(SourceTagFilter(-1, SourceType.USER, "john", 123, filter1.id, filter1.name))
      val sourceTagFilter2 = expectMsgType[SourceTagFilterAdded].sourceTagFilter
      filteringService ! GetSourceTagFilters(0, 100)
      expectMsg(SourceTagFilters(List(sourceTagFilter1, sourceTagFilter2)))
      system.eventStream.publish(SourceDeleted(SourceRef(source.sourceType, source.sourceId)))
      expectNoMessage()
      filteringService ! GetSourceTagFilters(0, 100)
      expectMsg(SourceTagFilters(List(sourceTagFilter2)))
    }

  }
}


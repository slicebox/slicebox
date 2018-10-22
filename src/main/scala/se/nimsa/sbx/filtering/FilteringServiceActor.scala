package se.nimsa.sbx.filtering

import akka.actor.{Actor, ActorSystem, Props}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import se.nimsa.sbx.app.GeneralProtocol.{SourceDeleted, SourceRef}
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.FutureUtil.await

import scala.concurrent.{ExecutionContext, Future}

class FilteringServiceActor(filteringDAO: FilteringDAO)(implicit timeout: Timeout) extends Actor {
  val log = Logging(context.system, this)

  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContext = context.dispatcher

  val emptyTagPathsSeq: Seq[TagFilterTagPath] = Seq()

  override def preStart(): Unit =
    context.system.eventStream.subscribe(context.self, classOf[SourceDeleted])

  def receive: Receive = LoggingReceive {
    case AddTagFilter(tagFilterSpec) =>
      sender ! insertTagFilter(tagFilterSpec)
    case gtf: GetTagFilters =>
      sender ! getTagFilters(gtf.startIndex, gtf.count)
    case RemoveTagFilter(tagFilterId) =>
      sender ! removeTagFilter(tagFilterId)
    case GetTagFilter(tagFilterId) =>
      sender ! getTagFilter(tagFilterId)
    case GetFilterForSource(source) =>
      sender ! getTagFilterForSource(source)
    case AddSourceFilterAssociation(sourceTagFilter) =>
      sender ! setTagFilterForSource(sourceTagFilter)
    case RemoveSourceTagFilter(sourceFilterId) =>
      sender ! removeSourceTagFilter(sourceFilterId)
    case RemoveFilterForSource(sourceRef) =>
      sender ! removeFilterForSource(sourceRef)
    case SourceDeleted(sourceRef) =>
      removeFilterForSource(sourceRef)
    case gstf: GetSourceTagFilters =>
      sender ! getSourceTagFilters(gstf.startIndex, gstf.count)
  }

  def insertTagFilter(tagFilterSpec: TagFilterSpec): TagFilterAdded = {
    await(filteringDAO.createOrUpdateTagFilter(tagFilterSpec).map(TagFilterAdded))
  }

  def getTagFilters(startIndex: Long, count: Long): TagFilterSpecs = {
    val tagFilters = filteringDAO.listTagFilters(startIndex, count)
    await(tagFilters.map(_.map(TagFilterSpec(_))).map(TagFilterSpecs))
  }

  def getSourceTagFilters(startIndex: Long, count: Long): SourceTagFilters = {
    val sourceTagFilters = filteringDAO.listSourceTagFilters(startIndex, count)
    await(sourceTagFilters.map(SourceTagFilters))
  }

  def removeTagFilter(tagFilterId: Long): TagFilterRemoved =
    await(filteringDAO.removeTagFilter(tagFilterId).map(_ => TagFilterRemoved(tagFilterId)))

  def getTagFilter(tagFilterId: Long): Option[TagFilterSpec] =
    await(filteringDAO.getTagFilter(tagFilterId))

  def getTagFilterForSource(source: SourceRef): Option[TagFilterSpec] =
    await(
      filteringDAO.getSourceFilter(source).flatMap {
        _.map {
          sourceFilter => filteringDAO.getTagFilter(sourceFilter.tagFilterId)
        }.getOrElse(Future(None))
      }
    )

  def setTagFilterForSource(sourceTagFilter: SourceTagFilter): SourceTagFilter =
    await(filteringDAO.createOrUpdateSourceFilter(sourceTagFilter))

  def removeFilterForSource(sourceRef: SourceRef): SourceTagFilterRemoved =
    await(filteringDAO.removeSourceFilter(sourceRef).map(_ => SourceTagFilterRemoved()))

  def removeSourceTagFilter(sourceFilterId: Long): SourceTagFilterRemoved =
    await(filteringDAO.removeSourceFilter(sourceFilterId).map(_ => SourceTagFilterRemoved()))
}

object FilteringServiceActor {
  def props(filteringDAO: FilteringDAO)(implicit timeout: Timeout): Props = Props(new FilteringServiceActor(filteringDAO))
}

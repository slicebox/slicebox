package se.nimsa.sbx.filtering

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.FutureUtil.await

import scala.concurrent.Future

class FilteringServiceActor(filteringDAO: FilteringDAO)(implicit timeout: Timeout) extends Actor {
  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val emptyTagPathsSeq: Seq[TagFilterTagPath] = Seq()

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
    case SetFilterForSource(source, tagFilterId) =>
      sender ! setTagFilterForSource(source, tagFilterId)
    case RemoveFilterForSource(sourceFilterId) =>
      sender ! removeFilterForSource(sourceFilterId)
  }

  def insertTagFilter(tagFilterSpec: TagFilterSpec): TagFilterAdded = {
    await(filteringDAO.createOrUpdateTagFilter(tagFilterSpec).map(TagFilterAdded(_)))
  }

  def getTagFilters(startIndex: Long, count: Long): TagFilterSpecs = {
    val tagFilters = filteringDAO.listTagFilters(startIndex, count)
    await(tagFilters.map(_.map(TagFilterSpec(_))).map(TagFilterSpecs(_)))
  }

  def removeTagFilter(tagFilterId: Long): TagFilterRemoved =
    await(filteringDAO.removeTagFilter(tagFilterId).map(_ => TagFilterRemoved(tagFilterId)))

  def getTagFilter(tagFilterId: Long): Option[TagFilterSpec] =
    await(filteringDAO.getTagFilter(tagFilterId))

  def getTagFilterForSource(source: Source): Option[TagFilterSpec] =
    await(
      filteringDAO.getSourceFilter(source.sourceType, source.sourceId).flatMap {
        _.map {
          sourceFilter => filteringDAO.getTagFilter(sourceFilter.tagFilterId)
        }.getOrElse(Future(None))
      }
    )

  def setTagFilterForSource(source: Source, tagFilterId: Long): SourceTagFilter =
    await(
      filteringDAO.createOrUpdateSourceFilter(SourceTagFilter(-1, source.sourceType, source.sourceId, tagFilterId))
    )

  def removeFilterForSource(sourceFilterId: Long): FilterForSourceRemoved =
    await(filteringDAO.removeSourceFilter(sourceFilterId).map(_ => FilterForSourceRemoved(sourceFilterId)))
}

object FilteringServiceActor {
  def props(filteringDAO: FilteringDAO)(implicit timeout: Timeout): Props = Props(new FilteringServiceActor(filteringDAO))
}

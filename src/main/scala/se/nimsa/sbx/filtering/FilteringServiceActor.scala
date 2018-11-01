package se.nimsa.sbx.filtering

import akka.actor.{Actor, ActorSystem, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.PipeToSupport
import se.nimsa.sbx.app.GeneralProtocol.SourceDeleted
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.SequentialPipeToSupport

import scala.concurrent.ExecutionContext

class FilteringServiceActor(filteringDAO: FilteringDAO) extends Actor with Stash with PipeToSupport with SequentialPipeToSupport {
  val log = Logging(context.system, this)

  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContext = context.dispatcher

  val emptyTagPathsSeq: Seq[TagFilterTagPath] = Seq()

  override def preStart(): Unit =
    context.system.eventStream.subscribe(context.self, classOf[SourceDeleted])

  def receive: Receive = LoggingReceive {
    case AddTagFilter(tagFilterSpec) =>
      filteringDAO.createOrUpdateTagFilter(toDistinct(tagFilterSpec))
        .map(spec => TagFilterAdded(toSorted(spec)))
        .pipeSequentiallyTo(sender)

    case GetTagFilters(startIndex, count) =>
      filteringDAO.listTagFilters(startIndex, count)
        .map(_.map(TagFilterSpec(_)))
        .map(TagFilterSpecs)
        .pipeTo(sender)

    case RemoveTagFilter(tagFilterId) =>
      filteringDAO.removeTagFilter(tagFilterId).map(_ => TagFilterRemoved(tagFilterId))
        .pipeSequentiallyTo(sender)

    case GetTagFilter(tagFilterId) =>
      filteringDAO.getTagFilter(tagFilterId)
        .map(_.map(toSorted))
        .pipeTo(sender)

    case GetFilterForSource(source) =>
      filteringDAO.getTagFilterForSource(source)
        .pipeTo(sender)

    case AddSourceFilterAssociation(sourceTagFilter) =>
      filteringDAO.createOrUpdateSourceFilter(sourceTagFilter)
        .pipeSequentiallyTo(sender)

    case RemoveSourceTagFilter(sourceFilterId) =>
      filteringDAO.removeSourceFilter(sourceFilterId)
        .map(_ => SourceTagFilterRemoved())
        .pipeSequentiallyTo(sender)

    case RemoveFilterForSource(sourceRef) =>
      filteringDAO.removeSourceFilter(sourceRef)
        .map(_ => SourceTagFilterRemoved())
        .pipeSequentiallyTo(sender)

    case SourceDeleted(sourceRef) =>
      filteringDAO.removeSourceFilter(sourceRef)
        .map(_ => SourceTagFilterRemoved())
        .pipeSequentiallyTo(sender)

    case GetSourceTagFilters(startIndex, count) =>
      filteringDAO.listSourceTagFilters(startIndex, count)
        .map(SourceTagFilters)
        .pipeTo(sender)
  }

  private def toDistinct(spec: TagFilterSpec): TagFilterSpec = spec.copy(tagPaths = spec.tagPaths.distinct)
  private def toSorted(spec: TagFilterSpec): TagFilterSpec = spec.copy(tagPaths = spec.tagPaths.sortWith(_ < _))
}

object FilteringServiceActor {
  def props(filteringDAO: FilteringDAO): Props = Props(new FilteringServiceActor(filteringDAO))
}

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
      filteringDAO.createOrUpdateTagFilter(tagFilterSpec).map(TagFilterAdded).pipeSequentiallyTo(sender)

    case GetTagFilters(startIndex, count) =>
      val filtersFuture = filteringDAO.listTagFilters(startIndex, count)
      pipe(filtersFuture.map(_.map(TagFilterSpec(_))).map(TagFilterSpecs)).to(sender)

    case RemoveTagFilter(tagFilterId) =>
      filteringDAO.removeTagFilter(tagFilterId).map(_ => TagFilterRemoved(tagFilterId)).pipeSequentiallyTo(sender)

    case GetTagFilter(tagFilterId) =>
      pipe(filteringDAO.getTagFilter(tagFilterId)).to(sender)

    case GetFilterForSource(source) =>
      pipe(filteringDAO.getTagFilterForSource(source)).to(sender)

    case AddSourceFilterAssociation(sourceTagFilter) =>
      filteringDAO.createOrUpdateSourceFilter(sourceTagFilter).pipeSequentiallyTo(sender)

    case RemoveSourceTagFilter(sourceFilterId) =>
      filteringDAO.removeSourceFilter(sourceFilterId).map(_ => SourceTagFilterRemoved()).pipeSequentiallyTo(sender)

    case RemoveFilterForSource(sourceRef) =>
      filteringDAO.removeSourceFilter(sourceRef).map(_ => SourceTagFilterRemoved()).pipeSequentiallyTo(sender)

    case SourceDeleted(sourceRef) =>
      filteringDAO.removeSourceFilter(sourceRef).map(_ => SourceTagFilterRemoved()).pipeSequentiallyTo(sender)

    case GetSourceTagFilters(startIndex, count) =>
      pipe(filteringDAO.listSourceTagFilters(startIndex, count).map(SourceTagFilters)).to(sender)
  }
}

object FilteringServiceActor {
  def props(filteringDAO: FilteringDAO): Props = Props(new FilteringServiceActor(filteringDAO))
}

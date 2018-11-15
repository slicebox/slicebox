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
    case msg: FilteringRequest =>
      msg match {

        case AddTagFilter(tagFilter) =>
          filteringDAO.insertTagFilter(tagFilter)
            .map(TagFilterAdded)
            .pipeSequentiallyTo(sender)

        case AddTagFilterTagPath(tagFilterTagPath) =>
          filteringDAO.insertTagFilterTagPath(tagFilterTagPath)
            .map(TagFilterTagPathAdded)
            .pipeSequentiallyTo(sender)

        case AddSourceTagFilter(sourceTagFilter) =>
          filteringDAO.insertSourceTagFilter(sourceTagFilter)
            .map(SourceTagFilterAdded)
            .pipeSequentiallyTo(sender)

        case RemoveTagFilter(tagFilterId) =>
          filteringDAO.removeTagFilterById(tagFilterId).map(_ => TagFilterRemoved(tagFilterId))
            .pipeSequentiallyTo(sender)

        case RemoveTagFilterTagPath(tagFilterTagPathId) =>
          filteringDAO.removeTagFilterTagPathById(tagFilterTagPathId)
            .map(_ => TagFilterTagPathRemoved(tagFilterTagPathId))
            .pipeSequentiallyTo(sender)

        case RemoveSourceTagFilter(sourceTagFilterId) =>
          filteringDAO.removeSourceTagFilterById(sourceTagFilterId)
            .map(_ => SourceTagFilterRemoved(sourceTagFilterId))
            .pipeSequentiallyTo(sender)

        case GetTagFilters(startIndex, count) =>
          filteringDAO.listTagFilters(startIndex, count)
            .map(TagFilters)
            .pipeTo(sender)

        case GetTagFilterTagPaths(tagFilterId, startIndex, count) =>
          filteringDAO.listTagFilterTagPathsByTagFilterId(tagFilterId, startIndex, count)
            .map(TagFilterTagPaths)
            .pipeTo(sender)

        case GetSourceTagFilters(startIndex, count) =>
          filteringDAO.listSourceTagFilters(startIndex, count)
            .map(SourceTagFilters)
            .pipeTo(sender)

        case GetFilterSpecsForSource(source) =>
          filteringDAO.tagFiltersToTagPaths(source)
            .map { tagFilterTagPaths =>
              tagFilterTagPaths.map {
                case (tagFilter, tagPaths) =>
                  TagFilterSpec(tagFilter.name, tagFilter.tagFilterType, tagPaths.map(_.tagPath))
              }
            }
            .pipeTo(sender)
      }

    case SourceDeleted(sourceRef) =>
      filteringDAO.removeSourceTagFiltersBySourceRef(sourceRef)
        .map(_ => SourceTagFiltersRemoved)
        .pipeSequentiallyTo(sender)
  }

}

object FilteringServiceActor {
  def props(filteringDAO: FilteringDAO): Props = Props(new FilteringServiceActor(filteringDAO))
}

package se.nimsa.sbx.filtering

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.FutureUtil.await

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
}

object FilteringServiceActor {
  def props(filteringDAO: FilteringDAO)(implicit timeout: Timeout): Props = Props(new FilteringServiceActor(filteringDAO))
}

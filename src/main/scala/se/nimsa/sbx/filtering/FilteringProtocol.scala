package se.nimsa.sbx.filtering

import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}

object FilteringProtocol {

  sealed trait FilteringRequest
  case class AddTagFilter(filter: TagFilterSpec) extends FilteringRequest
  case class GetTagFilters(startIndex: Long, count: Long) extends FilteringRequest
  case class RemoveTagFilter(tagFilterId: Long) extends FilteringRequest
  case class GetTagFilter(tagFilterId: Long) extends FilteringRequest
  case class GetFilterForSource(source: Source) extends FilteringRequest
  case class SetFilterForSource(source: Source, tagFilterId: Long) extends FilteringRequest
  case class RemoveFilterForSource(sourceFilterId: Long) extends FilteringRequest

  sealed trait TagFilterType
  object TagFilterType {
    case object WHITELIST extends TagFilterType
    case object BLACKLIST extends TagFilterType

    def withName(string: String): TagFilterType = string match {
      case "WHITELIST" => WHITELIST
      case "BLACKLIST" => BLACKLIST
    }
  }

  case class TagFilterAdded(filterSpecification: TagFilterSpec)

  case class TagFilterSpec(id: Long, name: String, tagFilterType: TagFilterType, tags: Seq[TagPathTag])

  case object TagFilterSpec {
    def apply(tf: TagFilter, tagPaths: Seq[TagFilterTagPath]):TagFilterSpec =
      TagFilterSpec(tf.id, tf.name, tf.tagFilterType, tagPaths.map(_.tagPathTag))
    def apply(tf: TagFilter): TagFilterSpec =
      TagFilterSpec(tf.id, tf.name, tf.tagFilterType, Seq())
  }

  case class TagFilterSpecs(tagFilterSpecs: Seq[TagFilterSpec])

  case class TagFilterRemoved(tagFilterId: Long)

  case class FilterForSourceRemoved(sourceFilterId: Long)

  //DB row representations
  case class TagFilter(id: Long, name: String, tagFilterType: TagFilterType)

  case class TagFilterTagPath(id: Long, tagFilterId: Long, tagPathTag: TagPathTag)

  case class SourceTagFilter(id: Long, sourceType: SourceType, sourceId: Long, tagFilterId: Long)
}

/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.filtering

import se.nimsa.dicom.data.TagTree
import se.nimsa.sbx.app.GeneralProtocol.{SourceRef, SourceType}

object FilteringProtocol {

  // domain

  sealed trait TagFilterType
  object TagFilterType {
    case object WHITELIST extends TagFilterType
    case object BLACKLIST extends TagFilterType

    def withName(string: String): TagFilterType = string match {
      case "WHITELIST" => WHITELIST
      case "BLACKLIST" => BLACKLIST
    }
  }

  case class TagFilterSpec(name: String, tagFilterType: TagFilterType, tagPaths: Seq[TagTree])

  // DB row representations

  case class TagFilter(id: Long, name: String, tagFilterType: TagFilterType)
  case class TagFilterTagPath(id: Long, tagFilterId: Long, tagPath: TagTree)
  case class SourceTagFilter(id: Long, sourceType: SourceType, sourceName: String, sourceId: Long, tagFilterId: Long, tagFilterName: String)

  // requests

  sealed trait FilteringRequest
  case class AddTagFilter(filter: TagFilter) extends FilteringRequest
  case class AddTagFilterTagPath(tagFilterTagPath: TagFilterTagPath) extends FilteringRequest
  case class AddSourceTagFilter(sourceTagFilter: SourceTagFilter) extends FilteringRequest

  case class RemoveTagFilter(tagFilterId: Long) extends FilteringRequest
  case class RemoveTagFilterTagPath(tagFilterTagPathId: Long) extends FilteringRequest
  case class RemoveSourceTagFilter(sourceTagFilterId: Long) extends FilteringRequest

  case class GetTagFilters(startIndex: Long, count: Long) extends FilteringRequest
  case class GetTagFilterTagPaths(tagFilterId: Long, startIndex: Long, count: Long) extends FilteringRequest
  case class GetSourceTagFilters(startIndex: Long, count: Long) extends FilteringRequest

  case class GetFilterSpecsForSource(source: SourceRef) extends FilteringRequest

  // responses

  case class TagFilterAdded(tagFilter: TagFilter)
  case class TagFilterTagPathAdded(tagFilterTagPath: TagFilterTagPath)
  case class SourceTagFilterAdded(sourceTagFilter: SourceTagFilter)

  case class TagFilterRemoved(tagFilterId: Long)
  case class TagFilterTagPathRemoved(tagFilterTagPathId: Long)
  case class SourceTagFilterRemoved(sourceTagFilterId: Long)
  case object SourceTagFiltersRemoved

  case class TagFilters(filters: Seq[TagFilter])
  case class TagFilterTagPaths(filters: Seq[TagFilterTagPath])
  case class SourceTagFilters(filters: Seq[SourceTagFilter])

  case class TagFilterSpecs(tagFilterSpecs: Seq[TagFilterSpec])
}

/*
 * Copyright 2015 Lars Edenbrandt
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

package se.nimsa.sbx.storage

import java.nio.file.Path
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesType

object StorageProtocol {

  import se.nimsa.sbx.model.Entity

  // domain objects

  case class SeriesSeriesType(seriesId: Long, seriesTypeId: Long)

  case class SeriesSource(id: Long, source: Source) extends Entity

  case class SeriesTag(id: Long, name: String) extends Entity

  case class SeriesSeriesTag(seriesId: Long, seriesTagId: Long)

  case class FileName(value: String)

  case class ImageInformation(
    numberOfFrames: Int,
    frameIndex: Int,
    minimumPixelValue: Int,
    maximumPixelValue: Int)

  sealed trait QueryOperator {
    override def toString(): String = this match {
      case QueryOperator.EQUALS => "="
      case QueryOperator.LIKE   => "like"
    }
  }

  object QueryOperator {
    case object EQUALS extends QueryOperator
    case object LIKE extends QueryOperator

    def withName(string: String) = string match {
      case "="    => EQUALS
      case "like" => LIKE
    }
  }

  case class QueryProperty(
    propertyName: String,
    operator: QueryOperator,
    propertyValue: String)

  case class QueryOrder(
      orderBy: String,
      orderAscending: Boolean)
      
  case class QueryFilters(
    sourceRefs: Seq[SourceRef],
    seriesTypeIds: Seq[Long],
    seriesTagIds: Seq[Long])

  case class Query(
    startIndex: Long,
    count: Long,
    order: Option[QueryOrder],
    queryProperties: Seq[QueryProperty],
    filters: Option[QueryFilters])

  // messages

  sealed trait MetaDataQuery

  case class GetPatients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetStudies(startIndex: Long, count: Long, patientId: Long, sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetSeries(startIndex: Long, count: Long, studyId: Long, sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetFlatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetImages(startIndex: Long, count: Long, seriesId: Long) extends MetaDataQuery

  case class GetPatient(patientId: Long) extends MetaDataQuery

  case class GetStudy(studyId: Long) extends MetaDataQuery

  case class GetSingleSeries(seriesId: Long) extends MetaDataQuery

  case object GetAllSeries extends MetaDataQuery

  case class GetImage(imageId: Long) extends MetaDataQuery

  case class GetSingleFlatSeries(seriesId: Long) extends MetaDataQuery

  case class QueryPatients(query: Query) extends MetaDataQuery

  case class QueryStudies(query: Query) extends MetaDataQuery

  case class QuerySeries(query: Query) extends MetaDataQuery

  case class QueryImages(query: Query) extends MetaDataQuery

  case class QueryFlatSeries(query: Query) extends MetaDataQuery

  case class DeleteImage(imageId: Long)

  sealed trait ImageRequest

  case class GetImagePath(imageId: Long) extends ImageRequest

  case class GetDataset(imageId: Long, withPixelData: Boolean) extends ImageRequest

  case class GetImageAttributes(imageId: Long) extends ImageRequest

  case class GetImageInformation(imageId: Long) extends ImageRequest

  case class GetImageFrame(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int) extends ImageRequest

  sealed trait PropertiesRequest

  case class AddSeriesTypeToSeries(seriesType: SeriesType, series: Series) extends PropertiesRequest

  case class AddSeriesTagToSeries(seriesTag: SeriesTag, seriesId: Long) extends PropertiesRequest

  case class RemoveSeriesTypesFromSeries(series: Series) extends PropertiesRequest

  case class RemoveSeriesTagFromSeries(seriesTagId: Long, seriesId: Long) extends PropertiesRequest

  case object GetSeriesTags extends PropertiesRequest

  case class GetSourceForSeries(seriesId: Long) extends PropertiesRequest

  case class GetSeriesTagsForSeries(seriesId: Long) extends PropertiesRequest

  case class GetSeriesTypesForSeries(seriesId: Long) extends PropertiesRequest

  case class AddDataset(dataset: Attributes, source: Source, allowSecondaryCapture: Boolean)

  // ***to API***

  case class Patients(patients: Seq[Patient])

  case class Studies(studies: Seq[Study])

  case class SeriesCollection(series: Seq[Series])

  case class FlatSeriesCollection(series: Seq[FlatSeries])

  case class Images(images: Seq[Image])

  case class ImagePath(imagePath: Path)

  case class ImageDeleted(imageId: Long)

  case class SeriesTypeAddedToSeries(seriesSeriesType: SeriesSeriesType)

  case class SeriesTagAddedToSeries(seriesTag: SeriesTag)

  case class SeriesTypesRemovedFromSeries(series: Series)

  case class SeriesTagRemovedFromSeries(seriesId: Long)

  case class SeriesTags(seriesTags: Seq[SeriesTag])

  // ***to storage***

  case class DatasetReceived(dataset: Attributes, source: Source)

  case class FileReceived(path: Path, source: Source)

  // Series

  case class SeriesDataset(id: Long, url: String)

}

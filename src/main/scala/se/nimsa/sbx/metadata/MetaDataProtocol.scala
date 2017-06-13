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

package se.nimsa.sbx.metadata

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.app.GeneralProtocol.SourceRef
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesIdSeriesType

object MetaDataProtocol {
  
  import se.nimsa.sbx.model.Entity
  
  // domain objects

  case class SeriesSource(id: Long, source: Source) extends Entity

  case class SeriesTag(id: Long, name: String) extends Entity

  case class SeriesSeriesTag(seriesId: Long, seriesTagId: Long)

  sealed trait QueryOperator {
    override def toString: String = this match {
      case QueryOperator.EQUALS => "="
      case QueryOperator.LIKE   => "like"
    }
  }

  object QueryOperator {
    case object EQUALS extends QueryOperator
    case object LIKE extends QueryOperator

    def withName(string: String): QueryOperator = string match {
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

  case class AddMetaData(attributes: Attributes, source: Source)
  
  case class DeleteMetaData(image: Image)
    
  sealed trait MetaDataQuery

  case class GetPatients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetStudies(startIndex: Long, count: Long, patientId: Long, sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetSeries(startIndex: Long, count: Long, studyId: Long, sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetFlatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery

  case class GetImages(startIndex: Long, count: Long, seriesId: Long) extends MetaDataQuery

  case class GetImagesForStudy(studyId: Long, sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery
  
  case class GetImagesForPatient(patientId: Long, sourceRefs: Array[SourceRef], seriesTypeIds: Array[Long], seriesTagIds: Array[Long]) extends MetaDataQuery
  
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

  sealed trait PropertiesRequest

  case class AddSeriesTagToSeries(seriesTag: SeriesTag, seriesId: Long) extends PropertiesRequest

  case class RemoveSeriesTagFromSeries(seriesTagId: Long, seriesId: Long) extends PropertiesRequest

  case object GetSeriesTags extends PropertiesRequest

  case class GetSourceForSeries(seriesId: Long) extends PropertiesRequest

  case class GetSeriesTagsForSeries(seriesId: Long) extends PropertiesRequest


  // ***to API***

  case class MetaDataAdded(patient: Patient, study: Study, series: Series, image: Image, patientAdded: Boolean, studyAdded: Boolean, seriesAdded: Boolean, imageAdded: Boolean, source: Source)

  case class MetaDataDeleted(deletedPatient: Option[Patient], deletedStudy: Option[Study], deletedSeries: Option[Series], deletedImage: Option[Image])
  
  case class Patients(patients: Seq[Patient])

  case class Studies(studies: Seq[Study])

  case class SeriesCollection(series: Seq[Series])

  case class FlatSeriesCollection(series: Seq[FlatSeries])

  case class Images(images: Seq[Image])

  case class SeriesTagAddedToSeries(seriesTag: SeriesTag)

  case class SeriesTagRemovedFromSeries(seriesId: Long)

  case class SeriesTags(seriesTags: Seq[SeriesTag])
}

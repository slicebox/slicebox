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

import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.util.DbUtil._
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import slick.jdbc.GetResult

import scala.concurrent.{ExecutionContext, Future}

class PropertiesDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import MetaDataDAO._
  import dbConf.profile.api._

  val db = dbConf.db

  val metaDataDao = new MetaDataDAO(dbConf)
  val seriesTypeDao = new SeriesTypeDAO(dbConf)

  import metaDataDao._

  // *** Sources ***

  private val toSeriesSource = (id: Long, sourceType: String, sourceName: String, sourceId: Long) => SeriesSource(id, Source(SourceType.withName(sourceType), sourceName, sourceId))

  private val fromSeriesSource = (seriesSource: SeriesSource) => Option((seriesSource.id, seriesSource.source.sourceType.toString(), seriesSource.source.sourceName, seriesSource.source.sourceId))

  private class SeriesSources(tag: Tag) extends Table[SeriesSource](tag, SeriesSources.name) {
    def id = column[Long]("id", O.PrimaryKey)
    def sourceType = column[String]("sourcetype")
    def sourceName = column[String]("sourcename")
    def sourceId = column[Long]("sourceid")
    def seriesSourceToImageFKey = foreignKey("seriesSourceToImageFKey", id, metaDataDao.seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def * = (id, sourceType, sourceName, sourceId) <> (toSeriesSource.tupled, fromSeriesSource)
  }

  object SeriesSources {
    val name = "SeriesSources"
  }

  private val seriesSourceQuery = TableQuery[SeriesSources]

  // *** Tags ***

  private val toSeriesTag = (id: Long, name: String) => SeriesTag(id, name)

  private val fromSeriesTag = (seriesTag: SeriesTag) => Option((seriesTag.id, seriesTag.name))

  class SeriesTagTable(tag: Tag) extends Table[SeriesTag](tag, SeriesTagTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name", O.Length(255))
    def idxUniqueName = index("idx_unique_series_tag_name", name, unique = true)
    def * = (id, name) <> (toSeriesTag.tupled, fromSeriesTag)
  }

  object SeriesTagTable {
    val name = "SeriesTags"
  }

  private val seriesTagQuery = TableQuery[SeriesTagTable]

  private val toSeriesSeriesTagRule = (seriesId: Long, seriesTagId: Long) => SeriesSeriesTag(seriesId, seriesTagId)

  private val fromSeriesSeriesTagRule = (seriesSeriesTag: SeriesSeriesTag) => Option((seriesSeriesTag.seriesId, seriesSeriesTag.seriesTagId))

  private class SeriesSeriesTagTable(tag: Tag) extends Table[SeriesSeriesTag](tag, SeriesSeriesTagTable.name) {
    def seriesId = column[Long]("seriesid")
    def seriesTagId = column[Long]("seriestagid")
    def pk = primaryKey("pk_tag", (seriesId, seriesTagId))
    def fkSeries = foreignKey("fk_series_seriesseriestag", seriesId, metaDataDao.seriesQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def fkSeriesType = foreignKey("fk_seriestag_seriesseriestag", seriesTagId, seriesTagQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (seriesId, seriesTagId) <> (toSeriesSeriesTagRule.tupled, fromSeriesSeriesTagRule)
  }

  object SeriesSeriesTagTable {
    val name = "SeriesSeriesTags"
  }

  private val seriesSeriesTagQuery = TableQuery[SeriesSeriesTagTable]

  // Setup

  def create() = createTables(dbConf, (SeriesSources.name, seriesSourceQuery), (SeriesTagTable.name, seriesTagQuery), (SeriesSeriesTagTable.name, seriesSeriesTagQuery))

  def drop() = db.run {
    (seriesSourceQuery.schema ++ seriesTagQuery.schema ++ seriesSeriesTagQuery.schema).drop
  }

  def clear() = db.run {
    DBIO.seq(seriesSourceQuery.delete, seriesTagQuery.delete, seriesSeriesTagQuery.delete)
  }

  // Functions

  def insertSeriesSourceAction(seriesSource: SeriesSource) = (seriesSourceQuery += seriesSource).map(_ => seriesSource)

  def insertSeriesSource(seriesSource: SeriesSource): Future[SeriesSource] = db.run(insertSeriesSourceAction(seriesSource))

  def updateSeriesSourceAction(seriesSource: SeriesSource) =
    seriesSourceQuery
      .filter(_.id === seriesSource.id)
      .update(seriesSource)

  def updateSeriesSource(seriesSource: SeriesSource): Future[Int] = db.run(updateSeriesSourceAction(seriesSource))

  def seriesSourceByIdAction(seriesId: Long) =
    seriesSourceQuery.filter(_.id === seriesId).result.headOption

  def seriesSourceById(seriesId: Long): Future[Option[SeriesSource]] = db.run(seriesSourceByIdAction(seriesId))

  def seriesSources: Future[Seq[SeriesSource]] = db.run {
    seriesSourceQuery.result
  }

  def seriesTags: Future[Seq[SeriesTag]] = db.run {
    seriesTagQuery.result
  }

  def insertSeriesTagAction(seriesTag: SeriesTag) =
    (seriesTagQuery returning seriesTagQuery.map(_.id) += seriesTag)
      .map(generatedId => seriesTag.copy(id = generatedId))

  def insertSeriesTag(seriesTag: SeriesTag): Future[SeriesTag] = db.run(insertSeriesTagAction(seriesTag))

  def seriesTagForNameAction(name: String) = seriesTagQuery.filter(_.name === name).result.headOption

  def seriesTagForName(name: String): Future[Option[SeriesTag]] = db.run(seriesTagForNameAction(name))

  def updateSeriesTag(seriesTag: SeriesTag): Future[Unit] = db.run {
    seriesTagQuery.filter(_.id === seriesTag.id).update(seriesTag)
  }.map(_ => Unit)

  def listSeriesSources: Future[Seq[SeriesSource]] = db.run {
    seriesSourceQuery.result
  }

  def listSeriesTags: Future[Seq[SeriesTag]] = db.run {
    seriesTagQuery.result
  }

  def removeSeriesTagAction(seriesTagId: Long) = seriesTagQuery.filter(_.id === seriesTagId).delete.map(_ => {})

  def removeSeriesTag(seriesTagId: Long): Future[Unit] = db.run(removeSeriesTagAction(seriesTagId))

  def insertSeriesSeriesTagAction(seriesSeriesTag: SeriesSeriesTag) =
    (seriesSeriesTagQuery += seriesSeriesTag).map(_ => seriesSeriesTag)

  def insertSeriesSeriesTag(seriesSeriesTag: SeriesSeriesTag): Future[SeriesSeriesTag] =
    db.run(insertSeriesSeriesTagAction(seriesSeriesTag))

  def listSeriesSeriesTagsForSeriesId(seriesId: Long): Future[Seq[SeriesSeriesTag]] = db.run {
    seriesSeriesTagQuery.filter(_.seriesId === seriesId).result
  }

  private def listSeriesSeriesTagsForSeriesTagIdAction(seriesTagId: Long) =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).result

  def listSeriesSeriesTagsForSeriesTagId(seriesTagId: Long): Future[Seq[SeriesSeriesTag]] =
    db.run(listSeriesSeriesTagsForSeriesTagIdAction(seriesTagId))

  def seriesSeriesTagForSeriesTagIdAndSeriesIdAction(seriesTagId: Long, seriesId: Long) =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).filter(_.seriesId === seriesId).result.headOption

  def seriesSeriesTagForSeriesTagIdAndSeriesId(seriesTagId: Long, seriesId: Long): Future[Option[SeriesSeriesTag]] =
    db.run(seriesSeriesTagForSeriesTagIdAndSeriesIdAction(seriesTagId, seriesId))

  def removeSeriesSeriesTagAction(seriesTagId: Long, seriesId: Long) =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).filter(_.seriesId === seriesId).delete.map(_ => {})

  def removeSeriesSeriesTag(seriesTagId: Long, seriesId: Long): Future[Unit] = db.run(removeSeriesSeriesTagAction(seriesTagId, seriesId))

  def seriesTagsForSeriesAction(seriesId: Long) = {
    val innerJoin = for {
      sst <- seriesSeriesTagQuery.filter(_.seriesId === seriesId)
      stq <- seriesTagQuery if sst.seriesTagId === stq.id
    } yield stq
    innerJoin.result
  }

  def seriesTagsForSeries(seriesId: Long): Future[Seq[SeriesTag]] = db.run(seriesTagsForSeriesAction(seriesId))

  def addAndInsertSeriesTagForSeriesIdAction(seriesTag: SeriesTag, seriesId: Long) =
    seriesTagForNameAction(seriesTag.name)
      .flatMap(_
        .map(DBIO.successful)
        .getOrElse(insertSeriesTagAction(seriesTag)))
      .flatMap { dbSeriesTag =>
        seriesSeriesTagForSeriesTagIdAndSeriesIdAction(dbSeriesTag.id, seriesId)
          .flatMap(_
            .map(_ => DBIO.successful(dbSeriesTag))
            .getOrElse(insertSeriesSeriesTagAction(SeriesSeriesTag(seriesId, dbSeriesTag.id)).map(_ => dbSeriesTag)))
      }

  def addAndInsertSeriesTagForSeriesId(seriesTag: SeriesTag, seriesId: Long): Future[SeriesTag] =
    db.run(addAndInsertSeriesTagForSeriesIdAction(seriesTag, seriesId).transactionally)

  def cleanupSeriesTagAction(seriesTagId: Long) =
    listSeriesSeriesTagsForSeriesTagIdAction(seriesTagId).flatMap { otherSeriesWithSameTag =>
      if (otherSeriesWithSameTag.isEmpty)
        removeSeriesTagAction(seriesTagId)
      else
        DBIO.successful({})
    }

  def cleanupSeriesTag(seriesTagId: Long) = db.run(cleanupSeriesTagAction(seriesTagId))

  def removeAndCleanupSeriesTagForSeriesId(seriesTagId: Long, seriesId: Long): Future[Unit] = db.run {
    removeSeriesSeriesTagAction(seriesTagId, seriesId)
      .flatMap(_ => cleanupSeriesTagAction(seriesTagId))
  }

  def deleteImageFullyAction(image: Image) =
    metaDataDao.deleteImageAction(image.id).flatMap { imagesDeleted =>
      metaDataDao.seriesByIdAction(image.seriesId).flatMap { maybeParentSeries =>
        imagesQuery.filter(_.seriesId === image.seriesId).take(1).result.flatMap { otherImages =>
          maybeParentSeries
            .filter(_ => otherImages.isEmpty)
            .map(series => deleteSeriesFullyAction(series))
            .getOrElse(DBIO.successful((None, None, None)))
        }.map {
          case (maybePatient, maybeStudy, maybeSeries) =>
            val maybeImage = if (imagesDeleted == 0) None else Some(image)
            (maybePatient, maybeStudy, maybeSeries, maybeImage)
        }
      }
    }

  def deleteSeriesFullyAction(series: Series) =
    seriesTagsForSeriesAction(series.id).flatMap { seriesSeriesTags =>
      metaDataDao.deleteSeriesFullyAction(series).flatMap { result =>
        DBIO.sequence(seriesSeriesTags.map(seriesTag => cleanupSeriesTagAction(seriesTag.id)))
          .map(_ => result)
      }
    }

  def deleteFully(series: Series): Future[(Option[Patient], Option[Study], Option[Series])] =
    db.run(deleteSeriesFullyAction(series).transactionally)

  def deleteFully(image: Image): Future[(Option[Patient], Option[Study], Option[Series], Option[Image])] = db.run {
    deleteImageFullyAction(image).transactionally
  }

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): Future[Seq[FlatSeries]] =
    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds))
      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
        db.run {

          implicit val getResult = metaDataDao.flatSeriesGetResult

          val query =
            metaDataDao.flatSeriesBasePart +
              propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
              " where" +
              metaDataDao.flatSeriesFilterPart(filter) +
              andPart(filter, sourceRefs) +
              sourcesPart(sourceRefs) +
              andPart(filter, sourceRefs, seriesTypeIds) +
              seriesTypesPart(seriesTypeIds) +
              andPart(filter, sourceRefs, seriesTypeIds, seriesTagIds) +
              seriesTagsPart(seriesTagIds) +
              orderByPart(orderBy, orderAscending) +
              pagePart(startIndex, count)

          sql"#$query".as[FlatSeries]
        }
      }
    else
      metaDataDao.flatSeries(startIndex, count, orderBy, orderAscending, filter)

  def propertiesJoinPart(sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]) =
    singlePropertyJoinPart(sourceRefs, """ inner join "SeriesSources" on "Series"."id" = "SeriesSources"."id"""") +
      singlePropertyJoinPart(seriesTypeIds, """ inner join "SeriesSeriesTypes" on "Series"."id" = "SeriesSeriesTypes"."seriesid"""") +
      singlePropertyJoinPart(seriesTagIds, """ inner join "SeriesSeriesTags" on "Series"."id" = "SeriesSeriesTags"."seriesid"""")

  def singlePropertyJoinPart(property: Seq[_ <: Any], part: String) = if (property.isEmpty) "" else part

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): Future[Seq[Patient]] =
    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds))
      checkColumnExists(dbConf, orderBy, PatientsTable.name).flatMap { _ =>
        db.run {

          implicit val getResult = patientsGetResult

          val query =
            patientsBasePart +
              propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
              " where" +
              patientsFilterPart(filter) +
              andPart(filter, sourceRefs) +
              sourcesPart(sourceRefs) +
              andPart(filter, sourceRefs, seriesTypeIds) +
              seriesTypesPart(seriesTypeIds) +
              andPart(filter, sourceRefs, seriesTypeIds, seriesTagIds) +
              seriesTagsPart(seriesTagIds) +
              orderByPart(orderBy, orderAscending) +
              pagePart(startIndex, count)

          sql"#$query".as[Patient]
        }
      }
    else
      metaDataDao.patients(startIndex, count, orderBy, orderAscending, filter)

  def parseQueryOrder(optionalOrder: Option[QueryOrder]) =
    (optionalOrder.map(_.orderBy), optionalOrder.forall(_.orderAscending))

  def wherePart(arrays: Seq[_ <: Any]*) =
    if (arrays.exists(_.nonEmpty))
      " where "
    else
      ""

  def queryMainPart(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long], queryProperties: Seq[QueryProperty]) =
    propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
      wherePart(queryProperties, sourceRefs, seriesTypeIds, seriesTagIds) +
      queryPart(queryProperties) +
      andPart(queryProperties, sourceRefs) +
      sourcesPart(sourceRefs) +
      andPart(queryProperties, sourceRefs, seriesTypeIds) +
      seriesTypesPart(seriesTypeIds) +
      andPart(queryProperties, sourceRefs, seriesTypeIds, seriesTagIds) +
      seriesTagsPart(seriesTagIds) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

  def queryPatients(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters]): Future[Seq[Patient]] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
        Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
          db.run {

            implicit val getResult = metaDataDao.patientsGetResult

            val query =
              metaDataDao.queryPatientsSelectPart +
                queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

            sql"#$query".as[Patient]
          }
        }
      }
    }.getOrElse {
      metaDataDao.queryPatients(startIndex, count, orderBy, orderAscending, queryProperties)
    }
  }

  def queryStudies(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters]): Future[Seq[Study]] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
        Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
          db.run {

            implicit val getResult = metaDataDao.studiesGetResult

            val query =
              metaDataDao.queryStudiesSelectPart +
                queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

            sql"#$query".as[Study]
          }
        }
      }
    }.getOrElse {
      metaDataDao.queryStudies(startIndex, count, orderBy, orderAscending, queryProperties)
    }
  }

  def querySeries(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters]): Future[Seq[Series]] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
        Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
          db.run {

            implicit val getResult = metaDataDao.seriesGetResult

            val query =
              metaDataDao.querySeriesSelectPart +
                queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

            sql"#$query".as[Series]
          }
        }
      }
    }.getOrElse {
      metaDataDao.querySeries(startIndex, count, orderBy, orderAscending, queryProperties)
    }
  }

  def queryImages(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters]): Future[Seq[Image]] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name, ImagesTable.name).flatMap { _ =>
        Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name, ImagesTable.name))).flatMap { _ =>
          db.run {

            implicit val getResult = metaDataDao.imagesGetResult

            val query =
              metaDataDao.queryImagesSelectPart +
                queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

            sql"#$query".as[Image]
          }
        }
      }
    }.getOrElse {
      metaDataDao.queryImages(startIndex, count, orderBy, orderAscending, queryProperties)
    }
  }

  def queryFlatSeries(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters]): Future[Seq[FlatSeries]] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
        Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
          db.run {

            implicit val getResult = metaDataDao.flatSeriesGetResult

            val query =
              metaDataDao.flatSeriesBasePart +
                queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

            sql"#$query".as[FlatSeries]
          }
        }
      }
    }.getOrElse {
      metaDataDao.queryFlatSeries(startIndex, count, orderBy, orderAscending, queryProperties)
    }
  }

  def isWithAdvancedFiltering(arrays: Seq[_ <: Any]*) = arrays.exists(_.nonEmpty)

  def patientsBasePart =
    s"""select distinct("Patients"."id"),
       "Patients"."patientName","Patients"."patientID","Patients"."patientBirthDate","Patients"."patientSex"
       from "Series"
       inner join "Studies" on "Series"."studyId" = "Studies"."id"
       inner join "Patients" on "Studies"."patientId" = "Patients"."id""""

  def andPart(target: Seq[_ <: Any]) = if (target.nonEmpty) " and" else ""

  def andPart(array: Seq[_ <: Any], target: Seq[_ <: Any]) = if (array.nonEmpty && target.nonEmpty) " and" else ""

  def andPart(array1: Seq[_ <: Any], array2: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((array1.nonEmpty || array2.nonEmpty) && target.nonEmpty) " and" else ""

  def andPart(array1: Seq[_ <: Any], array2: Seq[_ <: Any], array3: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((array1.nonEmpty || array2.nonEmpty || array3.nonEmpty) && target.nonEmpty) " and" else ""

  def andPart(option: Option[Any], target: Seq[_ <: Any]) = if (option.isDefined && target.nonEmpty) " and" else ""

  def andPart(option: Option[Any], array: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((option.isDefined || array.nonEmpty) && target.nonEmpty) " and" else ""

  def andPart(option: Option[Any], array1: Seq[_ <: Any], array2: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((option.isDefined || array1.nonEmpty || array2.nonEmpty) && target.nonEmpty) " and" else ""

  def sourcesPart(sourceRefs: Seq[SourceRef]) =
    if (sourceRefs.isEmpty)
      ""
    else
      " (" + sourceRefs.map(sourceTypeId =>
        s""""SeriesSources"."sourcetype" = '${sourceTypeId.sourceType}' and "SeriesSources"."sourceid" = ${sourceTypeId.sourceId}""")
        .mkString(" or ") + ")"

  def seriesTypesPart(seriesTypeIds: Seq[Long]) =
    if (seriesTypeIds.isEmpty)
      ""
    else
      " (" + seriesTypeIds.map(seriesTypeId =>
        s""""SeriesSeriesTypes"."seriestypeid" = $seriesTypeId""")
        .mkString(" or ") + ")"

  def seriesTagsPart(seriesTagIds: Seq[Long]) =
    if (seriesTagIds.isEmpty)
      ""
    else
      " (" + seriesTagIds.map(seriesTagId =>
        s""""SeriesSeriesTags"."seriestagid" = $seriesTagId""")
        .mkString(" or ") + ")"

  def studiesGetResult = GetResult(r =>
    Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)))

  def studiesForPatient(startIndex: Long, count: Long, patientId: Long, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): Future[Seq[Study]] = {

    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds))
      db.run {

        implicit val getResult = studiesGetResult

        val basePart =
          s"""select distinct("Studies"."id"),
        "Studies"."patientId","Studies"."studyInstanceUID","Studies"."studyDescription","Studies"."studyDate","Studies"."studyID","Studies"."accessionNumber","Studies"."patientAge"
        from "Series" 
        inner join "Studies" on "Series"."studyId" = "Studies"."id""""

        val wherePart =
          s"""
        where
        "Studies"."patientId" = $patientId"""

        val query = basePart +
          propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
          wherePart +
          andPart(sourceRefs) +
          sourcesPart(sourceRefs) +
          andPart(seriesTypeIds) +
          seriesTypesPart(seriesTypeIds) +
          andPart(seriesTagIds) +
          seriesTagsPart(seriesTagIds) +
          pagePart(startIndex, count)

        sql"#$query".as[Study]

      }
    else
      metaDataDao.studiesForPatient(startIndex, count, patientId)
  }

  def seriesGetResult = GetResult(r =>
    Series(r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString), Manufacturer(r.nextString), StationName(r.nextString), FrameOfReferenceUID(r.nextString)))

  def seriesForStudy(startIndex: Long, count: Long, studyId: Long, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): Future[Seq[Series]] = {

    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds))
      db.run {

        implicit val getResult = seriesGetResult

        val basePart =
          s"""select distinct("Series"."id"),
        "Series"."studyId","Series"."seriesInstanceUID","Series"."seriesDescription","Series"."seriesDate","Series"."modality","Series"."protocolName","Series"."bodyPartExamined","Series"."manufacturer","Series"."stationName","Series"."frameOfReferenceUID"
        from "Series""""

        val wherePart =
          s"""
        where
        "Series"."studyId" = $studyId"""

        val query = basePart +
          propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
          wherePart +
          andPart(sourceRefs) +
          sourcesPart(sourceRefs) +
          andPart(seriesTypeIds) +
          seriesTypesPart(seriesTypeIds) +
          andPart(seriesTagIds) +
          seriesTagsPart(seriesTagIds) +
          pagePart(startIndex, count)

        sql"#$query".as[Series]

      }
    else
      metaDataDao.seriesForStudy(startIndex, count, studyId)
  }

  def addMetaData(patient: Patient, study: Study, series: Series, image: Image, source: Source): Future[MetaDataAdded] = {
    val seriesSource = SeriesSource(-1, source)

    val addAction =
      patientByNameAndIDAction(patient).flatMap { patientMaybe =>
        patientMaybe.map { dbp =>
          val updatePatient = patient.copy(id = dbp.id)
          updatePatientAction(updatePatient).map(_ => (updatePatient, false))
        }.getOrElse {
          insertPatientAction(patient).map((_, true))
        }
      }.flatMap {
        case (dbPatient, patientAdded) =>
          studyByUidAndPatientAction(study, dbPatient).flatMap { studyMaybe =>
            studyMaybe.map { dbs =>
              val updateStudy = study.copy(id = dbs.id, patientId = dbs.patientId)
              updateStudyAction(updateStudy).map(_ => (updateStudy, false))
            }.getOrElse {
              insertStudyAction(study.copy(patientId = dbPatient.id)).map((_, true))
            }
          }.flatMap {
            case (dbStudy, studyAdded) =>
              seriesByUidAndStudyAction(series, dbStudy).flatMap { seriesMaybe =>
                seriesMaybe.map { dbs =>
                  val updateSeries = series.copy(id = dbs.id, studyId = dbs.studyId)
                  updateSeriesAction(updateSeries).map(_ => (updateSeries, false))
                }.getOrElse {
                  insertSeriesAction(series.copy(studyId = dbStudy.id)).map((_, true))
                }
              }.flatMap {
                case (dbSeries, seriesAdded) =>
                  imageByUidAndSeriesAction(image, dbSeries).flatMap { imageMaybe =>
                    imageMaybe.map { dbi =>
                      val updateImage = image.copy(id = dbi.id, seriesId = dbi.seriesId)
                      updateImageAction(updateImage).map(_ => (updateImage, false))
                    }.getOrElse {
                      insertImageAction(image.copy(seriesId = dbSeries.id)).map((_, true))
                    }
                  }.flatMap {
                    case (dbImage, imageAdded) =>
                      seriesSourceByIdAction(dbSeries.id).flatMap { seriesSourceMaybe =>
                        seriesSourceMaybe.map { dbss =>
                          val updateSeriesSource = seriesSource.copy(id = dbss.id)
                          updateSeriesSourceAction(updateSeriesSource).map(_ => updateSeriesSource)
                        }.getOrElse {
                          insertSeriesSourceAction(seriesSource.copy(id = dbSeries.id))
                        }
                      }.map { dbSeriesSource =>
                        MetaDataAdded(dbPatient, dbStudy, dbSeries, dbImage,
                          patientAdded, studyAdded, seriesAdded, imageAdded,
                          dbSeriesSource.source)
                      }
                  }
              }
          }
      }

    db.run(addAction.transactionally)
  }

  def addSeriesTagToSeries(seriesTag: SeriesTag, seriesId: Long): Future[Option[SeriesTag]] = db.run {
    metaDataDao.seriesByIdAction(seriesId)
      .map(_.map(_ => addAndInsertSeriesTagForSeriesIdAction(seriesTag, seriesId)))
      .unwrap
  }

}

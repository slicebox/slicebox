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
import slick.basic.{BasicAction, BasicStreamingAction, DatabaseConfig}
import slick.jdbc.{GetResult, JdbcProfile}

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
    def name = column[String]("name", O.Length(180))
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

  def create(): Future[Unit] = createTables(dbConf, (SeriesSources.name, seriesSourceQuery), (SeriesTagTable.name, seriesTagQuery), (SeriesSeriesTagTable.name, seriesSeriesTagQuery))

  def drop(): Future[Unit] = db.run {
    (seriesSourceQuery.schema ++ seriesTagQuery.schema ++ seriesSeriesTagQuery.schema).drop
  }

  def clear(): Future[Unit] = db.run {
    DBIO.seq(seriesSourceQuery.delete, seriesTagQuery.delete, seriesSeriesTagQuery.delete)
  }

  // Functions

  def insertSeriesSourceAction(seriesSource: SeriesSource): DBIOAction[SeriesSource, NoStream, Effect.Write] = (seriesSourceQuery += seriesSource).map(_ => seriesSource)

  def insertSeriesSource(seriesSource: SeriesSource): Future[SeriesSource] = db.run(insertSeriesSourceAction(seriesSource))

  def updateSeriesSourceAction(seriesSource: SeriesSource): BasicAction[Int, NoStream, Effect.Write] =
    seriesSourceQuery
      .filter(_.id === seriesSource.id)
      .update(seriesSource)

  def updateSeriesSource(seriesSource: SeriesSource): Future[Int] = db.run(updateSeriesSourceAction(seriesSource))

  def seriesSourcesByIdAction(seriesId: Long): BasicStreamingAction[Seq[SeriesSource], SeriesSource, Effect.Read] =
    seriesSourceQuery.filter(_.id === seriesId).result

  def seriesSourceById(seriesId: Long): Future[Option[SeriesSource]] = db.run(seriesSourcesByIdAction(seriesId).headOption)

  def seriesSources: Future[Seq[SeriesSource]] = db.run {
    seriesSourceQuery.result
  }

  def seriesTags: Future[Seq[SeriesTag]] = db.run {
    seriesTagQuery.result
  }

  def insertSeriesTagAction(seriesTag: SeriesTag): DBIOAction[SeriesTag, NoStream, Effect.Write] =
    (seriesTagQuery returning seriesTagQuery.map(_.id) += seriesTag)
      .map(generatedId => seriesTag.copy(id = generatedId))

  def insertSeriesTag(seriesTag: SeriesTag): Future[SeriesTag] = db.run(insertSeriesTagAction(seriesTag))

  def seriesTagsForNameAction(name: String): BasicStreamingAction[Seq[SeriesTag], SeriesTag, Effect.Read] =
    seriesTagQuery.filter(_.name === name).result

  def seriesTagForName(name: String): Future[Option[SeriesTag]] = db.run(seriesTagsForNameAction(name).headOption)

  def seriesTagForId(id: Long): Future[Option[SeriesTag]] = db.run(seriesTagQuery.filter(_.id === id).result.headOption)

  def updateSeriesTag(seriesTag: SeriesTag): Future[Option[SeriesTag]] = db.run {
    seriesTagQuery.filter(_.id === seriesTag.id).update(seriesTag)
  }.map { updateCount =>
    if (updateCount > 0) {
      Some(seriesTag)
    } else {
      None
    }
  }

  def listSeriesSources: Future[Seq[SeriesSource]] = db.run {
    seriesSourceQuery.result
  }

  def listSeriesTags(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]): Future[Seq[SeriesTag]] = db.run {
    val filtered = filter.map(f => seriesTagQuery.filter(_.name like s"%$f%")).getOrElse(seriesTagQuery)
    val sorted = orderBy match {
      case Some("id") => if (orderAscending) filtered.sortBy(_.id.asc) else filtered.sortBy(_.id.desc)
      case Some("name") => if (orderAscending) filtered.sortBy(_.name.asc) else filtered.sortBy(_.name.desc)
      case _ => filtered
    }
    sorted.drop(startIndex).take(count).result
  }

  def deleteSeriesTag(tagId: Long): Future[Unit] = db.run(seriesTagQuery.filter(_.id === tagId).delete.map(_ => {}))

  def insertSeriesSeriesTagAction(seriesSeriesTag: SeriesSeriesTag): DBIOAction[SeriesSeriesTag, NoStream, Effect.Write] =
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

  def seriesSeriesTagsForSeriesTagIdAndSeriesIdAction(seriesTagId: Long, seriesId: Long): BasicStreamingAction[Seq[SeriesSeriesTag], SeriesSeriesTag, Effect.Read] =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).filter(_.seriesId === seriesId).result

  def seriesSeriesTagForSeriesTagIdAndSeriesId(seriesTagId: Long, seriesId: Long): Future[Option[SeriesSeriesTag]] =
    db.run(seriesSeriesTagsForSeriesTagIdAndSeriesIdAction(seriesTagId, seriesId).headOption)

  def removeSeriesSeriesTagAction(seriesTagId: Long, seriesId: Long): DBIOAction[Unit, NoStream, Effect.Write] =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).filter(_.seriesId === seriesId).delete.map(_ => {})

  def removeSeriesSeriesTag(seriesTagId: Long, seriesId: Long): Future[Unit] = db.run(removeSeriesSeriesTagAction(seriesTagId, seriesId))

  def seriesTagsForSeriesAction(seriesId: Long): BasicStreamingAction[Seq[SeriesTag], SeriesTag, Effect.Read] = {
    val innerJoin = for {
      sst <- seriesSeriesTagQuery.filter(_.seriesId === seriesId)
      stq <- seriesTagQuery if sst.seriesTagId === stq.id
    } yield stq
    innerJoin.result
  }

  def seriesTagsForSeries(seriesId: Long): Future[Seq[SeriesTag]] = db.run(seriesTagsForSeriesAction(seriesId))

  def addAndInsertSeriesTagForSeriesIdAction(seriesTag: SeriesTag, seriesId: Long): DBIOAction[SeriesTag, NoStream, Effect.Read with Effect.Write with Effect.Read with Effect.Write] =
    seriesTagsForNameAction(seriesTag.name)
      .headOption
      .flatMap(_
        .map(DBIO.successful)
        .getOrElse(insertSeriesTagAction(seriesTag)))
      .flatMap { dbSeriesTag =>
        seriesSeriesTagsForSeriesTagIdAndSeriesIdAction(dbSeriesTag.id, seriesId)
          .headOption
          .flatMap(_
            .map(_ => DBIO.successful(dbSeriesTag))
            .getOrElse(insertSeriesSeriesTagAction(SeriesSeriesTag(seriesId, dbSeriesTag.id)).map(_ => dbSeriesTag)))
      }

  def addAndInsertSeriesTagForSeriesId(seriesTag: SeriesTag, seriesId: Long): Future[SeriesTag] =
    db.run(addAndInsertSeriesTagForSeriesIdAction(seriesTag, seriesId).transactionally)


  def removeSeriesTagForSeriesId(seriesTagId: Long, seriesId: Long): Future[Unit] = db.run {
    removeSeriesSeriesTagAction(seriesTagId, seriesId)
  }

  /**
    * Delete input images. If any series, studies and/or patients become empty as a result of this, delete them too.
    * Also, delete series tags no longer used, if any.
    *
    * @param imageIds IDs of images to delete
    * @return the ids of deleted patients, studies, series and images
    */
  def deleteFully(imageIds: Seq[Long]): Future[(Seq[Long], Seq[Long], Seq[Long], Seq[Long])] = {
    val action = DBIO.sequence {
      imageIds
        .grouped(1000) // micro-batch to keep size of queries under control
        .map(subset => deleteFullyBatch(subset))
    }
    db.run(action.transactionally)
      .map {
        // put the subsets back together again
        _.foldLeft((Seq.empty[Long], Seq.empty[Long], Seq.empty[Long], Seq.empty[Long])) { (total, ids) =>
          (total._1 ++ ids._1, total._2 ++ ids._2, total._3 ++ ids._3, total._4 ++ ids._4)
        }
      }
  }

  private def deleteFullyBatch(imageIds: Seq[Long]) = {

    val images = imagesQuery.filter(_.id inSetBind imageIds) // batch this?

    images.map(_.id).result.flatMap { imageIds =>

      val uniqueSeriesIdsAction = images.map(_.seriesId).distinct.result
      val deleteImagesAction = images.delete
      // find empty series for images, then delete images
      uniqueSeriesIdsAction.flatMap { uniqueSeriesIds =>
        deleteImagesAction.flatMap { _ =>
          DBIO.sequence(uniqueSeriesIds.map(seriesId =>
            imagesQuery.filter(_.seriesId === seriesId).take(1).result.map {
              case ims if ims.nonEmpty => None
              case _ => Some(seriesId)
            }
          )).map(_.flatten)
        }
      }.flatMap { emptySeriesIds =>

        // find empty studies for series, then delete empty series
        val series = seriesQuery.filter(_.id inSetBind emptySeriesIds)
        val uniqueStudyIdsAction = series.map(_.studyId).distinct.result
        val deleteSeriesAction = series.delete
        uniqueStudyIdsAction.flatMap { uniqueStudyIds =>
          deleteSeriesAction.flatMap { _ =>
            DBIO.sequence(uniqueStudyIds.map(studyId =>
              seriesQuery.filter(_.studyId === studyId).take(1).result.map {
                case ims if ims.nonEmpty => None
                case _ => Some(studyId)
              }
            )).map(_.flatten)
          }
        }.flatMap { emptyStudyIds =>

          // find empty patients for studies, then delete empty studies
          val studies = studiesQuery.filter(_.id inSetBind emptyStudyIds)
          val uniquePatientIdsAction = studies.map(_.patientId).distinct.result
          val deleteStudiesAction = studies.delete
          uniquePatientIdsAction.flatMap { uniquePatientIds =>
            deleteStudiesAction.flatMap { _ =>
              DBIO.sequence(uniquePatientIds.map(patientId =>
                studiesQuery.filter(_.patientId === patientId).take(1).result.map {
                  case ims if ims.nonEmpty => None
                  case _ => Some(patientId)
                }
              )).map(_.flatten)
            }
          }.flatMap { emptyPatientIds =>

            // delete empty patients
            patientsQuery.filter(_.id inSetBind emptyPatientIds).delete

              // return deleted ids for each level
              .map(_ => (emptyPatientIds, emptyStudyIds, emptySeriesIds, imageIds))
          }
        }
      }
    }
  }

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): Future[Seq[FlatSeries]] =
    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds))
      checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
        db.run {

          implicit val getResult: GetResult[FlatSeries] = metaDataDao.flatSeriesGetResult

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

  def propertiesJoinPart(sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): String =
    singlePropertyJoinPart(sourceRefs, """ inner join "SeriesSources" on "Series"."id" = "SeriesSources"."id"""") +
      singlePropertyJoinPart(seriesTypeIds, """ inner join "SeriesSeriesTypes" on "Series"."id" = "SeriesSeriesTypes"."seriesid"""") +
      singlePropertyJoinPart(seriesTagIds, """ inner join "SeriesSeriesTags" on "Series"."id" = "SeriesSeriesTags"."seriesid"""")

  def singlePropertyJoinPart(property: Seq[_ <: Any], part: String): String = if (property.isEmpty) "" else part

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]): Future[Seq[Patient]] =
    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds))
      checkColumnExists(dbConf, orderBy, PatientsTable.name).flatMap { _ =>
        db.run {

          implicit val getResult: GetResult[Patient] = patientsGetResult

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

  def parseQueryOrder(optionalOrder: Option[QueryOrder]): (Option[String], Boolean) =
    (optionalOrder.map(_.orderBy), optionalOrder.forall(_.orderAscending))

  def wherePart(arrays: Seq[_ <: Any]*): String =
    if (arrays.exists(_.nonEmpty))
      " where "
    else
      ""

  def queryMainPart(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long], queryProperties: Seq[QueryProperty]): String =
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

            implicit val getResult: GetResult[Patient] = metaDataDao.patientsGetResult

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

            implicit val getResult: GetResult[Study] = metaDataDao.studiesGetResult

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

            implicit val getResult: GetResult[Series] = metaDataDao.seriesGetResult

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

            implicit val getResult: GetResult[Image] = metaDataDao.imagesGetResult

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

            implicit val getResult: GetResult[FlatSeries] = metaDataDao.flatSeriesGetResult

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

  def isWithAdvancedFiltering(arrays: Seq[_ <: Any]*): Boolean = arrays.exists(_.nonEmpty)

  def patientsBasePart =
    s"""select distinct("Patients"."id"),
       "Patients"."patientName","Patients"."patientID","Patients"."patientBirthDate","Patients"."patientSex"
       from "Series"
       inner join "Studies" on "Series"."study_id" = "Studies"."id"
       inner join "Patients" on "Studies"."patient_id" = "Patients"."id""""

  def andPart(target: Seq[_ <: Any]): String = if (target.nonEmpty) " and" else ""

  def andPart(array: Seq[_ <: Any], target: Seq[_ <: Any]): String = if (array.nonEmpty && target.nonEmpty) " and" else ""

  def andPart(array1: Seq[_ <: Any], array2: Seq[_ <: Any], target: Seq[_ <: Any]): String = if ((array1.nonEmpty || array2.nonEmpty) && target.nonEmpty) " and" else ""

  def andPart(array1: Seq[_ <: Any], array2: Seq[_ <: Any], array3: Seq[_ <: Any], target: Seq[_ <: Any]): String = if ((array1.nonEmpty || array2.nonEmpty || array3.nonEmpty) && target.nonEmpty) " and" else ""

  def andPart(option: Option[Any], target: Seq[_ <: Any]): String = if (option.isDefined && target.nonEmpty) " and" else ""

  def andPart(option: Option[Any], array: Seq[_ <: Any], target: Seq[_ <: Any]): String = if ((option.isDefined || array.nonEmpty) && target.nonEmpty) " and" else ""

  def andPart(option: Option[Any], array1: Seq[_ <: Any], array2: Seq[_ <: Any], target: Seq[_ <: Any]): String = if ((option.isDefined || array1.nonEmpty || array2.nonEmpty) && target.nonEmpty) " and" else ""

  def sourcesPart(sourceRefs: Seq[SourceRef]): String =
    if (sourceRefs.isEmpty)
      ""
    else
      " (" + sourceRefs.map(sourceTypeId =>
        s""""SeriesSources"."sourcetype" = '${sourceTypeId.sourceType}' and "SeriesSources"."sourceid" = ${sourceTypeId.sourceId}""")
        .mkString(" or ") + ")"

  def seriesTypesPart(seriesTypeIds: Seq[Long]): String =
    if (seriesTypeIds.isEmpty)
      ""
    else
      " (" + seriesTypeIds.map(seriesTypeId =>
        s""""SeriesSeriesTypes"."seriestypeid" = $seriesTypeId""")
        .mkString(" or ") + ")"

  def seriesTagsPart(seriesTagIds: Seq[Long]): String =
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

        implicit val getResult: GetResult[Study] = studiesGetResult

        val basePart =
          s"""select distinct("Studies"."id"),
        "Studies"."patient_id","Studies"."studyInstanceUID","Studies"."studyDescription","Studies"."studyDate","Studies"."studyID","Studies"."accessionNumber","Studies"."patientAge"
        from "Series" 
        inner join "Studies" on "Series"."study_id" = "Studies"."id""""

        val wherePart =
          s"""
        where
        "Studies"."patient_id" = $patientId"""

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

        implicit val getResult: GetResult[Series] = seriesGetResult

        val basePart =
          s"""select distinct("Series"."id"),
        "Series"."study_id","Series"."seriesInstanceUID","Series"."seriesDescription","Series"."seriesDate","Series"."modality","Series"."protocolName","Series"."bodyPartExamined","Series"."manufacturer","Series"."stationName","Series"."frameOfReferenceUID"
        from "Series""""

        val wherePart =
          s"""
        where
        "Series"."study_id" = $studyId"""

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
      patientsByNameAndIDAction(patient).headOption.flatMap { patientMaybe =>
        patientMaybe.map { dbp =>
          val updatePatient = patient.copy(id = dbp.id)
          updatePatientAction(updatePatient).map(_ => (updatePatient, false))
        }.getOrElse {
          insertPatientAction(patient).map((_, true))
        }
      }.flatMap {
        case (dbPatient, patientAdded) =>
          studiesByUidAndPatientAction(study, dbPatient).headOption.flatMap { studyMaybe =>
            studyMaybe.map { dbs =>
              val updateStudy = study.copy(id = dbs.id, patientId = dbs.patientId)
              updateStudyAction(updateStudy).map(_ => (updateStudy, false))
            }.getOrElse {
              insertStudyAction(study.copy(patientId = dbPatient.id)).map((_, true))
            }
          }.flatMap {
            case (dbStudy, studyAdded) =>
              seriesByUidAndStudyAction(series, dbStudy).headOption.flatMap { seriesMaybe =>
                seriesMaybe.map { dbs =>
                  val updateSeries = series.copy(id = dbs.id, studyId = dbs.studyId)
                  updateSeriesAction(updateSeries).map(_ => (updateSeries, false))
                }.getOrElse {
                  insertSeriesAction(series.copy(studyId = dbStudy.id)).map((_, true))
                }
              }.flatMap {
                case (dbSeries, seriesAdded) =>
                  imagesByUidAndSeriesAction(image, dbSeries).headOption.flatMap { imageMaybe =>
                    imageMaybe.map { dbi =>
                      val updateImage = image.copy(id = dbi.id, seriesId = dbi.seriesId)
                      updateImageAction(updateImage).map(_ => (updateImage, false))
                    }.getOrElse {
                      insertImageAction(image.copy(seriesId = dbSeries.id)).map((_, true))
                    }
                  }.flatMap {
                    case (dbImage, imageAdded) =>
                      seriesSourcesByIdAction(dbSeries.id).headOption.flatMap { seriesSourceMaybe =>
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
    seriesQuery.filter(_.id === seriesId).result.headOption
      .map(_.map(_ => addAndInsertSeriesTagForSeriesIdAction(seriesTag, seriesId)))
      .unwrap
  }

}

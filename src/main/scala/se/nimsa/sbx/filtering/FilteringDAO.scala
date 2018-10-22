package se.nimsa.sbx.filtering

import se.nimsa.dicom.data.TagPath
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.sbx.app.GeneralProtocol.{SourceRef, SourceType}
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class FilteringDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  private val toTagFilter = (id:Long, name: String, tagFilterType: String) => TagFilter(id, name, TagFilterType.withName(tagFilterType))
  private val toTagFilterTagPath = (id: Long, tagFilterId: Long, tagPath: String) => TagFilterTagPath(id, tagFilterId, TagPath.parse(tagPath).asInstanceOf[TagPathTag])
  private val toSourceTagFilter = (id: Long, sourceType: String, sourceId: Long, tagFilterId: Long) => SourceTagFilter(id, SourceType.withName(sourceType), sourceId, tagFilterId)

  val tagFilterQuery = TableQuery[TagFilterTable]

  class TagFilterTable(tag: Tag) extends Table[TagFilter](tag, TagFilterTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def tagFilterType = column[String]("tagfiltertype", O.Length(32))
    def * = (id, name, tagFilterType) <> (toTagFilter.tupled, (tf: TagFilter) => Option((tf.id, tf.name, tf.tagFilterType.toString)))
  }

  object TagFilterTable {
    val name = "TagFilters"
  }

  val tagPathQuery = TableQuery[TagPathTable]

  class TagPathTable(tag: Tag) extends Table[TagFilterTagPath](tag, TagPathTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def tagFilterId = column[Long]("tagfilterid")
    def tagPath = column[String]("tagpath")
    def fkTagFilter = foreignKey("fk_tag_filter", tagFilterId, tagFilterQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, tagFilterId, tagPath) <> (toTagFilterTagPath.tupled, (a: TagFilterTagPath) => Option((a.id, a.tagFilterId, a.tagPathTag.toString())))
  }

  object TagPathTable {
    val name = "TagPaths"
  }

  val sourceFilterQuery = TableQuery[SourceFilterTable]

  class SourceFilterTable(tag: Tag) extends Table[SourceTagFilter](tag, SourceFilterTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sourceType = column[String]("sourcetype", O.Length(64))
    def sourceId = column[Long]("sourceid")
    def tagFilterId = column[Long]("tagfilterid")
    def fkTagFilter2 = foreignKey("fk_tag_filter2", tagFilterId, tagFilterQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, sourceType, sourceId, tagFilterId) <> (toSourceTagFilter.tupled, (a: SourceTagFilter) => Option((a.id, a.sourceType.toString, a.sourceId, a.tagFilterId)))
  }

  object SourceFilterTable {
    val name = "SourceTagFilters"
  }

  def create(): Future[Unit] = createTables(dbConf, (TagFilterTable.name, tagFilterQuery), (TagPathTable.name, tagPathQuery),
    (SourceFilterTable.name, sourceFilterQuery))

  def drop(): Future[Unit] = db.run {
    (tagFilterQuery.schema ++ tagPathQuery.schema ++ sourceFilterQuery.schema).drop
  }

  def clear(): Future[Unit] = db.run {
    DBIO.seq(tagFilterQuery.delete, tagPathQuery.delete, sourceFilterQuery.delete)
  }

  def dump(): Future[Seq[(Option[String], Option[String])]] = db.run {
    val fullOuterJoin = for {
      (c, s) <- tagFilterQuery joinFull tagPathQuery on (_.id === _.tagFilterId)
    } yield (c.map(_.name), s.map(_.tagPath))
    fullOuterJoin.result
  }

  def createOrUpdateTagFilter(tagFilter: TagFilterSpec): Future[TagFilterSpec] = {
    val tagFilterRow = TagFilter(tagFilter.id, tagFilter.name, tagFilter.tagFilterType)
    val insertAction = for {
      tfr <- insertTagFilterAction(tagFilterRow)
      _ <- replaceTagFilterTagPathAction(tagFilter.tags.map(tftp => TagFilterTagPath(-1, tfr.id, tftp)))
    } yield tfr
    val res = db.run {
      getTagFilterByNameAction(tagFilter.name).flatMap {
        _.map {t =>
          replaceTagFilterTagPathAction(tagFilter.tags.map(tftp => TagFilterTagPath(-1, t.id, tftp))) andThen
          updateTagFilterAction(tagFilterRow).map(_ => tagFilterRow.copy(id = t.id))
        }.getOrElse {
          insertAction
        }
      }
    }
    res.map(r => {
      val res = tagFilter.copy(id = r.id)
      res
    })
  }

  def getAllSourceFilters: Future[Seq[SourceTagFilter]] = db.run(sourceFilterQuery.result)

  def createOrUpdateSourceFilter(sourceFilter: SourceTagFilter): Future[SourceTagFilter] = {
    db.run {
      getSourceFilterAction(SourceRef(sourceFilter.sourceType, sourceFilter.sourceId)).flatMap {
        _.map { sf =>
          val updatedSourceFilter = sf.copy(tagFilterId = sourceFilter.tagFilterId)
          updateSourceFilterAction(updatedSourceFilter).map(_ => updatedSourceFilter)
        }.getOrElse {
          insertSourceFilterAction(sourceFilter)
        }
      }
    }
  }

  def getSourceFilter(sourceRef: SourceRef): Future[Option[SourceTagFilter]] = {
    db.run(getSourceFilterAction(sourceRef))
  }

  def removeSourceFilter(id: Long): Future[Unit] =   db.run(sourceFilterQuery.filter(_.id === id).delete.map(_ => {}))

  def removeSourceFilter(sourceRef: SourceRef): Future[Unit] =
    db.run(
      sourceFilterQuery
        .filter(r => r.sourceType === sourceRef.sourceType.toString && r.sourceId === sourceRef.sourceId)
        .delete.map(_ => {}))


  def insertTagFilter(tagFilter: TagFilterSpec): Future[TagFilterSpec] = {
    val tagFilterRow = TagFilter(tagFilter.id, tagFilter.name, tagFilter.tagFilterType)
    val upsertAction = for {
        tfr <- insertTagFilterAction(tagFilterRow)
        _ <- replaceTagFilterTagPathAction(tagFilter.tags.map(tftp => TagFilterTagPath(-1, tfr.id, tftp)))
      } yield tfr

    db.run(upsertAction).map(r => tagFilter.copy(id = r.id))
  }

  def removeTagFilter(tagFilterId: Long): Future[Unit] = db.run(tagFilterQuery.filter(_.id === tagFilterId).delete.map(_ => {}))

  def listTagFilters(startIndex: Long, count: Long): Future[Seq[TagFilter]] = db.run {
    tagFilterQuery
      .drop(startIndex)
      .take(count)
      .result
  }

  def listSourceTagFilters(startIndex: Long, count: Long): Future[Seq[SourceTagFilter]] = db.run {
    sourceFilterQuery
      .drop(startIndex)
      .take(count)
      .result
  }

  def getTagPathsByFilterId(filterId: Long): Future[Seq[TagFilterTagPath]] = db.run {
    tagPathQuery.filter(_.tagFilterId === filterId).result
  }

  def getTagFilter(id: Long): Future[Option[TagFilterSpec]] =
    db.run(getTagFilterAction(id)).map(s => s.headOption.map(t => TagFilterSpec(t._1, s.map(_._2).flatten)))

  private def getTagFilterByNameAction(name: String) =
    tagFilterQuery
      .filter(_.name === name)
      .result
      .headOption

  private def getTagFilterAction(id: Long) = {
    val tagFilter = tagFilterQuery.filter(_.id === id)
    val tagPaths = tagPathQuery.filter(_.tagFilterId === id).sortBy(_.tagPath.asc.nullsLast)
    val leftOuterJoin = for {
      (c, s) <- tagFilter joinLeft tagPaths
    } yield (c, s)
    leftOuterJoin.result
  }

  private def updateTagFilterAction(tagFilter: TagFilter) =
    tagFilterQuery.filter(_.id === tagFilter.id).update(tagFilter)

  private def insertTagFilterAction(tagFilter: TagFilter) =
    (tagFilterQuery returning tagFilterQuery.map(_.id) += tagFilter)
      .map(generatedId => tagFilter.copy(id = generatedId))

  private def replaceTagFilterTagPathAction(tagPaths: Seq[TagFilterTagPath]) = {
    val id: Long = tagPaths.headOption.map(_.tagFilterId).getOrElse(-1)
    tagPathQuery.filter(_.tagFilterId === id).delete andThen
      (tagPathQuery ++= tagPaths)
  }

  private def insertSourceFilterAction(sourceTagFilter: SourceTagFilter) =
    (sourceFilterQuery returning sourceFilterQuery.map(_.id) += sourceTagFilter)
      .map(generatedId => sourceTagFilter.copy(id = generatedId))

  private def getSourceFilterAction(sourceRef: SourceRef) =
    sourceFilterQuery
      .filter(r => r.sourceType === sourceRef.sourceType.toString && r.sourceId === sourceRef.sourceId)
      .result
      .headOption

  private def updateSourceFilterAction(sourceTagFilter: SourceTagFilter) =
    sourceFilterQuery.filter(_.id === sourceTagFilter.id).update(sourceTagFilter)
}

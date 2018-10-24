package se.nimsa.sbx.filtering

import se.nimsa.dicom.data.TagPath
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.sbx.app.GeneralProtocol.{SourceRef, SourceType}
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.basic.DatabaseConfig
import slick.dbio.DBIOAction
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class FilteringDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  implicit val tagPathColumnType: BaseColumnType[TagPathTag] =
    MappedColumnType.base[TagPathTag, String](
      tagPath => tagPath.toString, // map TagPathTag to String
      tagPathString => TagPath.parse(tagPathString).asInstanceOf[TagPathTag] // map String to TagPathTag
    )

  implicit val tagFilterTypeColumnType: BaseColumnType[TagFilterType] =
    MappedColumnType.base[TagFilterType, String](_.toString, TagFilterType.withName)

  implicit val sourceTypeColumnType: BaseColumnType[SourceType] =
    MappedColumnType.base[SourceType, String](_.toString, SourceType.withName)

  val tagFilterQuery = TableQuery[TagFilterTable]

  class TagFilterTable(tag: Tag) extends Table[TagFilter](tag, TagFilterTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def tagFilterType = column[TagFilterType]("tagfiltertype", O.Length(32))
    def * = (id, name, tagFilterType) <> (TagFilter.tupled, TagFilter.unapply)
  }

  object TagFilterTable {
    val name = "TagFilters"
  }

  val tagPathQuery = TableQuery[TagPathTable]

  class TagPathTable(tag: Tag) extends Table[TagFilterTagPath](tag, TagPathTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def tagFilterId = column[Long]("tagfilterid")
    def tagPath = column[TagPathTag]("tagpath")
    def fkTagFilter = foreignKey("fk_tag_filter", tagFilterId, tagFilterQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, tagFilterId, tagPath) <> (TagFilterTagPath.tupled, TagFilterTagPath.unapply)
//    def * = (id, tagFilterId, tagPath) <> (toTagFilterTagPath.tupled, (a: TagFilterTagPath) => Option((a.id, a.tagFilterId, a.tagPathTag.toString())))
  }

  object TagPathTable {
    val name = "TagPaths"
  }

  val sourceFilterQuery = TableQuery[SourceFilterTable]

  class SourceFilterTable(tag: Tag) extends Table[SourceTagFilter](tag, SourceFilterTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sourceType = column[SourceType]("sourcetype", O.Length(64))
    def sourceId = column[Long]("sourceid")
    def tagFilterId = column[Long]("tagfilterid")
    def fkTagFilter2 = foreignKey("fk_tag_filter2", tagFilterId, tagFilterQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, sourceType, sourceId, tagFilterId) <> (SourceTagFilter.tupled, SourceTagFilter.unapply)
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
//
//  def dump(): Future[Seq[(Option[String], Option[TagPathTag])]] = db.run {
//    val fullOuterJoin = for {
//      (c, s) <- tagFilterQuery joinFull tagPathQuery on (_.id === _.tagFilterId) joinFull sourceFilterQuery on (_._1 === _.tagFilterId)
//    } yield (c.map(_.name), s.map(_.tagPath))
//    fullOuterJoin.result
//  }

  def createOrUpdateTagFilter(tagFilter: TagFilterSpec): Future[TagFilterSpec] = {
    val tagFilterRow = TagFilter(tagFilter.id, tagFilter.name, tagFilter.tagFilterType)
    val insertAction = for {
      tfr <- insertTagFilterAction(tagFilterRow)
      _ <- replaceTagFilterTagPathAction(tfr.id, tagFilter.tags.map(tftp => TagFilterTagPath(-1, tfr.id, tftp)))
    } yield tfr
    db.run {
      getTagFilterByNameAction(tagFilter.name).flatMap {
        _.map {t =>
          replaceTagFilterTagPathAction(t.id, tagFilter.tags.map(tftp => TagFilterTagPath(-1, t.id, tftp))) andThen
          updateTagFilterAction(tagFilterRow).map(_ => tagFilterRow.copy(id = t.id))
        }.getOrElse {
          insertAction
        }
      }.map(tf => tagFilter.copy(id = tf.id))
    }
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

  def removeSourceFilter(id: Long): Future[Unit] = db.run(sourceFilterQuery.filter(_.id === id).delete.map(_ => {}))

  def removeSourceFilter(sourceRef: SourceRef): Future[Unit] =
    db.run(
      sourceFilterQuery
        .filter(r => r.sourceType === sourceRef.sourceType && r.sourceId === sourceRef.sourceId)
        .delete.map(_ => {})
    )

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

  def getTagFilter(id: Long): Future[Option[TagFilterSpec]] = db.run(getTagFilterAction(id))

  def getTagFilterForSource(ref: SourceRef): Future[Option[TagFilterSpec]] = db.run(getTagFilterForSourceAction(ref))

  private def getTagFilterForSourceAction(ref: SourceRef) = {
    getSourceFilterAction(ref)
      .flatMap( sourceFilterOption =>
        DBIOAction.sequenceOption(sourceFilterOption.map(sourceFilter =>
          getTagFilterAction(sourceFilter.tagFilterId))).map(_.flatten)
      )
  }

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
    leftOuterJoin.result.map(s => s.headOption.map(t => TagFilterSpec(t._1, s.flatMap(_._2))))
  }

  private def updateTagFilterAction(tagFilter: TagFilter) =
    tagFilterQuery.filter(_.id === tagFilter.id).update(tagFilter)

  private def insertTagFilterAction(tagFilter: TagFilter) =
    (tagFilterQuery returning tagFilterQuery.map(_.id) += tagFilter)
      .map(generatedId => tagFilter.copy(id = generatedId))

  private def replaceTagFilterTagPathAction(tagFilterId: Long, tagPaths: Seq[TagFilterTagPath]) = {
    tagPathQuery.filter(_.tagFilterId === tagFilterId).delete andThen
      (tagPathQuery ++= tagPaths)
  }

  private def insertSourceFilterAction(sourceTagFilter: SourceTagFilter) =
    (sourceFilterQuery returning sourceFilterQuery.map(_.id) += sourceTagFilter)
      .map(generatedId => sourceTagFilter.copy(id = generatedId))

  private def getSourceFilterAction(sourceRef: SourceRef) =
    sourceFilterQuery
      .filter(r => r.sourceType === sourceRef.sourceType && r.sourceId === sourceRef.sourceId)
      .result
      .headOption

  private def updateSourceFilterAction(sourceTagFilter: SourceTagFilter) =
    sourceFilterQuery.filter(_.id === sourceTagFilter.id).update(sourceTagFilter)
}

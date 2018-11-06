package se.nimsa.sbx.filtering

import se.nimsa.dicom.data.TagPath
import se.nimsa.sbx.app.GeneralProtocol.{SourceRef, SourceType}
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class FilteringDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  implicit val tagPathColumnType: BaseColumnType[TagPath] =
    MappedColumnType.base[TagPath, String](
      tagPath => tagPath.toString, // map TagPathTag to String
      tagPathString => TagPath.parse(tagPathString) // map String to TagPath
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
    def tagPath = column[TagPath]("tagpath")
    def fkTagFilter = foreignKey("fk_tag_filter", tagFilterId, tagFilterQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, tagFilterId, tagPath) <> (TagFilterTagPath.tupled, TagFilterTagPath.unapply)
  }

  object TagPathTable {
    val name = "TagPaths"
  }

  val sourceFilterQuery = TableQuery[SourceFilterTable]

  class SourceFilterTable(tag: Tag) extends Table[SourceTagFilter](tag, SourceFilterTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sourceType = column[SourceType]("sourcetype", O.Length(64))
    def sourceName = column[String]("sourcename")
    def sourceId = column[Long]("sourceid")
    def tagFilterId = column[Long]("tagfilterid")
    def tagFilterName = column[String]("tagfiltername")
    def fkTagFilter2 = foreignKey("fk_tag_filter2", tagFilterId, tagFilterQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, sourceType, sourceName, sourceId, tagFilterId, tagFilterName) <> (SourceTagFilter.tupled, SourceTagFilter.unapply)
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


  def insertTagFilter(tagFilter: TagFilter): Future[TagFilter] = db.run {
    (tagFilterQuery returning tagFilterQuery.map(_.id) += tagFilter)
      .map(generatedId => tagFilter.copy(id = generatedId))
  }

  def insertTagFilterTagPath(tagFilterTagPath: TagFilterTagPath): Future[TagFilterTagPath] = db.run {
    (tagPathQuery returning tagPathQuery.map(_.id) += tagFilterTagPath)
      .map(generatedId => tagFilterTagPath.copy(id = generatedId))
  }

  def insertSourceTagFilter(sourceTagFilter: SourceTagFilter): Future[SourceTagFilter] = db.run {
    (sourceFilterQuery returning sourceFilterQuery.map(_.id) += sourceTagFilter)
      .map(generatedId => sourceTagFilter.copy(id = generatedId))
  }

  def removeTagFilterById(tagFilterId: Long): Future[Unit] = db.run {
    tagFilterQuery.filter(_.id === tagFilterId).delete.map(_ => {})
  }

  def removeTagFilterTagPathById(tagFilterTagPatbId: Long): Future[Unit] = db.run {
    tagPathQuery.filter(_.id === tagFilterTagPatbId).delete.map(_ => {})
  }

  def removeSourceTagFilterById(sourceTagFilterId: Long): Future[Unit] = db.run {
    sourceFilterQuery.filter(_.id === sourceTagFilterId).delete.map(_ => {})
  }

  def listTagFilters(startIndex: Long, count: Long): Future[Seq[TagFilter]] = db.run {
    tagFilterQuery
      .drop(startIndex)
      .take(count)
      .result
  }

  def listTagFilterTagPathsByTagFilterId(tagFilterId: Long, startIndex: Long, count: Long): Future[Seq[TagFilterTagPath]] =
    db.run {
      tagPathQuery
        .filter(_.tagFilterId === tagFilterId)
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

  def tagFiltersToTagPaths(sourceRef: SourceRef): Future[Map[TagFilter, Seq[TagFilterTagPath]]] = db.run {
    val tagFiltersForSource = for {
      a <- sourceFilterQuery if a.sourceType === sourceRef.sourceType && a.sourceId === sourceRef.sourceId
      b <- tagFilterQuery if a.tagFilterId === b.id
    } yield b

    tagFiltersForSource
      .result
      .flatMap { tagFilters =>
        DBIO.sequence {
          tagFilters.map { tagFilter =>
            tagPathQuery
              .filter(_.tagFilterId === tagFilter.id)
              .result
              .map(t => (tagFilter, t))
          }
        }
      }
      .map(_.toMap)
  }

  def removeSourceTagFiltersBySourceRef(sourceRef: SourceRef): Future[Unit] = db.run {
    sourceFilterQuery
      .filter(_.sourceType === sourceRef.sourceType)
      .filter(_.sourceId === sourceRef.sourceId)
      .delete.map(_ => {})
  }

}

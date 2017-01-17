/*
 * Copyright 2016 Lars Edenbrandt
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

package se.nimsa.sbx.seriestype

import SeriesTypeProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class SeriesTypeDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.driver.api._

  val db = dbConf.db

  class SeriesTypeTable(tag: Tag) extends Table[SeriesType](tag, SeriesTypeTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def idxUniqueName = index("idx_unique_series_type_name", name, unique = true)
    def * = (id, name) <> (SeriesType.tupled, SeriesType.unapply)
  }

  object SeriesTypeTable {
    val name = "SeriesTypes"
  }

  val seriesTypes = TableQuery[SeriesTypeTable]

  class SeriesTypeRuleTable(tag: Tag) extends Table[SeriesTypeRule](tag, SeriesTypeRuleTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesTypeId = column[Long]("seriestypeid")
    def fkSeriesType = foreignKey("fk_series_type", seriesTypeId, seriesTypes)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, seriesTypeId) <> (SeriesTypeRule.tupled, SeriesTypeRule.unapply)
  }

  object SeriesTypeRuleTable {
    val name = "SeriesTypeRules"
  }

  val seriesTypeRules = TableQuery[SeriesTypeRuleTable]

  class SeriesTypeRuleAttributeTable(tag: Tag) extends Table[SeriesTypeRuleAttribute](tag, SeriesTypeRuleAttributeTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesTypeRuleId = column[Long]("seriestyperuleid")
    def dicomTag = column[Int]("tag")
    def name = column[String]("name")
    def tagPath = column[Option[String]]("tagpath")
    def namePath = column[Option[String]]("namepath")
    def values = column[String]("values")
    def fkSeriesTypeRule = foreignKey("fk_series_type_rule", seriesTypeRuleId, seriesTypeRules)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, seriesTypeRuleId, dicomTag, name, tagPath, namePath, values) <> (SeriesTypeRuleAttribute.tupled, SeriesTypeRuleAttribute.unapply)
  }

  object SeriesTypeRuleAttributeTable {
    val name = "SeriesTypeRuleAttributes"
  }

  val seriesTypeRuleAttributes = TableQuery[SeriesTypeRuleAttributeTable]

  private class SeriesSeriesTypeTable(tag: Tag) extends Table[SeriesSeriesType](tag, SeriesSeriesTypeTable.name) {
    def seriesId = column[Long]("seriesid")
    def seriesTypeId = column[Long]("seriestypeid")
    def pk = primaryKey("pk_seriestype", (seriesId, seriesTypeId))
    def fkSeriesType = foreignKey("fk_seriestype_seriesseriestype", seriesTypeId, seriesTypes)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (seriesId, seriesTypeId) <> (SeriesSeriesType.tupled, SeriesSeriesType.unapply)
  }

  object SeriesSeriesTypeTable {
    val name = "SeriesSeriesTypes"
  }

  private val seriesSeriesTypes = TableQuery[SeriesSeriesTypeTable]

  def create() = createTables(dbConf, Seq(
    (SeriesTypeTable.name, seriesTypes),
    (SeriesTypeRuleTable.name, seriesTypeRules),
    (SeriesTypeRuleAttributeTable.name, seriesTypeRuleAttributes),
    (SeriesSeriesTypeTable.name, seriesSeriesTypes)))

  def drop() = db.run {
    (seriesTypes.schema ++ seriesTypeRules.schema ++ seriesTypeRuleAttributes.schema ++ seriesSeriesTypes.schema).drop
  }

  def clear() = db.run {
    DBIO.seq(seriesTypes.delete, seriesTypeRules.delete, seriesTypeRuleAttributes.delete, seriesSeriesTypes.delete)
  }

  def insertSeriesType(seriesType: SeriesType): Future[SeriesType] = db.run {
    seriesTypes returning seriesTypes.map(_.id) += seriesType
  }.map(generatedId => seriesType.copy(id = generatedId))

  def insertSeriesTypeRule(seriesTypeRule: SeriesTypeRule): Future[SeriesTypeRule] = db.run {
    seriesTypeRules returning seriesTypeRules.map(_.id) += seriesTypeRule
  }.map(generatedId => seriesTypeRule.copy(id = generatedId))

  def insertSeriesTypeRuleAttribute(seriesTypeRuleAttribute: SeriesTypeRuleAttribute): Future[SeriesTypeRuleAttribute] = db.run {
    seriesTypeRuleAttributes returning seriesTypeRuleAttributes.map(_.id) += seriesTypeRuleAttribute
  }.map(generatedId => seriesTypeRuleAttribute.copy(id = generatedId))

  def seriesTypeForName(seriesTypeName: String): Future[Option[SeriesType]] = db.run {
    seriesTypes.filter(_.name === seriesTypeName).result.headOption
  }

  def updateSeriesType(seriesType: SeriesType): Future[Unit] = db.run {
    seriesTypes.filter(_.id === seriesType.id).update(seriesType)
  }.map(_ => Unit)

  def updateSeriesTypeRule(seriesTypeRule: SeriesTypeRule): Future[Unit] = db.run {
    seriesTypeRules.filter(_.id === seriesTypeRule.id).update(seriesTypeRule)
  }.map(_ => Unit)

  def updateSeriesTypeRuleAttribute(seriesTypeRuleAttribute: SeriesTypeRuleAttribute): Future[Unit] = db.run {
    seriesTypeRuleAttributes.filter(_.id === seriesTypeRuleAttribute.id).update(seriesTypeRuleAttribute)
  }.map(_ => Unit)

  def listSeriesTypes(startIndex: Long, count: Long): Future[Seq[SeriesType]] = db.run {
    seriesTypes
      .drop(startIndex)
      .take(count)
      .result
  }

  def listSeriesTypeRulesForSeriesTypeId(seriesTypeId: Long): Future[Seq[SeriesTypeRule]] = db.run {
    seriesTypeRules.filter(_.seriesTypeId === seriesTypeId).result
  }

  def listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRuleId: Long): Future[Seq[SeriesTypeRuleAttribute]] = db.run {
    seriesTypeRuleAttributes.filter(_.seriesTypeRuleId === seriesTypeRuleId).result
  }

  def listSeriesSeriesTypes: Future[Seq[SeriesSeriesType]] = db.run {
    seriesSeriesTypes.result
  }

  def seriesTypeForId(seriesTypeId: Long) = db.run {
    seriesTypes.filter(_.id === seriesTypeId).result.headOption
  }

  def removeSeriesType(seriesTypeId: Long): Future[Unit] = db.run {
    seriesTypes.filter(_.id === seriesTypeId).delete
  }.map(_ => Unit)

  def removeSeriesTypeRule(seriesTypeRuleId: Long): Future[Unit] = db.run {
    seriesTypeRules.filter(_.id === seriesTypeRuleId).delete
  }.map(_ => Unit)

  def removeSeriesTypeRuleAttribute(seriesTypeRuleAttributeId: Long): Future[Unit] = db.run {
    seriesTypeRuleAttributes.filter(_.id === seriesTypeRuleAttributeId).delete
  }.map(_ => Unit)

  def upsertSeriesSeriesType(seriesSeriesType: SeriesSeriesType): Future[SeriesSeriesType] = db.run {
    seriesSeriesTypes.insertOrUpdate(seriesSeriesType)
  }.map(_ => seriesSeriesType)

  def listSeriesSeriesTypesForSeriesId(seriesId: Long): Future[Seq[SeriesSeriesType]] = db.run {
    seriesSeriesTypes.filter(_.seriesId === seriesId).result
  }

  def removeSeriesTypesForSeriesId(seriesId: Long): Future[Unit] = db.run {
    seriesSeriesTypes.filter(_.seriesId === seriesId).delete
  }.map(_ => Unit)

  def removeSeriesTypeForSeriesId(seriesId: Long, seriesTypeId: Long): Future[Unit] = db.run {
    seriesSeriesTypes.filter(_.seriesId === seriesId).filter(_.seriesTypeId === seriesTypeId).delete
  }.map(_ => Unit)

  def seriesTypesForSeries(seriesId: Long): Future[Seq[SeriesType]] = db.run {
    val innerJoin = for {
      sst <- seriesSeriesTypes.filter(_.seriesId === seriesId)
      st <- seriesTypes if sst.seriesTypeId === st.id
    } yield st
    innerJoin.result
  }
}

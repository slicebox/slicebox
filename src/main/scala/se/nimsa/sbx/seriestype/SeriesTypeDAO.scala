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

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import SeriesTypeProtocol._

class SeriesTypeDAO(val driver: JdbcProfile) {

  import driver.simple._

  class SeriesTypeTable(tag: Tag) extends Table[SeriesType](tag, "SeriesTypes") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def name = column[String]("name")

    def idxUniqueName = index("idx_unique_series_type_name", name, unique = true)

    def * = (id, name) <>(SeriesType.tupled, SeriesType.unapply)
  }

  val seriesTypeQuery = TableQuery[SeriesTypeTable]

  class SeriesTypeRuleTable(tag: Tag) extends Table[SeriesTypeRule](tag, "SeriesTypeRules") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def seriesTypeId = column[Long]("seriestypeid")

    def fkSeriesType = foreignKey("fk_series_type", seriesTypeId, seriesTypeQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (id, seriesTypeId) <>(SeriesTypeRule.tupled, SeriesTypeRule.unapply)
  }

  val seriesTypeRuleQuery = TableQuery[SeriesTypeRuleTable]

  class SeriesTypeRuleAttributeTable(tag: Tag) extends Table[SeriesTypeRuleAttribute](tag, "SeriesTypeRuleAttributes") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def seriesTypeRuleId = column[Long]("seriestyperuleid")

    def dicomTag = column[Int]("tag")

    def name = column[String]("name")

    def tagPath = column[Option[String]]("tagpath")

    def namePath = column[Option[String]]("namepath")

    def values = column[String]("values")

    def fkSeriesTypeRule = foreignKey("fk_series_type_rule", seriesTypeRuleId, seriesTypeRuleQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (id, seriesTypeRuleId, dicomTag, name, tagPath, namePath, values) <>(SeriesTypeRuleAttribute.tupled, SeriesTypeRuleAttribute.unapply)
  }

  val seriesTypeRuleAttributeQuery = TableQuery[SeriesTypeRuleAttributeTable]

  private class SeriesSeriesTypeTable(tag: Tag) extends Table[SeriesSeriesType](tag, "SeriesSeriesTypes") {
    def seriesId = column[Long]("seriesid")

    def seriesTypeId = column[Long]("seriestypeid")

    def pk = primaryKey("pk_seriestype", (seriesId, seriesTypeId))

    def fkSeriesType = foreignKey("fk_seriestype_seriesseriestype", seriesTypeId, seriesTypeQuery)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (seriesId, seriesTypeId) <>(SeriesSeriesType.tupled, SeriesSeriesType.unapply)
  }

  private val seriesSeriesTypeQuery = TableQuery[SeriesSeriesTypeTable]

  def create(implicit session: Session): Unit = {
    if (MTable.getTables("SeriesTypes").list.isEmpty) seriesTypeQuery.ddl.create
    if (MTable.getTables("SeriesTypeRules").list.isEmpty) seriesTypeRuleQuery.ddl.create
    if (MTable.getTables("SeriesTypeRuleAttributes").list.isEmpty) seriesTypeRuleAttributeQuery.ddl.create
    if (MTable.getTables("SeriesSeriesTypes").list.isEmpty) seriesSeriesTypeQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (seriesTypeQuery.ddl ++ seriesTypeRuleQuery.ddl ++ seriesTypeRuleAttributeQuery.ddl ++ seriesSeriesTypeQuery.ddl).drop

  def clear(implicit session: Session) = {
    seriesTypeQuery.delete
    seriesTypeRuleQuery.delete
    seriesTypeRuleAttributeQuery.delete
    seriesSeriesTypeQuery.delete
  }

  def insertSeriesType(seriesType: SeriesType)(implicit session: Session): SeriesType = {
    val generatedId = (seriesTypeQuery returning seriesTypeQuery.map(_.id)) += seriesType
    seriesType.copy(id = generatedId)
  }

  def insertSeriesTypeRule(seriesTypeRule: SeriesTypeRule)(implicit session: Session): SeriesTypeRule = {
    val generatedId = (seriesTypeRuleQuery returning seriesTypeRuleQuery.map(_.id)) += seriesTypeRule
    seriesTypeRule.copy(id = generatedId)
  }

  def insertSeriesTypeRuleAttribute(seriesTypeRuleAttribute: SeriesTypeRuleAttribute)(implicit session: Session): SeriesTypeRuleAttribute = {
    val generatedId = (seriesTypeRuleAttributeQuery returning seriesTypeRuleAttributeQuery.map(_.id)) += seriesTypeRuleAttribute
    seriesTypeRuleAttribute.copy(id = generatedId)
  }

  def seriesTypeForName(seriesTypeName: String)(implicit session: Session): Option[SeriesType] =
    seriesTypeQuery.filter(_.name === seriesTypeName).firstOption

  def updateSeriesType(seriesType: SeriesType)(implicit session: Session): Unit =
    seriesTypeQuery.filter(_.id === seriesType.id).update(seriesType)

  def updateSeriesTypeRule(seriesTypeRule: SeriesTypeRule)(implicit session: Session): Unit =
    seriesTypeRuleQuery.filter(_.id === seriesTypeRule.id).update(seriesTypeRule)

  def updateSeriesTypeRuleAttribute(seriesTypeRuleAttribute: SeriesTypeRuleAttribute)(implicit session: Session): Unit =
    seriesTypeRuleAttributeQuery.filter(_.id === seriesTypeRuleAttribute.id).update(seriesTypeRuleAttribute)

  def listSeriesTypes(startIndex: Long, count: Long)(implicit session: Session): List[SeriesType] =
    seriesTypeQuery
      .drop(startIndex)
      .take(count)
      .list

  def listSeriesTypeRulesForSeriesTypeId(seriesTypeId: Long)(implicit session: Session): List[SeriesTypeRule] =
    seriesTypeRuleQuery.filter(_.seriesTypeId === seriesTypeId).list

  def listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRuleId: Long)(implicit session: Session): List[SeriesTypeRuleAttribute] =
    seriesTypeRuleAttributeQuery.filter(_.seriesTypeRuleId === seriesTypeRuleId).list

  def listSeriesSeriesTypes(implicit session: Session): List[SeriesSeriesType] =
    seriesSeriesTypeQuery.list

  def seriesTypeForId(seriesTypeId: Long)(implicit session: Session) =
    seriesTypeQuery.filter(_.id === seriesTypeId).firstOption

  def removeSeriesType(seriesTypeId: Long)(implicit session: Session): Unit =
    seriesTypeQuery.filter(_.id === seriesTypeId).delete

  def removeSeriesTypeRule(seriesTypeRuleId: Long)(implicit session: Session): Unit =
    seriesTypeRuleQuery.filter(_.id === seriesTypeRuleId).delete

  def removeSeriesTypeRuleAttribute(seriesTypeRuleAttributeId: Long)(implicit session: Session): Unit =
    seriesTypeRuleAttributeQuery.filter(_.id === seriesTypeRuleAttributeId).delete

  def upsertSeriesSeriesType(seriesSeriesType: SeriesSeriesType)(implicit session: Session): SeriesSeriesType = {
    seriesSeriesTypeQuery insertOrUpdate seriesSeriesType
    seriesSeriesType
  }

  def listSeriesSeriesTypesForSeriesId(seriesId: Long)(implicit session: Session): List[SeriesSeriesType] =
    seriesSeriesTypeQuery.filter(_.seriesId === seriesId).list

  def removeSeriesTypesForSeriesId(seriesId: Long)(implicit session: Session): Unit =
    seriesSeriesTypeQuery.filter(_.seriesId === seriesId).delete

  def seriesTypesForSeries(seriesId: Long)(implicit session: Session) =
    seriesSeriesTypeQuery.filter(_.seriesId === seriesId)
      .innerJoin(seriesTypeQuery).on(_.seriesTypeId === _.id)
      .map(_._2).list

}

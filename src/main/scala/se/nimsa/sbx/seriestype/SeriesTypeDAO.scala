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

package se.nimsa.sbx.seriestype

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import SeriesTypeProtocol._

class SeriesTypeDAO(val driver: JdbcProfile) {
  import driver.simple._
  
  private val toSeriesType = (id: Long, name: String) => SeriesType(id, name)

  private val fromSeriesType = (seriesType: SeriesType) => Option((seriesType.id, seriesType.name))

  private class SeriesTypeTable(tag: Tag) extends Table[SeriesType](tag, "SeriesTypes") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def idxUniqueName = index("idx_unique_series_type_name", name, unique = true)
    def * = (id, name) <> (toSeriesType.tupled, fromSeriesType)
  }

  private val seriesTypeQuery = TableQuery[SeriesTypeTable]
  
  
  private val toSeriesTypeRule = (id: Long, seriesTypeId: Long) => SeriesTypeRule(id, seriesTypeId)
  
  private val fromSeriesTypeRule = (seriesTypeRule: SeriesTypeRule) => Option((seriesTypeRule.id, seriesTypeRule.seriesTypeId))
  
  private class SeriesTypeRuleTable(tag: Tag) extends Table[SeriesTypeRule](tag, "SeriesTypeRules") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesTypeId = column[Long]("seriestypeid")
    def fkSeriesType = foreignKey("fk_series_type", seriesTypeId, seriesTypeQuery)(_.id, onDelete=ForeignKeyAction.Cascade)
    def * = (id, seriesTypeId) <> (toSeriesTypeRule.tupled, fromSeriesTypeRule)
  }
  
  private val seriesTypeRuleQuery = TableQuery[SeriesTypeRuleTable]
  
  
  private val toSeriesTypeRuleAttribute = (id: Long, seriesTypeRuleId: Long, group: Int, element: Int, path: Option[String], value: String) =>
    SeriesTypeRuleAttribute(id, seriesTypeRuleId, group, element, path, value)
    
  private val fromSeriesTypeRuleAttribute = (seriesTypeRuleAttribute: SeriesTypeRuleAttribute) =>
    Option((seriesTypeRuleAttribute.id, seriesTypeRuleAttribute.seriesTypeRuleId, seriesTypeRuleAttribute.group, seriesTypeRuleAttribute.element, seriesTypeRuleAttribute.path, seriesTypeRuleAttribute.value))
  
  private class SeriesTypeRuleAttributeTable(tag: Tag) extends Table[SeriesTypeRuleAttribute](tag, "SeriesTypeRuleAttributes") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesTypeRuleId = column[Long]("seriestyperuleid")
    def group = column[Int]("group")
    def element = column[Int]("element")
    def path = column[Option[String]]("path")
    def value = column[String]("value")
    def fkSeriesTypeRule = foreignKey("fk_series_type_rule", seriesTypeRuleId, seriesTypeRuleQuery)(_.id, onDelete=ForeignKeyAction.Cascade)
    def * = (id, seriesTypeRuleId, group, element, path, value) <> (toSeriesTypeRuleAttribute.tupled, fromSeriesTypeRuleAttribute)
  }
    
  private val seriesTypeRuleAttributeQuery = TableQuery[SeriesTypeRuleAttributeTable]
  
  def create(implicit session: Session): Unit =
    if (MTable.getTables("SeriesTypes").list.isEmpty) {
      (seriesTypeQuery.ddl ++ seriesTypeRuleQuery.ddl ++ seriesTypeRuleAttributeQuery.ddl).create
    }

  def drop(implicit session: Session): Unit =
    (seriesTypeQuery.ddl ++ seriesTypeRuleQuery.ddl ++ seriesTypeRuleAttributeQuery.ddl).drop
    
    
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
  
  def updateSeriesType(seriesType: SeriesType)(implicit session: Session): Unit =
    seriesTypeQuery.filter(_.id === seriesType.id).update(seriesType)
    
  def updateSeriesTypeRule(seriesTypeRule: SeriesTypeRule)(implicit session: Session): Unit =
    seriesTypeRuleQuery.filter(_.id === seriesTypeRule.id).update(seriesTypeRule)
    
  def updateSeriesTypeRuleAttribute(seriesTypeRuleAttribute: SeriesTypeRuleAttribute)(implicit session: Session): Unit =
    seriesTypeRuleAttributeQuery.filter(_.id === seriesTypeRuleAttribute.id).update(seriesTypeRuleAttribute)
  
  def listSeriesTypes(implicit session: Session): List[SeriesType] =
    seriesTypeQuery.list
    
  def listSeriesTypeRulesForSeriesTypeId(seriesTypeId: Long)(implicit session: Session): List[SeriesTypeRule] =
    seriesTypeRuleQuery.filter(_.seriesTypeId === seriesTypeId).list
    
  def listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRuleId: Long)(implicit session: Session): List[SeriesTypeRuleAttribute] =
    seriesTypeRuleAttributeQuery.filter(_.seriesTypeRuleId === seriesTypeRuleId).list
    
  def removeSeriesType(seriesTypeId: Long)(implicit session: Session): Unit =
    seriesTypeQuery.filter(_.id === seriesTypeId).delete
    
  def removeSeriesTypeRule(seriesTypeRuleId: Long)(implicit session: Session): Unit =
    seriesTypeRuleQuery.filter(_.id === seriesTypeRuleId).delete
    
  def removeSeriesTypeRuleAttribute(seriesTypeRuleAttributeId: Long)(implicit session: Session): Unit =
    seriesTypeRuleAttributeQuery.filter(_.id === seriesTypeRuleAttributeId).delete
}

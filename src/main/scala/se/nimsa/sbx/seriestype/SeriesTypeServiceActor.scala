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

import SeriesTypeProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Stash
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.SequentialPipeToSupport

class SeriesTypeServiceActor(dbProps: DbProps) extends Actor with Stash
  with SequentialPipeToSupport with ExceptionCatching {
  
  val db = dbProps.db
  val seriesTypeDao = new SeriesTypeDAO(dbProps.driver)
  
  setupDb()

  def receive = LoggingReceive {
    
    case msg: SeriesTypeRequest =>

      catchAndReport {

        msg match {

          case GetSeriesTypes =>
            val seriesTypes = getSeriesTypesFromDb()
            sender ! SeriesTypes(seriesTypes)
            
          case AddSeriesType(seriesType) =>
            val dbSeriesType = addSeriesTypeToDb(seriesType)
            sender ! SeriesTypeAdded(dbSeriesType)
            
          case UpdateSeriesType(seriesType) =>
            val dbSeriesType = updateSeriesTypeInDb(seriesType)
            sender ! SeriesTypeUpdated
            
          case RemoveSeriesType(seriesTypeId) =>
            removeSeriesTypeFromDb(seriesTypeId)
            sender ! SeriesTypeRemoved(seriesTypeId)
            
          case GetSeriesTypeRules(seriesTypeId) =>
            val seriesTypeRules = getSeriesTypeRulesFromDb(seriesTypeId)
            sender ! SeriesTypeRules(seriesTypeRules)
            
          case AddSeriesTypeRule(seriesTypeRule) =>
            val dbSeriesTypeRule = addSeriesTypeRuleToDb(seriesTypeRule)
            sender ! SeriesTypeRuleAdded(dbSeriesTypeRule)
            
          case RemoveSeriesTypeRule(seriesTypeRuleId) =>
            removeSeriesTypeRuleFromDb(seriesTypeRuleId)
            sender ! SeriesTypeRuleRemoved(seriesTypeRuleId)
            
          case GetSeriesTypeRuleAttributes(seriesTypeRuleId) =>
            val seriesTypeRuleAttributes = getSeriesTypeRuleAttributesFromDb(seriesTypeRuleId)
            sender ! SeriesTypeRuleAttributes(seriesTypeRuleAttributes)
            
          case AddSeriesTypeRuleAttribute(seriesTypeRuleAttribute) =>
            val dbSeriesTypeRuleAttribute = addSeriesTypeRuleAttributeToDb(seriesTypeRuleAttribute)
            sender ! SeriesTypeRuleAttributeAdded(dbSeriesTypeRuleAttribute)
            
          case RemoveSeriesTypeRuleAttribute(seriesTypeRuleAttributeId) =>
            removeSeriesTypeRuleAttributeFromDb(seriesTypeRuleAttributeId)
            sender ! SeriesTypeRuleAttributeRemoved(seriesTypeRuleAttributeId)
        }
      }
  }
  
  def setupDb(): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.create
    }

  def teardownDb(): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.drop
    }
  
  def getSeriesTypesFromDb(): Seq[SeriesType] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypes
    }
  
  def addSeriesTypeToDb(seriesType: SeriesType): SeriesType =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(seriesType)
    }
  
  def updateSeriesTypeInDb(seriesType: SeriesType): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.updateSeriesType(seriesType)
    }
  
  def removeSeriesTypeFromDb(seriesTypeId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesType(seriesTypeId)
    }
  
  def getSeriesTypeRulesFromDb(seriesTypeId: Long): Seq[SeriesTypeRule] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypeRulesForSeriesTypeId(seriesTypeId)
    }
  
  def getSeriesTypeRuleAttributesFromDb(seriesTypeRuleId: Long): Seq[SeriesTypeRuleAttribute] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRuleId)
    }
  
  def addSeriesTypeRuleToDb(seriesTypeRule: SeriesTypeRule): SeriesTypeRule =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRule(seriesTypeRule)
    }
  
  def removeSeriesTypeRuleFromDb(seriesTypeRuleId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesTypeRule(seriesTypeRuleId)
    }
  
  def addSeriesTypeRuleAttributeToDb(seriesTypeRuleAttribute: SeriesTypeRuleAttribute): SeriesTypeRuleAttribute =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRuleAttribute(seriesTypeRuleAttribute)
    }
  
  def removeSeriesTypeRuleAttributeFromDb(seriesTypeRuleAttributeId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesTypeRuleAttribute(seriesTypeRuleAttributeId)
    }
}


object SeriesTypeServiceActor {
  def props(dbProps: DbProps): Props = Props(new SeriesTypeServiceActor(dbProps))
}

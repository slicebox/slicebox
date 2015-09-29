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

package se.nimsa.sbx.scp

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import ScpProtocol.ScpData

class ScpDAO(val driver: JdbcProfile) {
  import driver.simple._

  class ScpDataTable(tag: Tag) extends Table[ScpData](tag, "ScpData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def port = column[Int]("port")
    def * = (id, name, aeTitle, port) <> (ScpData.tupled, ScpData.unapply)
  }

  val scpDataQuery = TableQuery[ScpDataTable]
  
  def create(implicit session: Session) =
    if (MTable.getTables("ScpData").list.isEmpty) scpDataQuery.ddl.create
  
  
  def insert(scpData: ScpData)(implicit session: Session): ScpData = {
    val generatedId = (scpDataQuery returning scpDataQuery.map(_.id)) += scpData
    scpData.copy(id = generatedId)
  }

  def deleteScpDataWithId(scpDataId: Long)(implicit session: Session): Int = {
    scpDataQuery
      .filter(_.id === scpDataId)
      .delete
  }
  
  def scpDataForId(id: Long)(implicit session: Session): Option[ScpData] =
    scpDataQuery.filter(_.id === id).firstOption
  
  def scpDataForName(name: String)(implicit session: Session): Option[ScpData] =
    scpDataQuery.filter(_.name === name).firstOption
  
  def scpDataForPort(port: Int)(implicit session: Session): Option[ScpData] =
    scpDataQuery.filter(_.port === port).firstOption
  
  def allScpDatas(implicit session: Session): List[ScpData] = scpDataQuery.list
}

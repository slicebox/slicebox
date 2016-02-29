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

package se.nimsa.sbx.scu

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import ScuProtocol.ScuData

class ScuDAO(val driver: JdbcProfile) {
  import driver.simple._

  class ScuDataTable(tag: Tag) extends Table[ScuData](tag, "ScuData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def host = column[String]("host")
    def port = column[Int]("port")
    def * = (id, name, aeTitle, host, port) <> (ScuData.tupled, ScuData.unapply)
  }

  val scuDataQuery = TableQuery[ScuDataTable]
  
  def create(implicit session: Session) =
    if (MTable.getTables("ScuData").list.isEmpty) scuDataQuery.ddl.create
  
  
  def insert(scuData: ScuData)(implicit session: Session): ScuData = {
    val generatedId = (scuDataQuery returning scuDataQuery.map(_.id)) += scuData
    scuData.copy(id = generatedId)
  }

  def deleteScuDataWithId(scuDataId: Long)(implicit session: Session): Int = {
    scuDataQuery
      .filter(_.id === scuDataId)
      .delete
  }
  
  def scuDataForId(id: Long)(implicit session: Session): Option[ScuData] =
    scuDataQuery.filter(_.id === id).firstOption
  
  def scuDataForName(name: String)(implicit session: Session): Option[ScuData] =
    scuDataQuery.filter(_.name === name).firstOption
  
  def scuDataForHostAndPort(host: String, port: Int)(implicit session: Session): Option[ScuData] =
    scuDataQuery.filter(_.host === host).filter(_.port === port).firstOption
  
  def allScuDatas(implicit session: Session): List[ScuData] = scuDataQuery.list
}

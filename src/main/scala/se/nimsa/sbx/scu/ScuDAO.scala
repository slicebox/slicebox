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

package se.nimsa.sbx.scu

import se.nimsa.sbx.scu.ScuProtocol.ScuData
import se.nimsa.sbx.util.DbUtil._
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class ScuDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  class ScuDataTable(tag: Tag) extends Table[ScuData](tag, ScuDataTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def host = column[String]("host")
    def port = column[Int]("port")
    def * = (id, name, aeTitle, host, port) <> (ScuData.tupled, ScuData.unapply)
  }

  object ScuDataTable {
    val name = "ScuData"
  }

  val scuDatas = TableQuery[ScuDataTable]

  def create() = createTables(dbConf, (ScuDataTable.name, scuDatas))

  def drop() = db.run {
    scuDatas.schema.drop
  }

  def clear() = db.run {
    scuDatas.delete
  }

  def insert(scuData: ScuData): Future[ScuData] = db.run {
    scuDatas returning scuDatas.map(_.id) += scuData
  }.map(generatedId => scuData.copy(id = generatedId))

  def deleteScuDataWithId(scuDataId: Long): Future[Int] = db.run {
    scuDatas
      .filter(_.id === scuDataId)
      .delete
  }

  def scuDataForId(id: Long): Future[Option[ScuData]] = db.run {
    scuDatas.filter(_.id === id).result.headOption
  }

  def scuDataForName(name: String): Future[Option[ScuData]] = db.run {
    scuDatas.filter(_.name === name).result.headOption
  }

  def scuDataForHostAndPort(host: String, port: Int): Future[Option[ScuData]] = db.run {
    scuDatas.filter(_.host === host).filter(_.port === port).result.headOption
  }

  def listScuDatas(startIndex: Long, count: Long): Future[Seq[ScuData]] = db.run {
    scuDatas
      .drop(startIndex)
      .take(count)
      .result
  }
}

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

package se.nimsa.sbx.directory

import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile

import scala.concurrent.Future

class DirectoryWatchDAO(val dbConf: DatabaseConfig[JdbcProfile]) {

  import dbConf.driver.api._

  val db = dbConf.db

  class DirectoryWatchDataTable(tag: Tag) extends Table[WatchedDirectory](tag, DirectoryWatchDataTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def path = column[String]("path")
    def * = (id, name, path) <> (WatchedDirectory.tupled, WatchedDirectory.unapply)
  }

  object DirectoryWatchDataTable {
    val name = "DirectoryWatchData"
  }

  val watchedDirectoriesQuery = TableQuery[DirectoryWatchDataTable]

  def create() = createTables(dbConf, Seq((DirectoryWatchDataTable.name, watchedDirectoriesQuery)))

  def drop() = db.run(watchedDirectoriesQuery.schema.drop)

  def clear() = db.run(watchedDirectoriesQuery.delete)

  def insert(watchedDirectory: WatchedDirectory): Future[WatchedDirectory] = db.run {
    (watchedDirectoriesQuery returning watchedDirectoriesQuery.map(_.id) += watchedDirectory)
      .map(generatedId => watchedDirectory.copy(id = generatedId))
  }

  def deleteWatchedDirectoryWithId(watchedDirectoryId: Long): Future[Unit] = db.run {
    watchedDirectoriesQuery
      .filter(_.id === watchedDirectoryId).delete.map(_ => {})
  }

  def listWatchedDirectories(startIndex: Long, count: Long): Future[Seq[WatchedDirectory]] = db.run {
    watchedDirectoriesQuery
      .drop(startIndex)
      .take(count)
      .result
  }

  def watchedDirectoryForId(id: Long): Future[Option[WatchedDirectory]] = db.run {
    watchedDirectoriesQuery.filter(_.id === id).result.headOption
  }

  def watchedDirectoryForPath(path: String): Future[Option[WatchedDirectory]] = db.run {
    watchedDirectoriesQuery.filter(_.path === path).result.headOption
  }

}

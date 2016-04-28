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

import scala.slick.driver.JdbcProfile
import java.nio.file.Path
import java.nio.file.Paths
import scala.slick.jdbc.meta.MTable
import DirectoryWatchProtocol._

class DirectoryWatchDAO(val driver: JdbcProfile) {
  import driver.simple._

  class DirectoryWatchDataTable(tag: Tag) extends Table[WatchedDirectory](tag, "DirectoryWatchData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def path = column[String]("path")
    def * = (id, name, path) <> (WatchedDirectory.tupled, WatchedDirectory.unapply)
  }

  val watchedDirectoriesQuery = TableQuery[DirectoryWatchDataTable]

  def create(implicit session: Session) =
    if (MTable.getTables("DirectoryWatchData").list.isEmpty) watchedDirectoriesQuery.ddl.create
  
  def insert(watchedDirectory: WatchedDirectory)(implicit session: Session): WatchedDirectory = {
    val generatedId = (watchedDirectoriesQuery returning watchedDirectoriesQuery.map(_.id)) += watchedDirectory
    watchedDirectory.copy(id = generatedId)
  }

  def deleteWatchedDirectoryWithId(watchedDirectoryId: Long)(implicit session: Session): Int = {
    watchedDirectoriesQuery
      .filter(_.id === watchedDirectoryId)
      .delete
  }
  
  def listWatchedDirectories(startIndex: Long, count: Long)(implicit session: Session): List[WatchedDirectory] =
    watchedDirectoriesQuery
      .drop(startIndex)
      .take(count)
      .list
    
  def watchedDirectoryForId(id: Long)(implicit session: Session): Option[WatchedDirectory] =
    watchedDirectoriesQuery.filter(_.id === id).firstOption
    
  def watchedDirectoryForPath(path: String)(implicit session: Session): Option[WatchedDirectory] =
    watchedDirectoriesQuery.filter(_.path === path).firstOption
    
}

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

package se.nimsa.sbx.app

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import se.nimsa.sbx.app.UserProtocol._

class UserDAO(val driver: JdbcProfile) {
  import driver.simple._

  val toUser = (id: Long, user: String, role: String, password: String) => ApiUser(id, user, UserRole.withName(role), Some(password))
  val fromUser = (user: ApiUser) => Option((user.id, user.user, user.role.toString, user.hashedPassword.getOrElse("")))

  class UserTable(tag: Tag) extends Table[ApiUser](tag, "User") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def user = column[String]("user")
    def role = column[String]("role")
    def password = column[String]("password")
    def idxUniqueUser = index("idx_unique_user", user, unique = true)
    def * = (id, user, role, password) <> (toUser.tupled, fromUser)
  }

  val userQuery = TableQuery[UserTable]

  def create(implicit session: Session) =
    if (MTable.getTables("User").list.isEmpty) {
      userQuery.ddl.create
    }
  
  def drop(implicit session: Session): Unit =
    (userQuery.ddl).drop

  def insert(user: ApiUser)(implicit session: Session) = {
    val generatedId = (userQuery returning userQuery.map(_.id)) += user
    user.copy(id = generatedId)
  }
  
  def userById(userId: Long)(implicit session: Session): Option[ApiUser] =
    userQuery.filter(_.id === userId).list.headOption
    
  def userByName(user: String)(implicit session: Session): Option[ApiUser] =
    userQuery.filter(_.user === user).list.headOption
    
  def removeUser(userId: Long)(implicit session: Session): Unit =
    userQuery.filter(_.id === userId).delete

//  def findUserByName(name: String)(implicit session: Session) =
//    userQuery.filter(_.user === name).firstOption.map(row => row.user)
//
//  def findKeyByName(name: String)(implicit session: Session) =
//    userQuery.filter(_.user === name).firstOption.map(row => row.key)
//
//  def removeByName(name: String)(implicit session: Session) =
//    userQuery.filter(_.user === name).delete
//
//  def listUserNames(implicit session: Session): List[String] =
//    userQuery.list.map(row => row.user.user)

  def listUsers(implicit session: Session): List[ApiUser] =
    userQuery.list

}

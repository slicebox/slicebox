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

package se.nimsa.sbx.user

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import UserProtocol._

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

  class SessionTable(tag: Tag) extends Table[ApiSession](tag, "ApiSession") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[Long]("userid")
    def token = column[String]("token")
    def ip = column[String]("ip")
    def userAgent = column[String]("useragent")
    def lastUpdated = column[Long]("lastupdated")
    def fkUser = foreignKey("fk_user", userId, userQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, userId, token, ip, userAgent, lastUpdated) <> (ApiSession.tupled, ApiSession.unapply)
  }

  val sessionQuery = TableQuery[SessionTable]

  def create(implicit session: Session) = {
    if (MTable.getTables("User").list.isEmpty) userQuery.ddl.create
    if (MTable.getTables("ApiSession").list.isEmpty) sessionQuery.ddl.create
  }
  def drop(implicit session: Session): Unit =
    (userQuery.ddl ++ sessionQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    userQuery.delete
    sessionQuery.delete
  }

  def insert(user: ApiUser)(implicit session: Session): ApiUser = {
    val generatedId = (userQuery returning userQuery.map(_.id)) += user
    user.copy(id = generatedId)
  }

  def userById(userId: Long)(implicit session: Session): Option[ApiUser] =
    userQuery.filter(_.id === userId).firstOption

  def userByName(user: String)(implicit session: Session): Option[ApiUser] =
    userQuery.filter(_.user === user).firstOption

  def userSessionsByToken(token: String)(implicit session: Session): List[(ApiUser, ApiSession)] =
    (for {
      users <- userQuery
      sessions <- sessionQuery if sessions.userId === users.id
    } yield (users, sessions))
      .filter(_._2.token === token)
      .list

  def userSessionByTokenIpAndUserAgent(token: String, ip: String, userAgent: String)(implicit session: Session): Option[(ApiUser, ApiSession)] =
    (for {
      users <- userQuery
      sessions <- sessionQuery if sessions.userId === users.id
    } yield (users, sessions))
      .filter(_._2.token === token)
      .filter(_._2.ip === ip)
      .filter(_._2.userAgent === userAgent)
      .firstOption

  def deleteUserByUserId(userId: Long)(implicit session: Session): Unit =
    userQuery.filter(_.id === userId).delete

  def listUsers(implicit session: Session): List[ApiUser] =
    userQuery.list

  def listSessions(implicit session: Session): List[ApiSession] =
    sessionQuery.list

  def userSessionByUserIdIpAndUserAgent(userId: Long, ip: String, userAgent: String)(implicit session: Session): Option[ApiSession] =
    (for {
      users <- userQuery
      sessions <- sessionQuery if sessions.userId === users.id
    } yield (users, sessions))
      .filter(_._1.id === userId)
      .filter(_._2.ip === ip)
      .filter(_._2.userAgent === userAgent)
      .map(_._2)
      .firstOption

  def insertSession(apiSession: ApiSession)(implicit session: Session) = {
    val generatedId = (sessionQuery returning sessionQuery.map(_.id)) += apiSession
    apiSession.copy(id = generatedId)
  }

  def updateSession(apiSession: ApiSession)(implicit session: Session) =
    sessionQuery.filter(_.id === apiSession.id).update(apiSession)

  def deleteSessionByUserIdIpAndUserAgent(userId: Long, ip: String, userAgent: String)(implicit session: Session) =
    sessionQuery
      .filter(_.userId === userId)
      .filter(_.ip === ip)
      .filter(_.userAgent === userAgent)
      .delete

}

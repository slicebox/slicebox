/*
 * Copyright 2017 Lars Edenbrandt
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

import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.util.DbUtil._
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class UserDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  val toUser = (id: Long, user: String, role: String, password: String) => ApiUser(id, user, UserRole.withName(role), Some(password))
  val fromUser = (user: ApiUser) => Option((user.id, user.user, user.role.toString(), user.hashedPassword.get))

  class UserTable(tag: Tag) extends Table[ApiUser](tag, UserTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def user = column[String]("user")
    def role = column[String]("role")
    def password = column[String]("password")
    def idxUniqueUser = index("idx_unique_user", user, unique = true)
    def * = (id, user, role, password) <>(toUser.tupled, fromUser)
  }
  object UserTable {
    val name = "User"
  }
  val users = TableQuery[UserTable]

  class SessionTable(tag: Tag) extends Table[ApiSession](tag, SessionTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[Long]("userid")
    def token = column[String]("token")
    def ip = column[String]("ip")
    def userAgent = column[String]("useragent")
    def updated = column[Long]("updated")
    def fkUser = foreignKey("fk_user", userId, users)(_.id, onDelete = ForeignKeyAction.Cascade)
    def idxUniqueSession = index("idx_unique_session", (token, ip, userAgent), unique = true)
    def * = (id, userId, token, ip, userAgent, updated) <>(ApiSession.tupled, ApiSession.unapply)
  }
  object SessionTable {
    val name = "ApiSession"
  }
  val sessions = TableQuery[SessionTable]

  def create() = createTables(dbConf, (UserTable.name, users), (SessionTable.name, sessions))

  def drop() = db.run {
    (users.schema ++ sessions.schema).drop
  }

  def clear() = db.run {
    DBIO.seq(users.delete, sessions.delete)
  }

  def insert(user: ApiUser): Future[ApiUser] = db.run {
    users returning users.map(_.id) += user
  }.map(generatedId => user.copy(id = generatedId))

  def userById(userId: Long): Future[Option[ApiUser]] = db.run {
    users.filter(_.id === userId).result.headOption
  }

  def userByName(user: String): Future[Option[ApiUser]] = db.run {
    users.filter(_.user === user).result.headOption
  }

  def userSessionsByToken(token: String): Future[Seq[(ApiUser, ApiSession)]] = db.run {
    (for {
      users <- users
      sessions <- sessions if sessions.userId === users.id
    } yield (users, sessions))
      .filter(_._2.token === token)
      .result
  }

  def userSessionByTokenIpAndUserAgent(token: String, ip: String, userAgent: String): Future[Option[(ApiUser, ApiSession)]] = db.run {
    (for {
      users <- users
      sessions <- sessions if sessions.userId === users.id
    } yield (users, sessions))
      .filter(_._2.token === token)
      .filter(_._2.ip === ip)
      .filter(_._2.userAgent === userAgent)
      .result.headOption
  }

  def deleteUserByUserId(userId: Long): Future[Int] = db.run {
    users.filter(_.id === userId).delete
  }

  def listUsers(startIndex: Long, count: Long): Future[Seq[ApiUser]] = db.run {
    users
      .drop(startIndex)
      .take(count)
      .result
  }

  def listSessions: Future[Seq[ApiSession]] = db.run {
    sessions.result
  }

  def userSessionByUserIdIpAndUserAgent(userId: Long, ip: String, userAgent: String): Future[Option[ApiSession]] = db.run {
    (for {
      users <- users
      sessions <- sessions if sessions.userId === users.id
    } yield (users, sessions))
      .filter(_._1.id === userId)
      .filter(_._2.ip === ip)
      .filter(_._2.userAgent === userAgent)
      .map(_._2)
      .result.headOption
  }

  def insertSession(apiSession: ApiSession) = db.run {
    sessions returning sessions.map(_.id) += apiSession
  }.map(generatedId => apiSession.copy(id = generatedId))

  def updateSession(apiSession: ApiSession) = db.run {
    sessions.filter(_.id === apiSession.id).update(apiSession)
  }

  def deleteSessionByUserIdIpAndUserAgent(userId: Long, ip: String, userAgent: String): Future[Int]  = db.run {
    sessions
      .filter(_.userId === userId)
      .filter(_.ip === ip)
      .filter(_.userAgent === userAgent)
      .delete
  }

  def deleteSessionById(sessionId: Long): Future[Int]  = db.run {
    sessions.filter(_.id === sessionId).delete
  }
}

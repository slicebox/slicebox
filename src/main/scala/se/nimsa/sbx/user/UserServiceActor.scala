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

package se.nimsa.sbx.user

import UserProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.util.ExceptionCatching
import scala.collection.mutable.Map
import scala.concurrent.duration.DurationInt
import java.util.UUID
import akka.actor.actorRef2Scala
import se.nimsa.sbx.app.DbProps
import UserServiceActor._

class UserServiceActor(dbProps: DbProps, superUser: String, superPassword: String, sessionTimeout: Long) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new UserDAO(dbProps.driver)

  addSuperUser()

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val expiredSessionCleaner = system.scheduler.schedule(1.hour, 1.hour) {
    self ! RemoveExpiredSessions
  }

  log.info("User service started")

  def receive = LoggingReceive {

    case msg: UserRequest =>
      catchAndReport {

        msg match {

          case Login(userPass, authKey) =>
            userByName(userPass.user) match {
              case Some(user) if user.passwordMatches(userPass.pass) =>
                val result = authKey.ip.flatMap(ip =>
                  authKey.userAgent.map(userAgent => {
                    val session = createOrUpdateSession(user, ip, userAgent)
                    LoggedIn(user, session)
                  }))
                  .getOrElse(LoginFailed)
                sender ! result
              case _ =>
                sender ! LoginFailed
            }

          case Logout(apiUser, authKey) =>
            deleteSession(apiUser, authKey)
            sender ! LoggedOut

          case AddUser(apiUser) =>
            if (apiUser.role == UserRole.SUPERUSER)
              throw new IllegalArgumentException("Superusers may not be added")
            sender ! UserAdded(getOrCreateUser(apiUser))

          case GetUserByName(user) =>
            sender ! userByName(user)

          case GetAndRefreshUserByAuthKey(authKey) =>
            val optionalUser = getAndRefreshUser(authKey)
            sender ! optionalUser

          case GetUsers =>
            val users = listUsers
            sender ! Users(users)

          case DeleteUser(userId) =>
            deleteUser(userId)
            sender ! UserDeleted(userId)

        }
      }

    case RemoveExpiredSessions => removeExpiredSessions()

  }

  def addSuperUser(): Unit =
    db.withSession { implicit session =>
      val superUsers = dao.listUsers.filter(_.role == UserRole.SUPERUSER)
      if (superUsers.isEmpty || superUsers(0).user != superUser || !superUsers(0).passwordMatches(superPassword)) {
        superUsers.foreach(superUser => dao.deleteUserByUserId(superUser.id))
        dao.insert(ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword))
      }
    }

  def userByName(name: String): Option[ApiUser] =
    db.withSession { implicit session =>
      dao.userByName(name)
    }

  def createOrUpdateSession(user: ApiUser, ip: String, userAgent: String): ApiSession =
    db.withSession { implicit session =>
      dao.userSessionByUserIdIpAndUserAgent(user.id, ip, userAgent)
        .map(apiSession => {
          val updatedSession = apiSession.copy(updated = currentTime)
          dao.updateSession(updatedSession)
          updatedSession
        })
        .getOrElse(
          dao.insertSession(ApiSession(-1, user.id, newSessionToken, ip, userAgent, currentTime)))
    }

  def deleteSession(user: ApiUser, authKey: AuthKey): Option[Int] =
    db.withSession { implicit session =>
      authKey.ip.flatMap(ip =>
        authKey.userAgent.map(userAgent =>
          dao.deleteSessionByUserIdIpAndUserAgent(user.id, ip, userAgent)))
    }

  def getAndRefreshUser(authKey: AuthKey): Option[ApiUser] =
    db.withSession { implicit session =>
      val validUserAndSession =
        authKey.token.flatMap(token =>
          authKey.ip.flatMap(ip =>
            authKey.userAgent.flatMap(userAgent =>
              dao.userSessionByTokenIpAndUserAgent(token, ip, userAgent))))
          .filter {
            case (user, apiSession) =>
              apiSession.updated > (currentTime - sessionTimeout)
          }

      validUserAndSession.foreach {
        case (user, apiSession) =>
          dao.updateSession(apiSession.copy(updated = currentTime))
      }

      validUserAndSession.map(_._1)
    }

  def getOrCreateUser(user: ApiUser): ApiUser =
    db.withSession { implicit session =>
      dao.userByName(user.user).getOrElse(dao.insert(user))
    }

  def deleteUser(userId: Long): Unit =
    db.withSession { implicit session =>
      dao.userById(userId)
        .filter(_.role == UserRole.SUPERUSER)
        .foreach(superuser => throw new IllegalArgumentException("Superuser may not be deleted"))

      dao.deleteUserByUserId(userId)
      sender ! UserDeleted(userId)
    }

  def removeExpiredSessions(): Unit =
    db.withSession { implicit session =>
      dao.listSessions
        .filter(_.updated < currentTime - sessionTimeout)
        .foreach(expiredSession => dao.deleteSessionById(expiredSession.id))
    }

  def listUsers: List[ApiUser] =
    db.withSession { implicit session =>
      dao.listUsers
    }
}

object UserServiceActor {
  def props(dbProps: DbProps, superUser: String, superPassword: String, sessionTimeout: Long): Props = Props(new UserServiceActor(dbProps, superUser, superPassword, sessionTimeout))

  def newSessionToken = UUID.randomUUID.toString

  def currentTime = System.currentTimeMillis
}

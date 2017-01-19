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

import java.util.UUID

import akka.actor.{Actor, Props, actorRef2Scala}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.user.UserServiceActor._
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

import scala.concurrent.duration.DurationInt

class UserServiceActor(userDao: UserDAO, superUser: String, superPassword: String, sessionTimeout: Long)(implicit timeout: Timeout) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

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

          case GetUsers(startIndex, count) =>
            val users = listUsers(startIndex, count)
            sender ! Users(users)

          case DeleteUser(userId) =>
            deleteUser(userId)
            sender ! UserDeleted(userId)

        }
      }

    case RemoveExpiredSessions => removeExpiredSessions()

  }

  def addSuperUser(): Unit = {
    val superUsers = await(userDao.listUsers(0, 1000000)).filter(_.role == UserRole.SUPERUSER)
    if (superUsers.isEmpty || superUsers.head.user != superUser || !superUsers.head.passwordMatches(superPassword)) {
      superUsers.foreach(superUser => userDao.deleteUserByUserId(superUser.id))
      await(userDao.insert(ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword)))
    }
  }

  def userByName(name: String): Option[ApiUser] =
    await(userDao.userByName(name))

  def createOrUpdateSession(user: ApiUser, ip: String, userAgent: String): ApiSession = {
    await(userDao.userSessionByUserIdIpAndUserAgent(user.id, ip, userAgent))
      .map(apiSession => {
        val updatedSession = apiSession.copy(updated = currentTime)
        await(userDao.updateSession(updatedSession))
        updatedSession
      })
      .getOrElse(
        await(userDao.insertSession(ApiSession(-1, user.id, newSessionToken, ip, userAgent, currentTime))))
  }

  def deleteSession(user: ApiUser, authKey: AuthKey): Option[Int] =
    authKey.ip.flatMap(ip =>
      authKey.userAgent.map(userAgent =>
        await(userDao.deleteSessionByUserIdIpAndUserAgent(user.id, ip, userAgent))))

  def getAndRefreshUser(authKey: AuthKey): Option[ApiUser] = {
    val validUserAndSession =
      authKey.token.flatMap(token =>
        authKey.ip.flatMap(ip =>
          authKey.userAgent.flatMap(userAgent =>
            await(userDao.userSessionByTokenIpAndUserAgent(token, ip, userAgent)))))
        .filter {
          case (_, apiSession) =>
            apiSession.updated > (currentTime - sessionTimeout)
        }

    validUserAndSession.foreach {
      case (_, apiSession) =>
        await(userDao.updateSession(apiSession.copy(updated = currentTime)))
    }

    validUserAndSession.map(_._1)
  }

  def getOrCreateUser(user: ApiUser): ApiUser =
    await(userDao.userByName(user.user)).getOrElse(await(userDao.insert(user)))

  def deleteUser(userId: Long): Unit = {
    await(userDao.userById(userId))
      .filter(_.role == UserRole.SUPERUSER)
      .foreach(_ => throw new IllegalArgumentException("Superuser may not be deleted"))

    await(userDao.deleteUserByUserId(userId))
    sender ! UserDeleted(userId)
  }

  def removeExpiredSessions(): Unit =
    await(userDao.listSessions)
      .filter(_.updated < currentTime - sessionTimeout)
      .foreach(expiredSession => userDao.deleteSessionById(expiredSession.id))

  def listUsers(startIndex: Long, count: Long): Seq[ApiUser] =
    await(userDao.listUsers(startIndex, count))
}

object UserServiceActor {
  def props(dao: UserDAO, superUser: String, superPassword: String, sessionTimeout: Long, timeout: Timeout): Props = Props(new UserServiceActor(dao, superUser, superPassword, sessionTimeout)(timeout))

  def newSessionToken = UUID.randomUUID.toString

  def currentTime = System.currentTimeMillis
}

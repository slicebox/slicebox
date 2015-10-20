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

class UserServiceActor(dbProps: DbProps, superUser: String, superPassword: String, sessionTimeout: Long) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new UserDAO(dbProps.driver)

  addSuperUser()

  implicit val system = context.system
  implicit val ec = context.dispatcher

  log.info("User service started")

  def receive = LoggingReceive {

    case msg: UserRequest =>
      catchAndReport {

        msg match {

          case AddUser(apiUser) =>
            if (apiUser.role == UserRole.SUPERUSER)
              throw new IllegalArgumentException("Superusers may not be added")

            db.withSession { implicit session =>
              sender ! UserAdded(dao.userByName(apiUser.user).getOrElse(dao.insert(apiUser)))
            }

          case GetUser(userId) =>
            db.withSession { implicit session =>
              sender ! dao.userById(userId)
            }

          case GetUserByName(user) =>
            db.withSession { implicit session =>
              sender ! dao.userByName(user)
            }

          case GetUserByToken(token) =>
            db.withSession { implicit session =>
              sender ! dao.userSessionByTokenAndIp(token.token, token.ip)
                .filter {
                  case (user, session) =>
                    session.lastUpdated > (System.currentTimeMillis() - sessionTimeout)
                }.map(_._1)
            }

          case GetUsers =>
            db.withSession { implicit session =>
              sender ! Users(dao.listUsers)
            }

          case DeleteUser(userId) =>
            db.withSession { implicit session =>
              dao.userById(userId)
                .filter(_.role == UserRole.SUPERUSER)
                .foreach(superuser => throw new IllegalArgumentException("Superuser may not be deleted"))

              dao.removeUser(userId)
              sender ! UserDeleted(userId)
            }

        }
      }

  }

  def addSuperUser() =
    db.withSession { implicit session =>
      val superUsers = dao.listUsers.filter(_.role == UserRole.SUPERUSER)
      if (superUsers.isEmpty || superUsers(0).user != superUser || !superUsers(0).passwordMatches(superPassword)) {
        superUsers.foreach(superUser => dao.removeUser(superUser.id))
        dao.insert(ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword))
      }
    }

}

object UserServiceActor {
  def props(dbProps: DbProps, superUser: String, superPassword: String, sessionTimeout: Long): Props = Props(new UserServiceActor(dbProps, superUser, superPassword, sessionTimeout))
}

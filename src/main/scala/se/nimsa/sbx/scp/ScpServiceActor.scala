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

package se.nimsa.sbx.scp

import java.nio.file.Path
import java.util.concurrent.Executors
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import ScpProtocol._

class ScpServiceActor(dbProps: DbProps) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScpDAO(dbProps.driver)

  val executor = Executors.newCachedThreadPool()

  setupScps()

  override def postStop() {
    executor.shutdown()
  }

  log.info("SCP service started")

  def receive = LoggingReceive {

    case msg: ScpRequest =>

      catchAndReport {

        msg match {

          case AddScp(scp: ScpData) =>
            scpForName(scp.name) match {
              case Some(scpData) =>

                sender ! scpData

              case None =>

                val trimmedAeTitle = scp.aeTitle.trim

                if (trimmedAeTitle.isEmpty)
                  throw new IllegalArgumentException("Ae title must not be empty")

                if (trimmedAeTitle.length > 16)
                  throw new IllegalArgumentException("Ae title must not exceed 16 characters, excluding leading and trailing epaces.")

                if (scp.port < 0 || scp.port > 65535)
                  throw new IllegalArgumentException("Port must be a value between 0 and 65535")

                if (scpForPort(scp.port).isDefined)
                  throw new IllegalArgumentException(s"Port ${scp.port} is already in use")

                val scpData = addScp(scp)

                context.child(scpData.id.toString).getOrElse(
                  context.actorOf(ScpActor.props(scpData, executor), scpData.id.toString))

                sender ! scpData

            }

          case RemoveScp(scpDataId) =>
            scpForId(scpDataId).foreach(scpData => deleteScpWithId(scpDataId))
            context.child(scpDataId.toString).foreach(_ ! PoisonPill)
            sender ! ScpRemoved(scpDataId)

          case GetScps =>
            val scps = getScps()
            sender ! Scps(scps)

          case GetScpById(id) =>
            db.withSession { implicit session =>
              sender ! dao.scpDataForId(id)
            }
        }
      }

  }

  def addScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.insert(scpData)
    }

  def scpForId(id: Long) =
    db.withSession { implicit session =>
      dao.scpDataForId(id)
    }

  def scpForName(name: String) =
    db.withSession { implicit session =>
      dao.scpDataForName(name)
    }

  def scpForPort(port: Int) =
    db.withSession { implicit session =>
      dao.scpDataForPort(port)
    }

  def deleteScpWithId(id: Long) =
    db.withSession { implicit session =>
      dao.deleteScpDataWithId(id)
    }

  def getScps() =
    db.withSession { implicit session =>
      dao.allScpDatas
    }

  def setupScps() = {
    val scps =
      db.withSession { implicit session =>
        dao.allScpDatas
      }
    scps foreach (scpData => context.actorOf(ScpActor.props(scpData, executor), scpData.id.toString))
  }

}

object ScpServiceActor {
  def props(dbProps: DbProps): Props = Props(new ScpServiceActor(dbProps))
}

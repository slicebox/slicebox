/*
 * Copyright 2015 Karl Sjöstrand
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

package se.nimsa.sbx.dicom.scp

import java.nio.file.Path
import java.util.concurrent.Executors
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomDispatchActor
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.util.ExceptionCatching

class ScpServiceActor(dbProps: DbProps) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScpDataDAO(dbProps.driver)

  val executor = Executors.newCachedThreadPool()

  setupDb()
  setupScps()

  override def postStop() {
    executor.shutdown()
  }

  def receive = LoggingReceive {

    case msg: ScpRequest =>

      catchAndReport {

        msg match {

          case AddScp(name, aeTitle, port) =>
            scpForName(name) match {
              case Some(scpData) =>

                sender ! scpData

              case None =>
                val scpData = ScpData(-1, name, aeTitle, port)

                if (port < 0 || port > 65535)
                  throw new IllegalArgumentException("Port must be a value between 0 and 65535")

                if (scpForPort(port).isDefined)
                  throw new IllegalArgumentException(s"Port $port is already in use")

                addScp(scpData)

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

        }
      }

    case msg: DatasetReceivedByScp =>
      context.parent ! msg

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

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def setupScps() =
    db.withTransaction { implicit session =>
      val scps = dao.allScpDatas
      scps foreach (scpData => context.actorOf(ScpActor.props(scpData, executor), scpData.id.toString))
    }

}

object ScpServiceActor {
  def props(dbProps: DbProps): Props = Props(new ScpServiceActor(dbProps))
}

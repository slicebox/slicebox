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

package se.nimsa.sbx.dicom.scu

import java.nio.file.Path
import scala.language.postfixOps
import scala.concurrent.Future
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.pipe
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomMetaDataDAO
import se.nimsa.sbx.dicom.DicomDispatchActor
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.log.SbxLog

class ScuServiceActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScuDataDAO(dbProps.driver)
  val metaDataDao = new DicomMetaDataDAO(dbProps.driver)

  import context.system
  implicit val ec = context.dispatcher

  setupDb()

  log.info("SCU service started")    
  
  def receive = LoggingReceive {

    case msg: ScuRequest =>

      catchAndReport {

        msg match {

          case AddScu(name, aeTitle, host, port) =>
            scuForName(name) match {
              case Some(scuData) =>

                sender ! scuData

              case None =>
                val scuData = ScuData(-1, name, aeTitle, host, port)

                if (port < 0 || port > 65535)
                  throw new IllegalArgumentException("Port must be a value between 0 and 65535")

                if (scuForHostAndPort(host, port).isDefined)
                  throw new IllegalArgumentException(s"Host $host and port $port is already in use")

                addScu(scuData)

                sender ! scuData

            }

          case RemoveScu(scuDataId) =>
            scuForId(scuDataId).foreach(scuData => deleteScuWithId(scuDataId))
            sender ! ScuRemoved(scuDataId)

          case GetScus =>
            val scus = getScus()
            sender ! Scus(scus)

          case SendSeriesToScp(seriesId, scuId) =>
            scuForId(scuId).map(scu => {
              val imageFiles = imageFilesForSeries(seriesId)
              SbxLog.info("SCU", s"Sending ${imageFiles.length} images using SCU ${scu.name}")
              Future {
                Scu.sendFiles(scu, imageFiles.map(imageFile => storage.resolve(imageFile.fileName.value)))
              }.map(r => ImagesSentToScp(scuId, imageFiles.map(_.id))).pipeTo(sender)
            }).orElse(throw new IllegalArgumentException(s"SCU with id $scuId not found"))
            // TODO handle errors when sending (do not return 500)
        }
      }

  }

  def addScu(scuData: ScuData) =
    db.withSession { implicit session =>
      dao.insert(scuData)
    }

  def scuForId(id: Long) =
    db.withSession { implicit session =>
      dao.scuDataForId(id)
    }

  def scuForName(name: String) =
    db.withSession { implicit session =>
      dao.scuDataForName(name)
    }

  def scuForHostAndPort(host: String, port: Int) =
    db.withSession { implicit session =>
      dao.scuDataForHostAndPort(host, port)
    }

  def deleteScuWithId(id: Long) =
    db.withSession { implicit session =>
      dao.deleteScuDataWithId(id)
    }

  def getScus() =
    db.withSession { implicit session =>
      dao.allScuDatas
    }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def imageFilesForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      metaDataDao.imageFilesForSeries(seriesId)
    }

}

object ScuServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new ScuServiceActor(dbProps, storage))
}

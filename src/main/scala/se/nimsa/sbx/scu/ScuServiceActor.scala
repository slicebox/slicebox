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

package se.nimsa.sbx.scu

import java.net.{ConnectException, NoRouteToHostException, UnknownHostException}

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.lang.{BadGatewayException, NotFoundException}
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.Future
import scala.language.postfixOps

class ScuServiceActor(dbProps: DbProps)(implicit timeout: Timeout) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScuDAO(dbProps.driver)

  import context.system

  implicit val ec = context.dispatcher

  val metaDataService = context.actorSelection("../MetaDataService")
  val storageService = context.actorSelection("../StorageService")

  val datasetProvider = new DatasetProvider {
    override def getDataset(imageId: Long, withPixelData: Boolean): Future[Option[Attributes]] = {
      metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
        imageMaybe.map { image =>
          storageService.ask(GetDataset(image, withPixelData, useBulkDataURI = withPixelData)).mapTo[Option[Attributes]]
        }.unwrap
      }
    }
  }

  log.info("SCU service started")

  def receive = LoggingReceive {

    case msg: ScuRequest =>

      catchAndReport {

        msg match {

          case AddScu(scu) =>
            scuForName(scu.name) match {
              case Some(scuData) =>

                sender ! scuData

              case None =>

                val trimmedAeTitle = scu.aeTitle.trim

                if (trimmedAeTitle.isEmpty)
                  throw new IllegalArgumentException("Ae title must not be empty")

                if (trimmedAeTitle.length > 16)
                  throw new IllegalArgumentException("Ae title must not exceed 16 characters, excluding leading and trailing epaces.")

                if (scu.port < 0 || scu.port > 65535)
                  throw new IllegalArgumentException("Port must be a value between 0 and 65535")

                if (scuForHostAndPort(scu.host, scu.port).isDefined)
                  throw new IllegalArgumentException(s"Host ${scu.host} and port ${scu.port} is already in use")

                val scuData = addScu(scu)

                sender ! scuData

            }

          case RemoveScu(scuDataId) =>
            scuForId(scuDataId).foreach(scuData => deleteScuWithId(scuDataId))
            sender ! ScuRemoved(scuDataId)

          case GetScus(startIndex, count) =>
            sender ! Scus(getScus(startIndex, count))

          case SendImagesToScp(imageIds, scuId) =>
            scuForId(scuId).map(scu => {
              SbxLog.info("SCU", s"Sending ${imageIds.length} images using SCU ${scu.name}")
              Scu.sendFiles(scu, datasetProvider, imageIds)
                .map(r => {
                  SbxLog.info("SCU", s"Finished sending ${imageIds.length} images using SCU ${scu.name}")
                  val nNotFound = imageIds.length - r.length
                  if (nNotFound > 0)
                    SbxLog.warn("SCU", s"$nNotFound of ${imageIds.length} images could not be found and were not sent")
                  context.system.eventStream.publish(ImagesSent(Destination(DestinationType.SCU, scu.name, scu.id), imageIds))
                  ImagesSentToScp(scuId, imageIds)
                })
                .recover {
                  case e: UnknownHostException =>
                    throw new BadGatewayException(s"Unable to reach host ${scu.aeTitle}@${scu.host}:${scu.port}")
                  case e: ConnectException =>
                    throw new BadGatewayException(s"Connection refused on host ${scu.aeTitle}@${scu.host}:${scu.port}")
                  case e: NoRouteToHostException =>
                    throw new BadGatewayException(s"No route found to host ${scu.aeTitle}@${scu.host}:${scu.port}")
                }
                .pipeTo(sender)
            }).orElse(throw new NotFoundException(s"SCU with id $scuId not found"))
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

  def getScus(startIndex: Long, count: Long) =
    db.withSession { implicit session =>
      dao.listScuDatas(startIndex, count)
    }

}

object ScuServiceActor {
  def props(dbProps: DbProps, timeout: Timeout): Props = Props(new ScuServiceActor(dbProps)(timeout))
}

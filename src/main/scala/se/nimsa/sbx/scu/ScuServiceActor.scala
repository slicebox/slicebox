/*
 * Copyright 2014 Lars Edenbrandt
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

import java.io.IOException
import java.net.{ConnectException, NoRouteToHostException, UnknownHostException}

import akka.NotUsed
import akka.actor.{Actor, ActorSelection, ActorSystem, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import akka.stream.{Materializer, scaladsl}
import akka.util.{ByteString, Timeout}
import org.dcm4che3.net.{IncompatibleConnectionException, NoPresentationContextException}
import se.nimsa.dicom.streams.DicomParts
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.lang.{BadGatewayException, NotFoundException}
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

import scala.concurrent.ExecutionContextExecutor

class ScuServiceActor(scuDao: ScuDAO, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val storageService: ActorSelection = context.actorSelection("../StorageService")

  val dicomDataProvider: DicomDataProvider = new DicomDataProvider {
    override def getDicomData(imageId: Long, stopTag: Option[Int]): scaladsl.Source[DicomParts.DicomPart, NotUsed] = storage.dataSource(imageId, stopTag)
    override def getDicomBytes(imageId: Long): scaladsl.Source[ByteString, NotUsed] = storage.fileSource(imageId)
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
            scuForId(scuDataId).foreach(_ => deleteScuWithId(scuDataId))
            sender ! ScuRemoved(scuDataId)

          case GetScus(startIndex, count) =>
            sender ! Scus(getScus(startIndex, count))

          case SendImagesToScp(imageIds, scuId) =>
            scuForId(scuId).map(scu => {
              SbxLog.info("SCU", s"Sending ${imageIds.length} images using SCU ${scu.name}")
              Scu.sendFiles(scu, dicomDataProvider, imageIds)
                .map(r => {
                  SbxLog.info("SCU", s"Finished sending ${imageIds.length} images using SCU ${scu.name}")
                  val nNotFound = imageIds.length - r.length
                  if (nNotFound > 0)
                    SbxLog.warn("SCU", s"$nNotFound of ${imageIds.length} images could not be found and were not sent")
                  context.system.eventStream.publish(ImagesSent(Destination(DestinationType.SCU, scu.name, scu.id), imageIds))
                  ImagesSentToScp(scuId, imageIds)
                })
                .recover {
                  case _: UnknownHostException =>
                    throw new BadGatewayException(s"Unknown host ${scu.aeTitle}@${scu.host}:${scu.port}")
                  case _: IncompatibleConnectionException =>
                    throw new BadGatewayException(s"Incompatible connection to ${scu.aeTitle}@${scu.host}:${scu.port}")
                  case e: IOException if e.getCause != null =>
                    e.getCause match {
                      case _: ConnectException =>
                        throw new BadGatewayException(s"Connection refused on host ${scu.aeTitle}@${scu.host}:${scu.port}")
                      case _: NoRouteToHostException =>
                        throw new BadGatewayException(s"No route found to host ${scu.aeTitle}@${scu.host}:${scu.port}")
                      case e: NoPresentationContextException =>
                        throw new BadGatewayException(s"No presentation context when sending to ${scu.aeTitle}@${scu.host}:${scu.port}: ${e.getMessage}")
                    }
                  case e: RuntimeException =>
                    throw new BadGatewayException(s"Unable to send images to ${scu.aeTitle}@${scu.host}:${scu.port}: ${e.getMessage}")
                }
                .pipeTo(sender)
            }).orElse(throw new NotFoundException(s"SCU with id $scuId not found"))
        }
      }

  }

  def addScu(scuData: ScuData): ScuData =
    await(scuDao.insert(scuData))

  def scuForId(id: Long): Option[ScuData] =
    await(scuDao.scuDataForId(id))

  def scuForName(name: String): Option[ScuData] =
    await(scuDao.scuDataForName(name))

  def scuForHostAndPort(host: String, port: Int): Option[ScuData] =
    await(scuDao.scuDataForHostAndPort(host, port))

  def deleteScuWithId(id: Long): Int =
    await(scuDao.deleteScuDataWithId(id))

  def getScus(startIndex: Long, count: Long): Seq[ScuData] =
    await(scuDao.listScuDatas(startIndex, count))

}

object ScuServiceActor {
  def props(scuDao: ScuDAO, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new ScuServiceActor(scuDao, storage))
}

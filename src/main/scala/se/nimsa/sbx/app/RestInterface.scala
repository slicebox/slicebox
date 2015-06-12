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

package se.nimsa.sbx.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import akka.actor.Actor
import akka.util.Timeout

import spray.routing.HttpService

import com.mchange.v2.c3p0.ComboPooledDataSource
import com.typesafe.config.ConfigFactory

import se.nimsa.sbx.app.routing.SliceboxRoutes
import se.nimsa.sbx.box.BoxServiceActor
import se.nimsa.sbx.storage.StorageServiceActor
import se.nimsa.sbx.scp.ScpServiceActor
import se.nimsa.sbx.scu.ScuServiceActor
import se.nimsa.sbx.directory.DirectoryWatchServiceActor
import se.nimsa.sbx.log.LogServiceActor
import se.nimsa.sbx.seriestype.SeriesTypeServiceActor

class RestInterface extends Actor with RestApi {

  def actorRefFactory = context

  def dbUrl = "jdbc:h2:" + sliceboxConfig.getString("database.path")

  def createStorageDirectory = {
    val storagePath = Paths.get(sliceboxConfig.getString("dicom-files.path"))
    if (!Files.exists(storagePath))
      try {
        Files.createDirectories(storagePath)
      } catch {
        case e: Exception => throw new RuntimeException("Dicom-files directory could not be created: " + e.getMessage)
      }
    if (!Files.isDirectory(storagePath))
      throw new IllegalArgumentException("Dicom-files directory is not a directory.")
    storagePath
  }

  def receive = runRoute(routes)

}

trait RestApi extends HttpService with SliceboxRoutes with JsonFormats {

  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(70.seconds)

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")

  def createStorageDirectory(): Path
  def dbUrl(): String

  def db = {
    val ds = new ComboPooledDataSource
    ds.setDriverClass("org.h2.Driver")
    ds.setJdbcUrl(dbUrl)
    Database.forDataSource(ds)
  }
  
  val dbProps = DbProps(db, H2Driver)

  val storage = createStorageDirectory()

  val host = config.getString("http.host")
  val port = config.getInt("http.port")
  val apiBaseURL = s"http://$host:$port/api"
  val superUser = sliceboxConfig.getString("superuser.user")
  val superPassword = sliceboxConfig.getString("superuser.password")

  val userService = actorRefFactory.actorOf(UserServiceActor.props(dbProps, superUser, superPassword), name = "UserService")
  val logService = actorRefFactory.actorOf(LogServiceActor.props(dbProps), name = "LogService")
  val storageService = actorRefFactory.actorOf(StorageServiceActor.props(dbProps, storage), name = "StorageService")
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, storage, apiBaseURL), name = "BoxService")
  val scpService = actorRefFactory.actorOf(ScpServiceActor.props(dbProps), name = "ScpService")
  val scuService = actorRefFactory.actorOf(ScuServiceActor.props(dbProps, storage), name = "ScuService")
  val directoryService = actorRefFactory.actorOf(DirectoryWatchServiceActor.props(dbProps, storage), name = "DirectoryService")
  val seriesTypeService = actorRefFactory.actorOf(SeriesTypeServiceActor.props(dbProps), name = "SeriesTypeService")

  val authenticator = new Authenticator(userService)

  def routes = sliceboxRoutes
}

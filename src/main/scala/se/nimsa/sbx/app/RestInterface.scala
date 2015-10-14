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
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import com.mchange.v2.c3p0.ComboPooledDataSource
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.app.routing.SliceboxRoutes
import se.nimsa.sbx.box.BoxDAO
import se.nimsa.sbx.box.BoxServiceActor
import se.nimsa.sbx.directory.DirectoryWatchDAO
import se.nimsa.sbx.directory.DirectoryWatchServiceActor
import se.nimsa.sbx.forwarding.ForwardingDAO
import se.nimsa.sbx.forwarding.ForwardingServiceActor
import se.nimsa.sbx.log.LogDAO
import se.nimsa.sbx.log.LogServiceActor
import se.nimsa.sbx.scp.ScpDAO
import se.nimsa.sbx.scp.ScpServiceActor
import se.nimsa.sbx.scu.ScuDAO
import se.nimsa.sbx.scu.ScuServiceActor
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeServiceActor
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.PropertiesDAO
import se.nimsa.sbx.storage.StorageServiceActor
import se.nimsa.sbx.user.Authenticator
import se.nimsa.sbx.user.UserDAO
import se.nimsa.sbx.user.UserServiceActor
import spray.routing.HttpService
import se.nimsa.sbx.log.SbxLog

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

  val appConfig: Config = ConfigFactory.load()
  val sliceboxConfig: Config = appConfig.getConfig("slicebox")

  def createStorageDirectory(): Path
  def dbUrl(): String

  def db = {
    val ds = new ComboPooledDataSource
    ds.setDriverClass("org.h2.Driver")
    ds.setJdbcUrl(dbUrl)
    Database.forDataSource(ds)
  }

  val dbProps = DbProps(db, H2Driver)

  db.withSession { implicit session =>
    new LogDAO(dbProps.driver).create
    new UserDAO(dbProps.driver).create
    new SeriesTypeDAO(dbProps.driver).create
    new ForwardingDAO(dbProps.driver).create
    new MetaDataDAO(dbProps.driver).create
    new PropertiesDAO(dbProps.driver).create
    new DirectoryWatchDAO(dbProps.driver).create
    new ScpDAO(dbProps.driver).create
    new ScuDAO(dbProps.driver).create
    new BoxDAO(dbProps.driver).create
  }

  val storage = createStorageDirectory()

  val host = sliceboxConfig.getString("host")
  val publicHost = sliceboxConfig.getString("public.host")

  val port = sliceboxConfig.getInt("port")
  val publicPort = sliceboxConfig.getInt("public.port")

  val withReverseProxy = (host != publicHost) || (port != publicPort)
  val withSsl =
    if (withReverseProxy)
      sliceboxConfig.getBoolean("public.with-ssl")
    else
      sliceboxConfig.getString("ssl.ssl-encryption") == "on"

  val clientTimeout = appConfig.getDuration("spray.can.client.request-timeout", MILLISECONDS)
  val serverTimeout = appConfig.getDuration("spray.can.server.request-timeout", MILLISECONDS)

  implicit val timeout = Timeout(math.max(clientTimeout, serverTimeout) + 10, MILLISECONDS)

  val apiBaseURL = {

    val ssl = if (withSsl) "s" else ""

    if (!withSsl && (publicPort == 80) || withSsl && (publicPort == 443))
      s"http$ssl://$publicHost/api"
    else
      s"http$ssl://$publicHost:$publicPort/api"
  }

  val superUser = sliceboxConfig.getString("superuser.user")
  val superPassword = sliceboxConfig.getString("superuser.password")

  implicit def executionContext = actorRefFactory.dispatcher

  val userService = actorRefFactory.actorOf(UserServiceActor.props(dbProps, superUser, superPassword), name = "UserService")
  val logService = actorRefFactory.actorOf(LogServiceActor.props(dbProps), name = "LogService")
  val storageService = actorRefFactory.actorOf(StorageServiceActor.props(dbProps, storage).withDispatcher("akka.prio-dispatcher"), name = "StorageService")
  val anonymizationService = actorRefFactory.actorOf(AnonymizationServiceActor.props(dbProps), name = "AnonymizationService")
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, apiBaseURL, timeout), name = "BoxService")
  val scpService = actorRefFactory.actorOf(ScpServiceActor.props(dbProps), name = "ScpService")
  val scuService = actorRefFactory.actorOf(ScuServiceActor.props(dbProps, timeout), name = "ScuService")
  val directoryService = actorRefFactory.actorOf(DirectoryWatchServiceActor.props(dbProps, storage), name = "DirectoryService")
  val seriesTypeService = actorRefFactory.actorOf(SeriesTypeServiceActor.props(dbProps, timeout), name = "SeriesTypeService")
  val forwardingService = actorRefFactory.actorOf(ForwardingServiceActor.props(dbProps, timeout), name = "ForwardingService")

  val authenticator = new Authenticator(userService)

  def routes = sliceboxRoutes
}

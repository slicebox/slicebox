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

package se.nimsa.sbx.app

import java.nio.file.Paths
import java.util.concurrent.TimeUnit.MILLISECONDS

import akka.actor.Actor
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.app.routing.SliceboxRoutes
import se.nimsa.sbx.box.{BoxDAO, BoxServiceActor}
import se.nimsa.sbx.directory.{DirectoryWatchDAO, DirectoryWatchServiceActor}
import se.nimsa.sbx.forwarding.{ForwardingDAO, ForwardingServiceActor}
import se.nimsa.sbx.importing.{ImportDAO, ImportServiceActor}
import se.nimsa.sbx.log.{LogDAO, LogServiceActor}
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.scp.{ScpDAO, ScpServiceActor}
import se.nimsa.sbx.scu.{ScuDAO, ScuServiceActor}
import se.nimsa.sbx.seriestype.{SeriesTypeDAO, SeriesTypeServiceActor}
import se.nimsa.sbx.storage.{FileStorage, S3Storage, StorageService, StorageServiceActor}
import se.nimsa.sbx.user.{Authenticator, UserDAO, UserServiceActor}
import spray.routing.HttpService

import scala.slick.driver.{H2Driver, MySQLDriver}
import scala.slick.jdbc.JdbcBackend.Database

class SliceboxServiceActor extends Actor with SliceboxService {

  override def actorRefFactory = context

  override def createStorageService(): StorageService =
    if (sliceboxConfig.getString("dicom-storage.config.name") == "s3")
      new S3Storage(sliceboxConfig.getString("dicom-storage.config.bucket"), sliceboxConfig.getString("dicom-storage.config.prefix"))
    else
      new FileStorage(Paths.get(sliceboxConfig.getString("dicom-storage.file-system.path")))

  override def dbUrl = sliceboxConfig.getString("database.path")

  override def receive = runRoute(routes)

}

trait SliceboxService extends HttpService with SliceboxRoutes with JsonFormats {

  val appConfig: Config = ConfigFactory.load()

  val sliceboxConfig = appConfig.getConfig("slicebox")

  def createStorageService(): StorageService

  def dbUrl: String

  def db = {
    val config = new HikariConfig()
    config.setJdbcUrl(dbUrl)
    if (sliceboxConfig.hasPath("database.user") && sliceboxConfig.getString("database.user").nonEmpty)
      config.setUsername(sliceboxConfig.getString("database.user"))
    if (sliceboxConfig.hasPath("database.password") && sliceboxConfig.getString("database.password").nonEmpty)
      config.setPassword(sliceboxConfig.getString("database.password"))
    Database.forDataSource(new HikariDataSource(config))
  }

  val driver = {
    val pattern = "jdbc:(.*?):".r
    val driverString = pattern.findFirstMatchIn(dbUrl).map(_ group 1)
    if (driverString.isEmpty)
      throw new IllegalArgumentException(s"Malformed database URL: $dbUrl")
    driverString.get.toLowerCase match {
      case "h2" => H2Driver
      case "mysql" => MySQLDriver
      case s => throw new IllegalArgumentException(s"Database not supported: $s")
    }
  }
  val dbProps = DbProps(db, driver)

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
    new ImportDAO(dbProps.driver).create
  }


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

  val purgeEmptyAnonymizationKeys = sliceboxConfig.getBoolean("anonymization.purge-empty-keys")

  val apiBaseURL = {

    val ssl = if (withSsl) "s" else ""

    if (!withSsl && (publicPort == 80) || withSsl && (publicPort == 443))
      s"http$ssl://$publicHost/api"
    else
      s"http$ssl://$publicHost:$publicPort/api"
  }

  val superUser = sliceboxConfig.getString("superuser.user")
  val superPassword = sliceboxConfig.getString("superuser.password")
  val sessionTimeout = sliceboxConfig.getDuration("session-timeout", MILLISECONDS)

  val sessionField = "slicebox-session"

  implicit def executionContext = actorRefFactory.dispatcher

  val storage = createStorageService()

  val userService = actorRefFactory.actorOf(UserServiceActor.props(dbProps, superUser, superPassword, sessionTimeout), name = "UserService")
  val logService = actorRefFactory.actorOf(LogServiceActor.props(dbProps), name = "LogService")
  val metaDataService = actorRefFactory.actorOf(MetaDataServiceActor.props(dbProps).withDispatcher("akka.prio-dispatcher"), name = "MetaDataService")
  val storageService = actorRefFactory.actorOf(StorageServiceActor.props(storage), name = "StorageService")
  val anonymizationService = actorRefFactory.actorOf(AnonymizationServiceActor.props(dbProps, purgeEmptyAnonymizationKeys), name = "AnonymizationService")
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, apiBaseURL, timeout), name = "BoxService")
  val scpService = actorRefFactory.actorOf(ScpServiceActor.props(dbProps, timeout), name = "ScpService")
  val scuService = actorRefFactory.actorOf(ScuServiceActor.props(dbProps, timeout), name = "ScuService")
  val directoryService = actorRefFactory.actorOf(DirectoryWatchServiceActor.props(dbProps, timeout), name = "DirectoryService")
  val seriesTypeService = actorRefFactory.actorOf(SeriesTypeServiceActor.props(dbProps, timeout), name = "SeriesTypeService")
  val forwardingService = actorRefFactory.actorOf(ForwardingServiceActor.props(dbProps, timeout), name = "ForwardingService")
  val importService = actorRefFactory.actorOf(ImportServiceActor.props(dbProps), name = "ImportService")

  val authenticator = new Authenticator(userService)

  def routes = sliceboxRoutes
}

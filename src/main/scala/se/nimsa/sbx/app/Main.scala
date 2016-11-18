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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.app.routing.SliceboxRoutes
import se.nimsa.sbx.box.{BoxDAO, BoxServiceActor}
import se.nimsa.sbx.directory.{DirectoryWatchDAO, DirectoryWatchServiceActor}
import se.nimsa.sbx.forwarding.{ForwardingDAO, ForwardingServiceActor}
import se.nimsa.sbx.importing.{ImportDAO, ImportServiceActor}
import se.nimsa.sbx.log.{LogDAO, LogServiceActor, SbxLog}
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.scp.{ScpDAO, ScpServiceActor}
import se.nimsa.sbx.scu.{ScuDAO, ScuServiceActor}
import se.nimsa.sbx.seriestype.{SeriesTypeDAO, SeriesTypeServiceActor}
import se.nimsa.sbx.storage.{FileStorage, S3Storage, StorageService, StorageServiceActor}
import se.nimsa.sbx.user.{UserDAO, UserServiceActor}

import scala.concurrent.ExecutionContextExecutor
import scala.slick.driver.{H2Driver, MySQLDriver}
import scala.slick.jdbc.JdbcBackend.Database
import scala.util.{Failure, Success}

trait SliceboxBase extends SliceboxRoutes with JsonFormats with SprayJsonSupport {

  val appConfig = ConfigFactory.load()
  val sliceboxConfig = appConfig.getConfig("slicebox")

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def executor: ExecutionContextExecutor
  implicit val timeout = {
    val clientTimeout = appConfig.getDuration("akka.http.client.connecting-timeout", MILLISECONDS)
    val serverTimeout = appConfig.getDuration("akka.http.server.request-timeout", MILLISECONDS)
    Timeout(math.max(clientTimeout, serverTimeout) + 10, MILLISECONDS)
  }

  def dbProps: DbProps

  dbProps.db.withSession { implicit session =>
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
  val port = sliceboxConfig.getInt("port")
  val publicHost = sliceboxConfig.getString("public.host")
  val publicPort = sliceboxConfig.getInt("public.port")

  val useSsl = sliceboxConfig.getString("ssl.ssl-encryption") == "on"

  val apiBaseURL = {
    val withReverseProxy = (host != publicHost) || (port != publicPort)
    val withSsl = withReverseProxy && sliceboxConfig.getBoolean("public.with-ssl") || useSsl

    val ssl = if (withSsl) "s" else ""

    if (!withSsl && (publicPort == 80) || withSsl && (publicPort == 443))
      s"http$ssl://$publicHost/api"
    else
      s"http$ssl://$publicHost:$publicPort/api"
  }

  val superUser = sliceboxConfig.getString("superuser.user")
  val superPassword = sliceboxConfig.getString("superuser.password")

  def storage: StorageService

  val userService = {
    val sessionTimeout = sliceboxConfig.getDuration("session-timeout", MILLISECONDS)
    system.actorOf(UserServiceActor.props(dbProps, superUser, superPassword, sessionTimeout), name = "UserService")
  }
  val logService = system.actorOf(LogServiceActor.props(dbProps), name = "LogService")
  val metaDataService = system.actorOf(MetaDataServiceActor.props(dbProps).withDispatcher("akka.prio-dispatcher"), name = "MetaDataService")
  val storageService = system.actorOf(StorageServiceActor.props(storage), name = "StorageService")
  val anonymizationService = {
    val purgeEmptyAnonymizationKeys = sliceboxConfig.getBoolean("anonymization.purge-empty-keys")
    system.actorOf(AnonymizationServiceActor.props(dbProps, purgeEmptyAnonymizationKeys), name = "AnonymizationService")
  }
  val boxService = system.actorOf(BoxServiceActor.props(dbProps, apiBaseURL, timeout), name = "BoxService")
  val scpService = system.actorOf(ScpServiceActor.props(dbProps, timeout), name = "ScpService")
  val scuService = system.actorOf(ScuServiceActor.props(dbProps, timeout), name = "ScuService")
  val directoryService = system.actorOf(DirectoryWatchServiceActor.props(dbProps, timeout), name = "DirectoryService")
  val seriesTypeService = system.actorOf(SeriesTypeServiceActor.props(dbProps, timeout), name = "SeriesTypeService")
  val forwardingService = system.actorOf(ForwardingServiceActor.props(dbProps, timeout), name = "ForwardingService")
  val importService = system.actorOf(ImportServiceActor.props(dbProps), name = "ImportService")

}

object Main extends {
  implicit val system = ActorSystem("slicebox")
  implicit val materializer = ActorMaterializer()
  implicit val executor = system.dispatcher
  val cfg = ConfigFactory.load().getConfig("slicebox")
  val dbProps = {
    val dbUrl = cfg.getString("database.path")
    val db = {
      val config = new HikariConfig()
      config.setJdbcUrl(dbUrl)
      if (cfg.hasPath("database.user") && cfg.getString("database.user").nonEmpty)
        config.setUsername(cfg.getString("database.user"))
      if (cfg.hasPath("database.password") && cfg.getString("database.password").nonEmpty)
        config.setPassword(cfg.getString("database.password"))
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
    DbProps(db, driver)
  }
  val storage =
    if (cfg.getString("dicom-storage.config.name") == "s3")
      new S3Storage(cfg.getString("dicom-storage.config.bucket"), cfg.getString("dicom-storage.config.prefix"))
    else
      new FileStorage(Paths.get(cfg.getString("dicom-storage.file-system.path")))
} with SliceboxBase with App {

  val bindFuture = if (useSsl) {
    Http().setDefaultClientHttpsContext(SslConfiguration.httpsContext)
    Http().bindAndHandle(routes, host, port, SslConfiguration.httpsContext)
  } else
    Http().bindAndHandle(routes, host, port)

  bindFuture onComplete {
    case Success(_) =>
      SbxLog.info("System", s"Slicebox bound to $host:$port")
    case Failure(e) =>
      SbxLog.error("System", s"Could not bind to $host:$port, ${e.getMessage}")
  }

}

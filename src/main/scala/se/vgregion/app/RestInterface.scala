package se.vgregion.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import akka.actor.Actor
import akka.util.Timeout

import spray.http.StatusCodes.BadRequest
import spray.routing.ExceptionHandler
import spray.routing.HttpService

import com.typesafe.config.ConfigFactory

import se.vgregion.app.routing.SliceboxRoutes
import se.vgregion.box.BoxServiceActor
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.log.LogServiceActor

class RestInterface extends Actor with RestApi {

  def actorRefFactory = context

  def dbUrl = "jdbc:h2:storage"

  def createStorageDirectory = {
    val storagePath = Paths.get(sliceboxConfig.getString("storage"))
    if (!Files.exists(storagePath))
      Files.createDirectories(storagePath)
    if (!Files.isDirectory(storagePath))
      throw new IllegalArgumentException("Storage directory is not a directory.")
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

  def db = Database.forURL(dbUrl,
    user = sliceboxConfig.getString("db.user"),
    password = sliceboxConfig.getString("db.password"),
    driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = createStorageDirectory()

  val host = config.getString("http.host")
  val port = config.getInt("http.port")
  val apiBaseURL = s"http://$host:$port/api"
  val superUser = sliceboxConfig.getString("superuser.user")
  val superPassword = sliceboxConfig.getString("superuser.password")

  val userService = actorRefFactory.actorOf(UserServiceActor.props(dbProps, superUser, superPassword), "UserService")
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, storage, apiBaseURL), "BoxService")
  val dicomService = actorRefFactory.actorOf(DicomDispatchActor.props(storage, dbProps), "DicomDispatch")
  val logService = actorRefFactory.actorOf(LogServiceActor.props(dbProps), "LogService")

  val authenticator = new Authenticator(userService)

  def routes = sliceboxRoutes
}


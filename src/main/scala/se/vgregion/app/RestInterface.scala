package se.vgregion.app

import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import spray.http.StatusCodes._
import spray.httpx.PlayTwirlSupport.twirlHtmlMarshaller
import spray.httpx.SprayJsonSupport._
import spray.routing._
import com.typesafe.config.ConfigFactory
import se.vgregion.box.BoxProtocol._
import se.vgregion.box.BoxServiceActor
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.dicom.directory.DirectoryWatchServiceActor
import se.vgregion.dicom.scp.ScpServiceActor
import java.nio.file.Path
import java.nio.file.Files

class RestInterface extends Actor with RestApi {

  def actorRefFactory = context

  def dbUrl = "jdbc:h2:storage"

  def createStorageDirectory = {
    val storagePath = Paths.get(sliceboxConfig.getString("storage"))
    if (!Files.exists(storagePath))
      Files.createDirectories(storagePath)
    if (!Files.isDirectory(storage))
      throw new IllegalArgumentException("Storage directory is not a directory.")
    storagePath
  }

  def receive = runRoute(routes)

}

trait RestApi extends HttpService with JsonFormats {

  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(10.seconds)

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")

  def createStorageDirectory(): Path
  def dbUrl(): String

  def db = Database.forURL(dbUrl, driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = createStorageDirectory()

  val userService = new DbUserRepository(actorRefFactory, dbProps)
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, config.getString("http.host"), config.getInt("http.port")), "BoxService")
  val dicomService = actorRefFactory.actorOf(DicomDispatchActor.props(storage, dbProps), "DicomDispatch")

  val authenticator = new Authenticator(userService)

  def staticResourcesRoutes =
    get {
      pathPrefix("assets") {
        path(Rest) { path =>
          getFromResource("public/" + path)
        }
      }
    }

  def twirlRoutes =
    get {
      path("") {
        complete(views.html.index())
      }
    }

  def directoryRoutes: Route =
    pathPrefix("directory") {
      post {
        entity(as[WatchDirectory]) { directory =>
          onSuccess(dicomService.ask(directory)) {
            case DirectoryWatched(path) =>
              complete("Now watching directory " + path)
          }
        }
      } ~ delete {
        entity(as[UnWatchDirectory]) { directory =>
          onSuccess(dicomService.ask(directory)) {
            case DirectoryUnwatched(path) =>
              complete("Stopped watching directory " + path)
          }
        }
      } ~ get {
        path("list") {
          onSuccess(dicomService.ask(GetWatchedDirectories)) {
            case WatchedDirectories(list) =>
              complete(list.map(_.toString))
          }
        }
      }

    }

  def scpRoutes: Route =
    pathPrefix("scp") {
      post {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            onSuccess(dicomService.ask(AddScp(scpData))) {
              case ScpAdded(scpData) =>
                complete("Added SCP " + scpData.name)
            }
          }
        }
      } ~ delete {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            onSuccess(dicomService.ask(RemoveScp(scpData))) {
              case ScpRemoved(scpData) =>
                complete("Removed SCP " + scpData.name)
            }
          }
        }
      } ~ get {
        path("list") {
          onSuccess(dicomService.ask(GetScpDataCollection)) {
            case ScpDataCollection(list) =>
              complete(list)
          }
        }
      }
    }

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      get {
        path("patients") {
          onSuccess(dicomService.ask(GetPatients(None))) {
            case Patients(patients) =>
              complete(patients)
          }
        } ~ path("studies") {
          entity(as[Patient]) { patient =>
            onSuccess(dicomService.ask(GetStudies(patient, None))) {
              case Studies(studies) =>
                complete(studies)
            }
          }
        } ~ path("series") {
          entity(as[Study]) { study =>
            onSuccess(dicomService.ask(GetSeries(study, None))) {
              case SeriesCollection(series) =>
                complete(series)
            }
          }
        } ~ path("images") {
          entity(as[Series]) { series =>
            onSuccess(dicomService.ask(GetImages(series, None))) {
              case Images(images) =>
                complete(images)
            }
          }
        } ~ path("allimages") {
          onSuccess(dicomService.ask(GetAllImages(None))) {
            case Images(images) =>
              complete(images)
          }
        }
      }
    }
  }

  def boxRoutes: Route =
    pathPrefix("box") {
      get {
        path("list") {
          onSuccess(boxService.ask(GetBoxes)) {
            case Boxes(configs) =>
              complete((OK, configs))
          }
        }
      } ~ post {
        path("add") {
          entity(as[BoxConfig]) { config =>
            onSuccess(boxService.ask(AddBox(config))) {
              case BoxAdded(config) =>
                complete((OK, "Added box " + config.name))
            }
          }
        } ~ path("create") {
          entity(as[BoxName]) { boxName =>
            onSuccess(boxService.ask(CreateBox(boxName.name))) {
              case BoxCreated(config) =>
                complete((OK, "Created box " + config.name))
            }
          }
        }
      } ~ delete {
        pathEnd {
          entity(as[BoxConfig]) { config =>
            onSuccess(boxService.ask(RemoveBox(config))) {
              case BoxRemoved(config) =>
                complete((OK, "Removed box " + config.name))
            }
          }
        }
      }
    }

  def userRoutes: Route =
    pathPrefix("user") {
      post {
        pathEnd {
          entity(as[ClearTextUser]) { user =>
            val apiUser = ApiUser(user.user, user.role).withPassword(user.password)
            onSuccess(userService.addUser(apiUser)) {
              _ match {
                case Some(newUser) =>
                  complete(s"Added user ${newUser.user}")
                case None =>
                  complete((BadRequest, s"User ${user.user} already exists."))
              }
            }
          }
        }
      } ~ delete {
        pathEnd {
          entity(as[String]) { userName =>
            onSuccess(userService.deleteUser(userName)) {
              _ match {
                case Some(deletedUser) =>
                  complete(s"Deleted user ${deletedUser.user}")
                case None =>
                  complete((BadRequest, s"User ${userName} does not exist."))
              }
            }
          }
        }
      } ~ get {
        path("names") {
          onSuccess(userService.listUserNames()) { userNames =>
            complete(userNames)
          }
        }
      }
    } ~ pathPrefix("private") {
      authenticate(authenticator.basicUserAuthenticator) { authInfo =>
        path("test") {
          get {
            complete(s"Hi, ${authInfo.user.user}")
          }
        } ~ path("admin") {
          authorize(authInfo.hasPermission(Administrator)) {
            get {
              complete(s"Admin: ${authInfo.user.user}")
            }
          }
        }

      }
    }

  def systemRoutes: Route =
    path("stop") {
      (post | parameter('method ! "post")) {
        complete {
          var system = actorRefFactory.asInstanceOf[ActorContext].system
          system.scheduler.scheduleOnce(1.second)(system.shutdown())(system.dispatcher)
          "Shutting down in 1 second..."
        }
      }
    }

  def routes: Route =
    twirlRoutes ~ staticResourcesRoutes ~ pathPrefix("api") {
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ boxRoutes ~ userRoutes ~ systemRoutes
    }

}


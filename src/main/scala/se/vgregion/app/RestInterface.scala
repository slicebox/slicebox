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
import se.vgregion.util.PerRequest

class RestInterface extends Actor with RestApi {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  def dbUrl = "jdbc:h2:storage"

  def receive = runRoute(routes)

}

trait RestApi extends HttpService with JsonFormats {

  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(10.seconds)

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")
  val storage = Paths.get(sliceboxConfig.getString("storage"))

  def dbUrl(): String

  def db = Database.forURL(dbUrl, driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val directoryService = actorRefFactory.actorOf(DirectoryWatchServiceActor.props(dbProps, storage), "DirectoryService")
  val scpService = actorRefFactory.actorOf(ScpServiceActor.props(dbProps, storage), "ScpService")
  val userService = new DbUserRepository(actorRefFactory, dbProps)
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, config.getString("http.host"), config.getInt("http.port")), "BoxService")

  val dispatchProps = DicomDispatchActor.props(directoryService, scpService, storage, dbProps)

  val authenticator = new Authenticator(userService)

  def staticResourcesRoutes =
    get {
      pathPrefix("assets") {
        path(Rest) { path =>
          getFromResource("public/" + path)
        }
      }
    }

  def angularRoutes =
    get {
      pathPrefix("") {
        complete(views.html.index())
      }
    }

  def directoryRoutes: Route =
    pathPrefix("directory") {
      post {
        entity(as[WatchDirectory]) { directory =>
          ctx =>
            actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, directory) {
              def handleResponse = {
                case DirectoryWatched(path) =>
                  complete(OK, "Now watching directory " + path)
              }
            }))
        }
      } ~ delete {
        entity(as[UnWatchDirectory]) { directory =>
          ctx =>
            actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, directory) {
              def handleResponse = {
                case DirectoryUnwatched(path) =>
                  complete(OK, "Stopped watching directory " + path)
              }
            }))
        }
      } ~ get {
        path("list") { ctx =>
          actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetWatchedDirectories) {
            def handleResponse = {
              case WatchedDirectories(list) =>
                complete(OK, list.map(_.toString))
            }
          }))
        }
      }

    }

  def scpRoutes: Route =
    pathPrefix("scp") {
      post {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            ctx =>
              actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, AddScp(scpData)) {
                def handleResponse = {
                  case ScpAdded(scpData) =>
                    complete(OK, "Added SCP " + scpData.name)
                }
              }))
          }
        }
      } ~ delete {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            ctx =>
              actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, RemoveScp(scpData)) {
                def handleResponse = {
                  case ScpRemoved(scpData) =>
                    complete(OK, "Removed SCP " + scpData.name)
                }
              }))
          }
        }
      } ~ get {
        path("list") { ctx =>
          actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetScpDataCollection) {
            def handleResponse = {
              case ScpDataCollection(list) =>
                complete(OK, list)
            }
          }))
        }
      }
    }

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      get {
        path("patients") {
          ctx =>
            actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetPatients(None)) {
              def handleResponse = {
                case Patients(patients) =>
                  complete(OK, patients)
              }
            }))
        } ~ path("studies") {
          entity(as[Patient]) { patient =>
            ctx =>
              actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetStudies(patient, None)) {
                def handleResponse = {
                  case Studies(studies) =>
                    complete(OK, studies)
                }
              }))
          }
        } ~ path("series") {
          entity(as[Study]) { study =>
            ctx =>
              actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetSeries(study, None)) {
                def handleResponse = {
                  case SeriesCollection(series) =>
                    complete(OK, series)
                }
              }))
          }
        } ~ path("images") {
          entity(as[Series]) { series =>
            ctx =>
              actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetImages(series, None)) {
                def handleResponse = {
                  case Images(images) =>
                    complete(OK, images)
                }
              }))
          }
        } ~ path("allimages") {
          ctx =>
            actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetAllImages(None)) {
              def handleResponse = {
                case Images(images) =>
                  complete(OK, images)
              }
            }))
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
    pathPrefix("api") {
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ boxRoutes ~ userRoutes ~ systemRoutes
    } ~ staticResourcesRoutes ~ angularRoutes

  private def dispatchActor = actorRefFactory.actorOf(dispatchProps, "dispatch-" + UUID.randomUUID())

}


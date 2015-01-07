package se.vgregion.app

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Actor
import akka.actor.ActorContext
import akka.pattern.ask
import spray.http.StatusCodes
import spray.httpx.Json4sSupport
import spray.httpx.PlayTwirlSupport.twirlHtmlMarshaller
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.HttpService
import spray.routing.Route
import com.typesafe.config.ConfigFactory
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomDispatchProtocol._
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.directory.DirectoryWatchCollectionActor
import se.vgregion.dicom.scp.ScpCollectionActor
import scala.concurrent.Await
import spray.httpx.SprayJsonSupport._
import spray.json._
import akka.actor.Props
import se.vgregion.util.PerRequest
import spray.http.StatusCodes._
import java.util.UUID
import spray.http.Timedout
import scala.util.Success
import scala.util.Failure

class RestInterface extends Actor with RestApi {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context
  
  def dbUrl = "jdbc:h2:storage"
  
  def receive = runRoute(routes)

}

trait RestApi extends HttpService with JsonFormats {

  implicit def executionContext = actorRefFactory.dispatcher

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")
  val isProduction = sliceboxConfig.getBoolean("production")
  val storage = Paths.get(sliceboxConfig.getString("storage"))

  def dbUrl(): String
  
  def db = Database.forURL(dbUrl, driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val directoryService = actorRefFactory.actorOf(DirectoryWatchCollectionActor.props(dbProps, storage), "DirectoryService")
  val scpService = actorRefFactory.actorOf(ScpCollectionActor.props(dbProps, storage), "ScpService")
  val userService = new DbUserRepository(actorRefFactory, dbProps)

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

  def twirlRoutes =
    get {
      path("") {
        complete(views.html.index())
      }
    }

  def directoryRoutes: Route =
    pathPrefix("directory") {
      put {
        entity(as[WatchDirectory]) { directory =>
          ctx =>
            actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, directory) {
              def handleResponse = {
                case DirectoryWatched(path) =>
                  complete(OK, "Now watching directory " + path)
              }
            }))
        }
      }
    }

  def scpRoutes: Route =
    pathPrefix("scp") {
      put {
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
      } ~ get {
        path("list") { ctx =>
          actorRefFactory.actorOf(Props(new PerRequest(ctx, dispatchActor, GetScpDataCollection) {
            def handleResponse = {
              case ScpDataCollection(list) =>
                complete(OK, list)
            }
          }))
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
                  complete((StatusCodes.BadRequest, s"User ${user.user} already exists."))
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
                  complete((StatusCodes.BadRequest, s"User ${userName} does not exist."))
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
    } ~ path("initialize") {
      (post | parameter('method ! "post")) {
        onComplete(dispatchActor.ask(Initialize)(4.second).zip(userService.initialize())) {
          case Success(value) => complete("System initialized")
          case Failure(ex)    => complete((InternalServerError, s"System not initialized: ${ex.getMessage}"))
        }
      }
    }

  def routes: Route =
    twirlRoutes ~ staticResourcesRoutes ~ pathPrefix("api") {
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ userRoutes ~ systemRoutes
    }

  private def dispatchActor = actorRefFactory.actorOf(dispatchProps, "dispatch-" + UUID.randomUUID())

}


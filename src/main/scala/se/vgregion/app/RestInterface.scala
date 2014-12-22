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
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import com.typesafe.config.ConfigFactory
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomDispatchProtocol._
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.directory.DirectoryWatchCollectionActor
import se.vgregion.dicom.scp.ScpCollectionActor
import se.vgregion.util.PerRequestCreator
import se.vgregion.util.RestMessage
import scala.concurrent.Await

class RestInterface extends Actor with RestApi {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  def receive = runRoute(routes)

}

trait RestApi extends HttpService with Json4sSupport with PerRequestCreator {

  implicit val json4sFormats = DefaultFormats + new Role.RoleSerializer

  implicit def executionContext = actorRefFactory.dispatcher

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")
  val isProduction = sliceboxConfig.getBoolean("production")
  val storage = Paths.get(sliceboxConfig.getString("storage"))

  def db = Database.forURL("jdbc:h2:storage", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val directoryService = actorRefFactory.actorOf(DirectoryWatchCollectionActor.props(dbProps, storage), "DirectoryService")
  val scpService = actorRefFactory.actorOf(ScpCollectionActor.props(dbProps, storage), "ScpService")
  val userService = new DbUserRepository(actorRefFactory, dbProps)

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
          dispatch(directory)
        }
      }
    }

  def scpRoutes: Route =
    pathPrefix("scp") {
      put {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            dispatch(AddScp(scpData))
          }
        }
      } ~ get {
        path("list") {
          dispatch(GetScpDataCollection)
        }
      } ~ delete {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            dispatch(RemoveScp(scpData))
          }
        }
      }
    }

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      get {
        path("patients") {
          dispatch(GetPatients(None))
        } ~ path("studies") {
          entity(as[Patient]) { patient =>
            dispatch(GetStudies(patient, None))
          }
        } ~ path("series") {
          entity(as[Study]) { study =>
            dispatch(GetSeries(study, None))
          }
        } ~ path("images") {
          entity(as[Series]) { series =>
            dispatch(GetImages(series, None))
          }
        } ~ path("allimages") {
          dispatch(GetAllImages(None))
        }
      }
    }
  }

  def userRoutes: Route =
    pathPrefix("user") {
      put {
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
            complete(Serialization.write(userNames))
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
        val dispatchActor = actorRefFactory.actorOf(DicomDispatchActor.props(directoryService, scpService, storage, dbProps))
        onSuccess(dispatchActor.ask(Initialize)(1.second).zip(userService.initialize())) {
          case (a, b) => complete("System initialized")
        }
      }
    }

  def dispatch(message: RestMessage): Route =
    ctx => perRequest(ctx, DicomDispatchActor.props(directoryService, scpService, storage, dbProps), message)

  def routes: Route =
    twirlRoutes ~ staticResourcesRoutes ~ pathPrefix("api") {
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ userRoutes ~ systemRoutes
    }

}


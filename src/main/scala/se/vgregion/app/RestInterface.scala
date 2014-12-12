package se.vgregion.app

import akka.actor._
import spray.routing._
import spray.http.MediaTypes
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps
import se.vgregion.filesystem.DirectoryWatchCollectionActor
import se.vgregion.dicom.ScpCollectionActor
import se.vgregion.dicom.MetaDataActor
import se.vgregion.dicom.DicomActor
import se.vgregion.db.DbActor
import com.typesafe.config.ConfigFactory
import java.io.File
import spray.routing.PathMatchers._
import scala.util.Right
import scala.util.Failure
import scala.util.Success
import spray.http.MediaTypes
import scala.slick.jdbc.JdbcBackend.Database
import scala.slick.driver.H2Driver
import se.vgregion.db.DAO
import spray.httpx.PlayTwirlSupport._
import se.vgregion.db.DbUserRepository
import java.nio.file.Paths

class RestInterface extends Actor with RestApi {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  def receive = runRoute(routes)
}

trait RestApi extends HttpService {
  import se.vgregion.filesystem.DirectoryWatchProtocol._
  import se.vgregion.dicom.ScpProtocol._
  import se.vgregion.dicom.MetaDataProtocol._
  import se.vgregion.db.DbProtocol._
  
  import akka.pattern.ask
  import akka.pattern.pipe

  // we use the enclosing ActorContext's or ActorSystem's dispatcher for our Futures and Scheduler
  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(10 seconds)

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")
  val isProduction = sliceboxConfig.getBoolean("production")
  val storage = Paths.get(sliceboxConfig.getString("storage"))
  
  private val db =
    if (isProduction)
      Database.forURL("jdbc:h2:storage", driver = "org.h2.Driver")
    else
      Database.forURL("jdbc:h2:mem:storage;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dbActor = actorRefFactory.actorOf(DbActor.props(db, new DAO(H2Driver)), "DbActor")
  val metaDataActor = actorRefFactory.actorOf(MetaDataActor.props(dbActor), "MetaDataActor")
  val dicomActor = actorRefFactory.actorOf(DicomActor.props(metaDataActor, storage), "DicomActor")
  val directoryWatchCollectionActor = actorRefFactory.actorOf(DirectoryWatchCollectionActor.props(dicomActor), "DirectoryWatchCollectionActor")
  val scpCollectionActor = actorRefFactory.actorOf(ScpCollectionActor.props(dbActor, dicomActor), "ScpCollectionActor")
  val userRepository = new DbUserRepository(dbActor)
  val authenticator = new Authenticator(userRepository)
  if (!isProduction) {
    setupDevelopmentEnvironment()
  }

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
        entity(as[MonitorDir]) { monitorDirectory =>
          onSuccess(directoryWatchCollectionActor.ask(monitorDirectory)) {
            _ match {
              case MonitoringDir(directory) => complete(s"Now monitoring directory $directory")
              case MonitorDirFailed(reason) => complete((StatusCodes.BadRequest, s"Monitoring directory failed: ${reason}"))
              case _ => complete(StatusCodes.BadRequest)
            }
          }
        }
      }
    }

  def scpRoutes: Route =
    pathPrefix("scp") {
      put {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            onSuccess(scpCollectionActor.ask(AddScp(scpData))) {
              _ match {
                case ScpAdded(scpData) =>
                  complete((StatusCodes.OK, s"Added SCP ${scpData.name}"))
                case ScpAlreadyAdded(scpData) =>
                  complete((StatusCodes.BadRequest, s"An SCP with name ${scpData.name} is already running."))
              }
            }
          }
        }
      } ~ get {
        path("list") {
          complete {
            scpCollectionActor.ask(GetScpDataCollection).mapTo[ScpDataCollection]
          }
        }
      } ~ delete {
        pathEnd {
          entity(as[DeleteScp]) { deleteScp =>
            onSuccess(scpCollectionActor.ask(deleteScp)) {
              _ match {
                case ScpDeleted(name) =>
                  complete(s"Deleted SCP $name")
                case ScpNotFound(name) =>
                  complete((StatusCodes.NotFound, s"No SCP found with name $name"))
              }
            }
          }
        }
      }
    }

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      get {
        path("list") {
          complete {
            metaDataActor.ask(GetImageFiles).mapTo[ImageFiles]
          }
        } ~ path("patients") {
          complete {
            metaDataActor.ask(GetPatients).mapTo[Patients]
          }
        } ~ path("studies") {
          entity(as[Patient]) { patient =>
            complete {
              metaDataActor.ask(GetStudies(patient)).mapTo[Studies]
            }
          }
        } ~ path("series") {
          entity(as[Study]) { study =>
            complete {
              metaDataActor.ask(GetSeries(study)).mapTo[SeriesCollection]
            }
          }
        } ~ path("images") {
          entity(as[Series]) { series =>
            complete {
              metaDataActor.ask(GetImages(series)).mapTo[Images]
            }
          }
        } ~ path("imagefiles") {
          entity(as[Image]) { image =>
            complete {
              metaDataActor.ask(GetImageFiles(image)).mapTo[ImageFiles]
            }
          }
        }
      }
    }
  }

  def fileRoutes: Route =
    pathPrefix("file") {
      get {
        path("image") {
          entity(as[ImageFile]) { imageFile =>
            getFromFile(imageFile.fileName.value)
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
            onSuccess(userRepository.addUser(apiUser)) {
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
            onSuccess(userRepository.deleteUser(userName)) {
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
          onSuccess(userRepository.listUserNames()) { userNames =>
            complete(userNames.toJson.toString)
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

  def stopRoute: Route =
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
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ fileRoutes ~ userRoutes ~ stopRoute
    }

  def setupDevelopmentEnvironment() = {
    InitialValues.createTables(dbActor)
    InitialValues.addUsers(userRepository)
    InitialValues.initFileSystemData(sliceboxConfig, directoryWatchCollectionActor)
    InitialValues.initScpData(sliceboxConfig, scpCollectionActor, directoryWatchCollectionActor)
  }

}

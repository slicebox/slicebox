package se.vgregion.app

import akka.actor._
import spray.routing._
import spray.http.MediaTypes
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps
import se.vgregion.filesystem.FileSystemActor
import se.vgregion.dicom.ScpCollectionActor
import se.vgregion.db.DbActor
import com.typesafe.config.ConfigFactory
import java.io.File
import se.vgregion.filesystem.FileSystemProtocol.MonitorDir
import spray.routing.PathMatchers._
import scala.util.Right
import scala.util.Failure
import scala.util.Success
import spray.http.MediaTypes
import se.vgregion.dicom.MetaDataActor
import scala.slick.jdbc.JdbcBackend.Database
import scala.slick.driver.H2Driver
import se.vgregion.db.DAO
import se.vgregion.db.DbProtocol.CreateTables
import spray.httpx.PlayTwirlSupport._

class RestInterface extends Actor with RestApi {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  def receive = runRoute(routes)
}

trait RestApi extends HttpService {
  import se.vgregion.filesystem.FileSystemProtocol._
  import se.vgregion.dicom.ScpProtocol._
  import se.vgregion.dicom.MetaDataProtocol._
  import akka.pattern.ask
  import akka.pattern.pipe

  // we use the enclosing ActorContext's or ActorSystem's dispatcher for our Futures and Scheduler
  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(10 seconds)

  val config = ConfigFactory.load()
  val isProduction = config.getBoolean("production")

  private val db =
    if (isProduction)
      Database.forURL("jdbc:h2:storage", driver = "org.h2.Driver")
    else
      Database.forURL("jdbc:h2:mem:storage;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dbActor = actorRefFactory.actorOf(Props(classOf[DbActor], db, new DAO(H2Driver)))
  val metaDataActor = actorRefFactory.actorOf(Props(classOf[MetaDataActor], dbActor))
  val fileSystemActor = actorRefFactory.actorOf(Props(classOf[FileSystemActor], metaDataActor))
  val scpCollectionActor = actorRefFactory.actorOf(Props(classOf[ScpCollectionActor], dbActor))

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
    path("monitordirectory") {
      put {
        entity(as[MonitorDir]) { monitorDirectory =>
          onSuccess(fileSystemActor.ask(monitorDirectory)) {
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
            metaDataActor.ask(GetImages).mapTo[Images]
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
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ stopRoute
    }

  def setupDevelopmentEnvironment() = {
    InitialValues.createTables(dbActor)
    InitialValues.initFileSystemData(config, fileSystemActor)
    InitialValues.initScpData(config, scpCollectionActor, fileSystemActor)
  }

}

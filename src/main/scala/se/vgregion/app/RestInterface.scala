package se.vgregion.app

import akka.actor._
import spray.routing._
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps
import se.vgregion.filesystem.FileSystemActor
import se.vgregion.dicom.ScpCollectionActor
import se.vgregion.db.DbActor
import se.vgregion.db.SqlDbOps
import com.typesafe.config.ConfigFactory
import java.io.File
import se.vgregion.filesystem.FileSystemProtocol.MonitorDir
import spray.routing.PathMatchers._
import scala.util.Right
import scala.util.Failure
import scala.util.Success
import spray.http.MediaTypes
import se.vgregion.dicom.MetaDataActor

class RestInterface extends HttpServiceActor
  with RestApi {
  def receive = runRoute(routes)
}

trait RestApi extends HttpService with ActorLogging { actor: Actor =>
  import context.dispatcher

  import se.vgregion.filesystem.FileSystemProtocol._
  import se.vgregion.dicom.ScpProtocol._
  import se.vgregion.dicom.MetaDataProtocol._

  implicit val timeout = Timeout(10 seconds)
  import akka.pattern.ask

  import akka.pattern.pipe

  val config = ConfigFactory.load()
  val isProduction = config.getBoolean("application.production.mode")
  val scpStorageDirectory = config.getString("scp.storage.directory")

  val dbActor = context.actorOf(Props(classOf[DbActor], new SqlDbOps(isProduction)))
  val metaDataActor = context.actorOf(Props(classOf[MetaDataActor], dbActor))
  val fileSystemActor = context.actorOf(Props(classOf[FileSystemActor], metaDataActor))
  val scpCollectionActor = context.actorOf(Props(classOf[ScpCollectionActor], dbActor, scpStorageDirectory))

  if (!isProduction) {
    // Add some initial values for easier development with in-mem database
    //InitialValues.initFileSystemData(fileSystemActor)
    //InitialValues.initScpData(scpCollectionActor)
  }
  //fileSystemActor ! MonitorDir(scpStorageDirectory)

  def directoryRoutes: Route =
    path("monitordirectory") {
      put {
        entity(as[MonitorDir]) { dir =>
          onSuccess(fileSystemActor.ask(dir)) {
            _ match {
              case MonitoringDir(dir) => complete(s"Now monitoring directory $dir")
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
          onSuccess(scpCollectionActor.ask(GetScpDataCollection)) {
            _ match {
              case ScpDataCollection(data) =>
                complete((StatusCodes.OK, data))
            }
          }
        }
      } ~ delete {
        path(Segment) { name =>
          onSuccess(scpCollectionActor.ask(DeleteScp(name))) {
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

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      get {
        path("list") {
          onSuccess(metaDataActor.ask(GetMetaDataCollection)) {
            _ match {
              case MetaDataCollection(data) =>
                complete((StatusCodes.OK, data))
            }
          }
        }
      }
    }
  }

  def stopRoute: Route =
    (post | parameter('method ! "post")) {
      path("stop") {
        complete {
          var system = context.system
          system.scheduler.scheduleOnce(1.second)(system.shutdown())(system.dispatcher)
          "Shutting down in 1 second..."
        }
      }
    }

  def routes: Route =
    directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ stopRoute

}

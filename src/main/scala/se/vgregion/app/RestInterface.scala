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

class RestInterface extends HttpServiceActor
  with RestApi {
  def receive = runRoute(routes)
}

trait RestApi extends HttpService with ActorLogging { actor: Actor =>
  import context.dispatcher

  import se.vgregion.filesystem.FileSystemProtocol._
  import se.vgregion.dicom.ScpProtocol._
  
  implicit val timeout = Timeout(10 seconds)
  import akka.pattern.ask

  import akka.pattern.pipe

  val config = ConfigFactory.load()
  val isProduction = config.getBoolean("application.production.mode")
  val storageDirectory = new File(config.getString("scp.storage.directory"))
  if (!storageDirectory.exists() || !storageDirectory.isDirectory()) {
    System.err.println("Storage directory does not exist or is not a directory.")
    self ! PoisonPill
  }
  
  val dbActor = context.actorOf(Props(classOf[DbActor], new SqlDbOps(isProduction)))
  val fileSystemActor = context.actorOf(Props[FileSystemActor])
  val scpCollectionActor = context.actorOf(Props(classOf[ScpCollectionActor], dbActor, storageDirectory))

  // Add some initial values for easier development with in-mem database
  InitialValues.initFileSystemData(fileSystemActor)
  InitialValues.initScpData(scpCollectionActor)
  
  def routes: Route =

    path("dir") {
      put {
        entity(as[MonitorDir]) { dir => requestContext =>
          val responder = createResponder(requestContext)
          fileSystemActor.ask(dir).pipeTo(responder)
        }
      }
    } ~
    path("files") {
      get { requestContext =>
        val responder = createResponder(requestContext)
        fileSystemActor.ask(GetFileNames).pipeTo(responder)
      }
    } ~
    path("scps") {
      get { requestContext =>
        val responder = createResponder(requestContext)
        scpCollectionActor.ask(GetScpDataCollection).pipeTo(responder)
      }
    }
  def createResponder(requestContext: RequestContext) = {
    context.actorOf(Props(new Responder(requestContext, fileSystemActor)))
  }

}

class Responder(requestContext: RequestContext, fileSystemActor: ActorRef) extends Actor with ActorLogging {
  import se.vgregion.filesystem.FileSystemProtocol._
  import se.vgregion.dicom.ScpProtocol._
  
  import spray.httpx.SprayJsonSupport._

  def receive = {

    case ScpDataCollection(data) =>
      requestContext.complete((StatusCodes.OK, data))
      self ! PoisonPill
      
    case FileNames(files) =>
      requestContext.complete((StatusCodes.OK, files))
      self ! PoisonPill
      
    case MonitoringDir =>
      requestContext.complete(StatusCodes.OK)
      self ! PoisonPill
      
  }
}
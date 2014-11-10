package se.vgregion

import akka.actor._

import spray.routing._
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps

class RestInterface extends HttpServiceActor
  with RestApi {
  def receive = runRoute(routes)
}

trait RestApi extends HttpService with ActorLogging { actor: Actor =>
  import context.dispatcher

  import se.vgregion.FileSystemProtocol._

  implicit val timeout = Timeout(10 seconds)
  import akka.pattern.ask

  import akka.pattern.pipe

  val fileSystemActor = context.actorOf(Props[FileSystemActor])

  // temporary line
  fileSystemActor ! MonitorDir("C:/users/karl/Desktop/temp")  
  
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
    }
  def createResponder(requestContext: RequestContext) = {
    context.actorOf(Props(new Responder(requestContext, fileSystemActor)))
  }

}

class Responder(requestContext: RequestContext, fileSystemActor: ActorRef) extends Actor with ActorLogging {
  import se.vgregion.FileSystemProtocol._
  
  import spray.httpx.SprayJsonSupport._

  def receive = {

    case FileNames(files) =>
      requestContext.complete((StatusCodes.OK, files))
      self ! PoisonPill
      
    case MonitoringDir =>
      requestContext.complete(StatusCodes.OK)
      self ! PoisonPill
  }
}
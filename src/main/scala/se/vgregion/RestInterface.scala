package se.vgregion

import akka.actor._
import spray.routing._
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps
import se.vgregion.StoreScpProtocol.AddStoreScp
import se.vgregion.StoreScpProtocol.GetStoreScpDataCollection
import se.vgregion.StoreScpProtocol.StoreScpDataCollection

class RestInterface extends HttpServiceActor
  with RestApi {
  def receive = runRoute(routes)
}

trait RestApi extends HttpService with ActorLogging { actor: Actor =>
  import context.dispatcher

  import se.vgregion.FileSystemProtocol._
  import StoreScpProtocol._
  
  implicit val timeout = Timeout(10 seconds)
  import akka.pattern.ask

  import akka.pattern.pipe

  val fileSystemActor = context.actorOf(Props[FileSystemActor])
  val storeScpCollectionActor = context.actorOf(Props[StoreScpCollectionActor])
  
  // temporary lines
  fileSystemActor ! MonitorDir("C:/users/karl/Desktop/temp")  
  storeScpCollectionActor ! AddStoreScp(StoreScpData("testSCP", "myAE", 11123))
  
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
        storeScpCollectionActor.ask(GetStoreScpDataCollection).pipeTo(responder)
      }
    }
  def createResponder(requestContext: RequestContext) = {
    context.actorOf(Props(new Responder(requestContext, fileSystemActor)))
  }

}

class Responder(requestContext: RequestContext, fileSystemActor: ActorRef) extends Actor with ActorLogging {
  import FileSystemProtocol._
  import StoreScpProtocol._
  
  import spray.httpx.SprayJsonSupport._

  def receive = {

    case StoreScpDataCollection(data) =>
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
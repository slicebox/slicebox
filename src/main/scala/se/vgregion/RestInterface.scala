package se.vgregion

import akka.actor._

import spray.routing._
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext
import akka.util.Timeout
import scala.concurrent.duration._

class RestInterface extends HttpServiceActor
  with RestApi {
  def receive = runRoute(routes)
}

trait RestApi extends HttpService with ActorLogging { actor: Actor =>
  import context.dispatcher

  import se.vgregion.StorageProtocol._

  implicit val timeout = Timeout(10 seconds)
  import akka.pattern.ask

  import akka.pattern.pipe

  val imageStorage = context.actorOf(Props[ImageStorage])

  def routes: Route =

    path("images") {
      get { requestContext =>
        val responder = createResponder(requestContext)
        imageStorage.ask(GetImages).pipeTo(responder)
      }
    }
  def createResponder(requestContext: RequestContext) = {
    context.actorOf(Props(new Responder(requestContext, imageStorage)))
  }

}

class Responder(requestContext: RequestContext, ticketMaster: ActorRef) extends Actor with ActorLogging {
  import StorageProtocol._

  import spray.httpx.SprayJsonSupport._

  def receive = {

    case Images(images) =>
      requestContext.complete(StatusCodes.OK, images)
      self ! PoisonPill

  }
}
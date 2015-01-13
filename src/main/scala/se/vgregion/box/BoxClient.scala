package se.vgregion.box

import spray.http._
import spray.client.pipelining._
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure
import akka.actor.ActorContext

class BoxClient(context: ActorContext) {

  implicit val system = context
  
  import system.dispatcher // execution context for futures

  val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

  def ip: Future[HttpResponse] = pipeline(Get("http://bot.whatismyipaddress.com"))
  
}
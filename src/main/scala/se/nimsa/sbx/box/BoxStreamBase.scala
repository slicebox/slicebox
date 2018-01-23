package se.nimsa.sbx.box

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Broadcast, Concat, Flow, GraphDSL}
import akka.stream.{FlowShape, Materializer}
import se.nimsa.sbx.box.BoxProtocol.{Box, OutgoingTransactionImage}
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.matching.Regex

trait BoxStreamBase {

  import BoxStreamBase._

  type ResponseImage = (Try[HttpResponse], OutgoingTransactionImage)

  val box: Box
  val storage: StorageService

  implicit val system: ActorSystem
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext

  val http: HttpExt

  protected val streamChunkSize: Long = storage.streamChunkSize

  protected val retryInterval: FiniteDuration = 15.seconds

  protected def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
    box.baseUrl match {
      case pattern(protocolPart, host, portPart) =>
        val protocol = Option(protocolPart).getOrElse("http")
        val port = Option(portPart) match {
          case None if protocol == "https" => 443
          case None => 80
          case Some(portString) => portString.toInt
        }
        if (port == 443)
          http.cachedHostConnectionPoolHttps[T](host, port)
        else
          http.cachedHostConnectionPool[T](host, port)
    }

  /**
    * When applying this function to the input flow, the resulting flow does not complete until the upstream completes
    * AND the decider function has returned true at least once.
    */
  protected def completeFlow[T](flow: Flow[T, T, _], decider: T => Boolean): Flow[T, T, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val toSomeFlow = builder.add(Flow[T].map(Some.apply))
      val fromOptionFlow = Flow[Option[T]].collect { case Some(t) => t }

      val concatFinished = builder.add(Concat[Option[T]](2))
      val broadcast = builder.add(Broadcast[T](2))

      val shouldCompleteFlow = Flow[T].filter(decider)
      val toOnceNoneFlow = Flow[T].take(1).map(_ => None)

      toSomeFlow ~> concatFinished ~> fromOptionFlow ~> flow                 ~> broadcast
                    concatFinished <~ toOnceNoneFlow <~ shouldCompleteFlow   <~ broadcast.out(1)

      FlowShape(toSomeFlow.in, broadcast.out(0))
    })

  protected def singleRequest(request: HttpRequest): Future[HttpResponse] = http.singleRequest(request)
}

object BoxStreamBase {
  val pattern: Regex = """(?:([A-Za-z]*)://)?([^\:|/]+)?:?([0-9]+)?.*""".r
}
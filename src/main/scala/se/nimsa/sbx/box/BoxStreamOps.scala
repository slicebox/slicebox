package se.nimsa.sbx.box

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, MergePreferred, RunnableGraph, Sink}
import se.nimsa.sbx.box.BoxProtocol.{Box, OutgoingTransactionImage}
import se.nimsa.sbx.log.SbxLog

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

trait BoxStreamOps {

  import BoxStreamOps._

  val box: Box
  val transferType: String
  val retryInterval: FiniteDuration
  val batchSize: Int
  val parallelism: Int

  implicit val system: ActorSystem
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext

  lazy val http = Http(system)

  def pollAndTransfer[Mat](transferBatch: () => Future[Seq[OutgoingTransactionImage]]): RunnableGraph[KillSwitch] = {
    RunnableGraph.fromGraph(GraphDSL.create(KillSwitches.single[Tick]) {
      implicit b =>
        switch =>

          import GraphDSL.Implicits._

          val ticker = scaladsl.Source.tick(retryInterval, retryInterval, tick)
            .detach

          val merge = b.add(MergePreferred[Tick](1))
          val bcast = b.add(Broadcast[Try[Seq[OutgoingTransactionImage]]](2))

          val tryBatch = (_: Tick) => transferBatch().map(Success.apply).recover { case t: Throwable => Failure(t) }
          val batchFlow = Flow[Tick].mapAsync(1)(tryBatch)

          val onlineAndTransferring = Flow[Try[Seq[OutgoingTransactionImage]]]
            .collect {
              case Success(images) if images.nonEmpty => tick
            }

          val failedBatchPath = Flow[Try[Seq[OutgoingTransactionImage]]]
            .collect {
              case f: Failure[_] => f
            }
            .statefulMapConcat {
              () =>
                var lastExceptionTimestamp = 0L

              {
                case Failure(exception) =>
                  val now = System.currentTimeMillis
                  val duration = now - lastExceptionTimestamp
                  lastExceptionTimestamp = now
                  if (duration > (retryInterval.toMillis * 1.2).toLong)
                    Failure(exception) :: Nil
                  else
                    Nil
              }
            }
            .map { failure =>
              failure.exception match {
                case e: TransactionException =>
                  SbxLog.warn("Box", s"Connection to ${box.name} ($transferType) failed for image ${e.transactionImage.image.imageId}: ${e.getMessage}, cause: ${if (e.getCause != null) e.getCause.getMessage else "none"}. Retrying later.")
                case t: Throwable =>
                  SbxLog.warn("Box", s"Connection to ${box.name} ($transferType) failed: ${t.getMessage}. Retrying later.")
              }
              failure
            }

          ticker ~> merge.in(0)
                    merge           ~> switch ~> batchFlow   ~> bcast
                    merge.preferred <~ onlineAndTransferring <~ bcast
                                                                bcast ~> failedBatchPath ~> Sink.ignore

          ClosedShape
    })
  }

  def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
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

  def checkResponse(responseImage: ResponseImage): (HttpResponse, OutgoingTransactionImage) = {
    responseImage match {
      case (Success(response), transactionImage) =>
        response.status.intValue match {
          case status if status >= 200 && status < 300 => (response, transactionImage)
          case status if status == 400 =>
            response.discardEntityBytes()
            SbxLog.warn("Box", s"${box.name} ($transferType): Ignoring rejected image ${transactionImage.image.imageId}")
            (response, transactionImage)
          case status if status == 404 =>
            response.discardEntityBytes()
            SbxLog.warn("Box", s"${box.name} ($transferType): Ignoring removed image ${transactionImage.image.imageId}")
            (response, transactionImage)
          case _ =>
            response.discardEntityBytes()
            throw new TransactionException(transactionImage, s"Connection to ${box.name} ($transferType) failed for image ${transactionImage.image.imageId}", null)
        }
      case (Failure(exception), transactionImage) => throw new TransactionException(transactionImage, s"Connection to ${box.name} ($transferType) failed for image ${transactionImage.image.id}", exception)
    }
  }

  def singleRequest(request: HttpRequest): Future[HttpResponse] = http.singleRequest(request)
}

object BoxStreamOps {

  type RequestImage = (HttpRequest, OutgoingTransactionImage)
  type ResponseImage = (Try[HttpResponse], OutgoingTransactionImage)

  class TransactionException(val transactionImage: OutgoingTransactionImage, message: String, cause: Throwable) extends Exception(message, cause)

  case class Tick()

  val tick = Tick()

  val pattern: Regex = """(?:([A-Za-z]*)://)?([^\:|/]+)?:?([0-9]+)?.*""".r

  val indexInTransaction: () => OutgoingTransactionImage => List[(OutgoingTransactionImage, Long)] = () => {
    val indices = mutable.Map.empty[Long, Long]

    (transactionImage: OutgoingTransactionImage) => {
      val key = transactionImage.transaction.id
      val index = indices.getOrElse(key, transactionImage.transaction.sentImageCount) + 1
      indices(key) = index
      (transactionImage, index) :: Nil
    }
  }
}
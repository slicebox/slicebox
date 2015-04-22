package se.nimsa.sbx.app.routing

import akka.pattern.ask
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.routing._
import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.DicomAnonymization

trait RemoteBoxRoutes { this: RestApi =>

  def remoteBoxRoutes: Route =
    pathPrefix("box") {
      pathPrefix(Segment) { token =>

        onSuccess(boxService.ask(ValidateToken(token))) {
          case InvalidToken(token) =>
            complete((Unauthorized, "Invalid token"))
          case ValidToken(token) =>
            path("image") {
              parameters('transactionid.as[Long], 'sequencenumber.as[Long], 'totalimagecount.as[Long]) { (transactionId, sequenceNumber, totalImageCount) =>
                post {
                  // make sure spray.httpx.SprayJsonSupport._ is NOT imported here. It messes with the content type expectations
                  entity(as[Array[Byte]]) { imageData =>
                    val dataset = DicomUtil.loadDataset(imageData, true)
                    onSuccess(dicomService.ask(AddDataset(dataset))) {
                      case ImageAdded(image) =>
                        onSuccess(boxService.ask(UpdateInbox(token, transactionId, sequenceNumber, totalImageCount))) {
                          case msg: InboxUpdated =>
                            complete(NoContent)
                        }
                    }
                  }
                }
              }
            } ~ pathPrefix("outbox") {
              path("poll") {
                get {
                  onSuccess(boxService.ask(PollOutbox(token))) {
                    case outboxEntry: OutboxEntry =>
                      import spray.httpx.SprayJsonSupport._
                      complete(outboxEntry)
                    case OutboxEmpty =>
                      complete(NotFound)
                  }
                }
              } ~ path("done") {
                post {
                  import spray.httpx.SprayJsonSupport._
                  entity(as[OutboxEntry]) { outboxEntry =>
                    onSuccess(boxService.ask(DeleteOutboxEntry(token, outboxEntry.transactionId, outboxEntry.sequenceNumber))) {
                      case OutboxEntryDeleted => complete(NoContent)
                    }
                  }
                }
              } ~ pathEndOrSingleSlash {
                get {
                  parameters('transactionid.as[Long], 'sequencenumber.as[Long]) { (transactionId, sequenceNumber) =>
                    onSuccess(boxService.ask(GetOutboxEntry(token, transactionId, sequenceNumber))) {
                      case outboxEntry: OutboxEntry =>
                        onSuccess(boxService.ask(GetTransactionTagValues(outboxEntry.imageFileId, transactionId)).mapTo[Seq[TransactionTagValue]]) {
                          case transactionTagValues =>
                            onSuccess(dicomService.ask(GetImageFile(outboxEntry.imageFileId))) {
                              case imageFile: ImageFile =>
                                detach() {
                                  val path = storage.resolve(imageFile.fileName.value)
                                  val dataset = DicomUtil.loadDataset(path, true)
                                  val anonymizedDataset = DicomAnonymization.anonymizeDataset(dataset)
                                  DicomUtil.applyTagValues(anonymizedDataset, transactionTagValues)
                                  val bytes = DicomUtil.toByteArray(anonymizedDataset)
                                  complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
                                }
                            }
                        }
                      case OutboxEntryNotFound =>
                        complete(NotFound)
                    }
                  }
                }
              }
            }
        }
      }
    }

}
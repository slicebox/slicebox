package se.vgregion.app.routing

import akka.pattern.ask

import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.AuthInfo
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole
import se.vgregion.box.BoxProtocol._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.dicom.DicomUtil

trait BoxRoutes { this: RestApi =>

  def boxRoutes(authInfo: AuthInfo): Route =
    pathPrefix("boxes") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetBoxes)) {
            case Boxes(boxes) =>
              complete(boxes)
          }
        }
      } ~ authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
        path("generatebaseurl") {
          post {
            entity(as[RemoteBoxName]) { remoteBoxName =>
              onSuccess(boxService.ask(GenerateBoxBaseUrl(remoteBoxName.value))) {
                case BoxBaseUrlGenerated(baseUrl) =>
                  complete(BoxBaseUrl(baseUrl))
              }
            }
          }
        } ~ path("addremotebox") {
          post {
            entity(as[RemoteBox]) { remoteBox =>
              onSuccess(boxService.ask(AddRemoteBox(remoteBox))) {
                case RemoteBoxAdded(box) =>
                  complete(box)
              }
            }
          }
        } ~ path(LongNumber) { boxId =>
          delete {
            onSuccess(boxService.ask(RemoveBox(boxId))) {
              case BoxRemoved(boxId) =>
                complete(NoContent)
            }
          }
        }
      } ~ path(LongNumber / "sendpatients") { remoteBoxId =>
        post {
          entity(as[Seq[Long]]) { patientIds =>
            onSuccess(dicomService.ask(GetImageFilesForPatients(patientIds))) {
              case ImageFiles(imageFiles) => onSuccess(boxService.ask(SendImagesToRemoteBox(remoteBoxId, imageFiles.map(_.id)))) {
                case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
              }
            }
          }
        }
      } ~ path(LongNumber / "sendstudies") { remoteBoxId =>
        post {
          entity(as[Seq[Long]]) { studyIds =>
            onSuccess(dicomService.ask(GetImageFilesForStudies(studyIds))) {
              case ImageFiles(imageFiles) => onSuccess(boxService.ask(SendImagesToRemoteBox(remoteBoxId, imageFiles.map(_.id)))) {
                case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
              }
            }
          }
        }
      } ~ path(LongNumber / "sendseries") { remoteBoxId =>
        post {
          entity(as[Seq[Long]]) { seriesIds =>
            onSuccess(dicomService.ask(GetImageFilesForSeries(seriesIds))) {
              case ImageFiles(imageFiles) => onSuccess(boxService.ask(SendImagesToRemoteBox(remoteBoxId, imageFiles.map(_.id)))) {
                case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
              }
            }
          }
        }
      } ~ path(LongNumber / "sendimages") { remoteBoxId =>
        post {
          entity(as[Seq[Long]]) { imageIds =>
            onSuccess(boxService.ask(SendImagesToRemoteBox(remoteBoxId, imageIds))) {
              case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
            }
          }
        }
      } ~ tokenRoutes
    }

  def tokenRoutes: Route =
    pathPrefix(Segment) { token =>

      onSuccess(boxService.ask(ValidateToken(token))) {
        case InvalidToken(token) =>
          complete((Forbidden, "Invalid token"))
        case ValidToken(token) =>

          pathPrefix("image") {
            path(LongNumber / LongNumber / LongNumber) { (transactionId, sequenceNumber, totalImageCount) =>
              post {
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
                    complete(outboxEntry)
                  case OutboxEmpty =>
                    complete(NotFound)
                }
              }
            } ~ path("done") {
              post {
                entity(as[OutboxEntry]) { outboxEntry =>
                  onSuccess(boxService.ask(DeleteOutboxEntry(token, outboxEntry.transactionId, outboxEntry.sequenceNumber))) {
                    case OutboxEntryDeleted => complete(NoContent)
                  }
                }
              }
            } ~ pathEndOrSingleSlash {
              get {
                parameters('transactionId.as[Long], 'sequenceNumber.as[Long]) { (transactionId, sequenceNumber) =>
                  onSuccess(boxService.ask(GetOutboxEntry(token, transactionId, sequenceNumber))) {
                    case outboxEntry: OutboxEntry =>
                      onSuccess(dicomService.ask(GetImageFile(outboxEntry.imageId))) {
                        case imageFile: ImageFile =>
                          val path = storage.resolve(imageFile.fileName.value)
                          val bytes = DicomUtil.toAnonymizedByteArray(path)
                          detach() {
                            complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
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

  def inboxRoutes: Route =
    pathPrefix("inbox") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetInbox)) {
            case Inbox(entries) =>
              complete(entries)
          }
        }
      }
    }

  def outboxRoutes: Route =
    pathPrefix("outbox") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetOutbox)) {
            case Outbox(entries) =>
              complete(entries)
          }
        }
      } ~ path(LongNumber) { outboxEntryId =>
        delete {
          onSuccess(boxService.ask(RemoveOutboxEntry(outboxEntryId))) {
            case OutboxEntryRemoved(outboxEntryId) =>
              complete(NoContent)
          }
        }
      }
    }

}
package se.vgregion.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorContext
import akka.pattern.ask
import akka.util.Timeout
import se.vgregion.box.BoxProtocol._
import se.vgregion.box.BoxServiceActor
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.log.LogProtocol._
import se.vgregion.app.UserRepositoryDbProtocol._
import se.vgregion.dicom.DicomUtil
import spray.http.ContentTypes
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.PlayTwirlSupport.twirlHtmlMarshaller
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import se.vgregion.log.LogServiceActor
import spray.http.MediaTypes

class RestInterface extends Actor with RestApi {

  def actorRefFactory = context

  def dbUrl = "jdbc:h2:storage"

  def createStorageDirectory = {
    val storagePath = Paths.get(sliceboxConfig.getString("storage"))
    if (!Files.exists(storagePath))
      Files.createDirectories(storagePath)
    if (!Files.isDirectory(storagePath))
      throw new IllegalArgumentException("Storage directory is not a directory.")
    storagePath
  }

  def receive = runRoute(routes)

}

trait RestApi extends HttpService with JsonFormats {

  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(70.seconds)

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")

  def createStorageDirectory(): Path
  def dbUrl(): String

  def db = Database.forURL(dbUrl,
    user = sliceboxConfig.getString("db.user"),
    password = sliceboxConfig.getString("db.password"),
    driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = createStorageDirectory()
  
  val host = config.getString("http.host")
  val port = config.getInt("http.port")
  val apiBaseURL = s"http://$host:$port/api"

  val userService = actorRefFactory.actorOf(UserRepositoryDbActor.props(dbProps), "UserService")
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, storage, apiBaseURL), "BoxService")
  val dicomService = actorRefFactory.actorOf(DicomDispatchActor.props(storage, dbProps), "DicomDispatch")
  val logService = actorRefFactory.actorOf(LogServiceActor.props(dbProps), "LogService")

  val authenticator = new Authenticator(userService)

  implicit def sliceboxExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest, "Illegal arguments: " + e.getMessage()))
    }

  def staticResourcesRoutes =
    get {
      pathPrefix("assets") {
        path(Rest) { path =>
          getFromResource("public/" + path)
        }
      }
    }

  def angularRoutes =
    get {
      pathPrefix("") {
        complete(views.html.index())
      }
    }

  def directoryRoutes: Route =
    pathPrefix("directorywatches") {
      pathEnd {
        get {
          onSuccess(dicomService.ask(GetWatchedDirectories)) {
            case WatchedDirectories(directories) =>
              complete(directories)
          }
        } ~ post {
          entity(as[WatchDirectory]) { directory =>
            onSuccess(dicomService.ask(directory)) {
              case DirectoryWatched(path) =>
                complete("Now watching directory " + path)
            }
          }
        }
      } ~ path(LongNumber) { watchDirectoryId =>
        delete {
          onSuccess(dicomService.ask(UnWatchDirectory(watchDirectoryId))) {
            case DirectoryUnwatched(watchedDirectoryId) =>
              complete("Stopped watching directory " + watchedDirectoryId)
          }
        }
      }
    }

  def scpRoutes: Route =
    pathPrefix("scps") {
      pathEnd {
        get {
          onSuccess(dicomService.ask(GetScps)) {
            case Scps(scps) =>
              complete(scps)
          }
        } ~ post {
          entity(as[AddScp]) { addScp =>
            onSuccess(dicomService.ask(addScp)) {
              case ScpAdded(scpData) =>
                complete(scpData)
            }
          }
        }
      } ~ path(LongNumber) { scpDataId =>
        delete {
          onSuccess(dicomService.ask(RemoveScp(scpDataId))) {
            case ScpRemoved(scpDataId) =>
              complete(NoContent)
          }
        }
      }
    }

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      pathPrefix("patients") {
        pathEnd {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?) { (startIndex, count, orderBy, orderAscending, filter) =>

                onSuccess(dicomService.ask(GetPatients(startIndex, count, orderBy, orderAscending, filter))) {
                  case Patients(patients) =>
                    complete(patients)
                }
              }
          }
        } ~ path(LongNumber) { patientId =>
          delete {
            onSuccess(dicomService.ask(DeletePatient(patientId))) {
              case ImageFilesDeleted(_) =>
                complete(NoContent)
            }
          }
        }
      } ~ pathPrefix("studies") {
        pathEnd {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'patientId.as[Long]) { (startIndex, count, patientId) =>
                onSuccess(dicomService.ask(GetStudies(startIndex, count, patientId))) {
                  case Studies(studies) =>
                    complete(studies)
                }
              }
          }
        } ~ path(LongNumber) { studyId =>
          delete {
            onSuccess(dicomService.ask(DeleteStudy(studyId))) {
              case ImageFilesDeleted(_) =>
                complete(NoContent)
            }
          }
        }
      } ~ pathPrefix("series") {
        pathEnd {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'studyId.as[Long]) { (startIndex, count, studyId) =>
                onSuccess(dicomService.ask(GetSeries(startIndex, count, studyId))) {
                  case SeriesCollection(series) =>
                    complete(series)
                }
              }
          } ~ get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?) { (startIndex, count, orderBy, orderAscending, filter) =>
                onSuccess(dicomService.ask(GetFlatSeries(startIndex, count, orderBy, orderAscending, filter))) {
                  case FlatSeriesCollection(flatSeries) =>
                    complete(flatSeries)
                }
              }
          }
        } ~ path(LongNumber) { seriesId =>
          delete {
            onSuccess(dicomService.ask(DeleteSeries(seriesId))) {
              case ImageFilesDeleted(_) =>
                complete(NoContent)
            }
          }
        }
      } ~ path("images") {
        get {
          parameters('seriesId.as[Long]) { seriesId =>
            onSuccess(dicomService.ask(GetImages(seriesId))) {
              case Images(images) =>
                complete(images)
            }
          }
        }
      }
    }
  }

  def seriesRoutes: Route =
    pathPrefix("series") {
      path("datasets") {
        get {
          parameters('seriesId.as[Long]) { seriesId =>
            onSuccess(dicomService.ask(GetImages(seriesId))) {
              case Images(images) =>
                val imageURLs =
                  images.map(image => SeriesDataset(image.id, s"$apiBaseURL/images/${image.id}"))
                  
                complete(imageURLs)
            }
          }
        }
      }
    }
  
  def imageRoutes: Route =
    pathPrefix("images") {
      pathEnd {
        post {
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
            onSuccess(dicomService.ask(AddDataset(dataset))) {
              case ImageAdded(image) =>
                complete(image)
            }
          }
        }
      } ~ path(LongNumber) { imageId =>
        get {
          onSuccess(dicomService.ask(GetImageFile(imageId))) {
            case imageFile: ImageFile =>
              val file = storage.resolve(imageFile.fileName.value).toFile
              if (file.isFile && file.canRead)
                detach() {
                  complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(file)))
                }
              else
                complete((BadRequest, "Dataset could not be read"))
          }
        }
      } ~ path(LongNumber / "attributes") { imageId =>
        get {
          onSuccess(dicomService.ask(GetImageAttributes(imageId))) {
            case ImageAttributes(attributes) =>
              complete(attributes)
          }
        }
      } ~ path(LongNumber / "imageinformation") { imageId =>
        get {
          onSuccess(dicomService.ask(GetImageInformation(imageId))) {
            case info: ImageInformation =>
              complete(info)
          }
        }
      } ~ path(LongNumber / "png") { imageId =>
        parameters('framenumber.as[Int] ? 1, 'windowmin.as[Int] ? 0, 'windowmax.as[Int] ? 0, 'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
          get {
            onSuccess(dicomService.ask(GetImageFrame(imageId, frameNumber, min, max, height))) {
              case ImageFrame(bytes) =>
                complete(HttpEntity(MediaTypes.`image/png`, HttpData(bytes)))
            }
          }
        }
      }
    }

  def boxRoutes: Route =
    pathPrefix("boxes") {
      pathEnd {
        get {
          onSuccess(boxService.ask(GetBoxes)) {
            case Boxes(boxes) =>
              complete(boxes)
          }
        }
      } ~ path("generatebaseurl") {
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
            } ~ pathEnd {
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
      pathEnd {
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
      pathEnd {
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

  def logRoutes: Route =
    pathPrefix("log") {
      pathEnd {
        get {
          parameters('startindex.as[Long] ? 0, 'count.as[Long] ? 20) { (startIndex, count) =>
            onSuccess(logService.ask(GetLogEntries(startIndex, count))) {
              case LogEntries(logEntries) =>
                complete(logEntries)
            }
          }
        }
      }
    }

  def userRoutes: Route =
    pathPrefix("users") {
      pathEnd {
        post {
          entity(as[ClearTextUser]) { user =>
            val apiUser = ApiUser(-1, user.user, user.role).withPassword(user.password)
            onSuccess(userService.ask(AddUser(apiUser))) {
              case UserAdded(user) =>
                complete(user)
            }
          }
        }
      } ~ pathEnd {
        get {
          onSuccess(userService.ask(GetUsers)) {
            case Users(users) =>
              complete(users)
          }
        }
      } ~ path(LongNumber) { userId =>
        delete {
          onSuccess(userService.ask(DeleteUser(userId))) {
            case UserDeleted(userId) =>
              complete(NoContent)
          }
        }
      }
    } ~ pathPrefix("private") {
      authenticate(authenticator.basicUserAuthenticator) { authInfo =>
        path("test") {
          get {
            complete(s"Hi, ${authInfo.user.user}")
          }
        } ~ path("admin") {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            get {
              complete(s"Admin: ${authInfo.user.user}")
            }
          }
        }

      }
    }

  def systemRoutes: Route =
    path("stop") {
      (post | parameter('method ! "post")) {
        complete {
          var system = actorRefFactory.asInstanceOf[ActorContext].system
          system.scheduler.scheduleOnce(1.second)(system.shutdown())(system.dispatcher)
          "Shutting down in 1 second..."
        }
      }
    }

  def routes: Route =
    pathPrefix("api") {
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ imageRoutes ~ seriesRoutes ~ boxRoutes ~ userRoutes ~ inboxRoutes ~ outboxRoutes ~ logRoutes ~ systemRoutes
    } ~ staticResourcesRoutes ~ angularRoutes

}


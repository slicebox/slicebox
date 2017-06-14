package se.nimsa.sbx.storage

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.dicom.DicomHierarchy.Image

import scala.concurrent.{ExecutionContext, Future}

class RuntimeStorage extends StorageService {

  import scala.collection.mutable

  val storage = mutable.Map.empty[String, ByteString]

  override def deleteFromStorage(name: String): Unit = storage.remove(name)

  def clear() = storage.clear()

  override def move(sourceImageName: String, targetImageName: String) =
    storage.get(sourceImageName).map { sourceBytes =>
      storage.remove(sourceImageName)
      storage(targetImageName) = sourceBytes
      Unit
    }.getOrElse {
      throw new RuntimeException(s"Dicom data not found for key $sourceImageName")
    }

  override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] =
    Sink.reduce[ByteString](_ ++ _)
      .mapMaterializedValue {
        _.map {
          bytes =>
            storage(name) = bytes
            Done
        }
      }

  override def fileSource(image: Image): Source[ByteString, NotUsed] = Source.single(storage(imageName(image)))

}

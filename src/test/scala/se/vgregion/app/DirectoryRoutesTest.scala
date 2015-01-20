package se.vgregion.app

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.dicom.DicomHierarchy.Image
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.BadRequest
import spray.httpx.SprayJsonSupport._

class DirectoryRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:directoryroutestest;DB_CLOSE_DELAY=-1"

  val tempDir = Files.createTempDirectory("slicebox-watch-dir-")
  val watchDir = WatchDirectory(tempDir.toString)
  val tempFile = Files.createTempFile("slicebox-temp-file-", ".tmp")
  val watchFile = WatchDirectory(tempFile.toString)
  val watchStorage = WatchDirectory(storage.toString)

  "The system" should "return a monitoring message when asked to watch a new directory" in {
    Post("/api/directory", watchDir) ~> routes ~> check {
      responseAs[String] should be(s"Now watching directory $tempDir")
    }
  }

  it should "respond with BadRequest when asking to watch a path which is not a directory" in {
    Post("/api/directory", watchFile) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "respond with BadRequest when asking to watch the storage directory" in {
    Post("/api/directory", watchStorage) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return an empty list of images when watching an empty directory and return one image after a file has been copied to that directory" in {
    Get("/api/metadata/allimages") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].size should be(0)
    }

    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    // just sleep for a tiny bit and let the OS find out there was a new file in the watched directory. It will be picked up and put in the database
    Thread.sleep(1000)

    Get("/api/metadata/allimages") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].size should be(1)
    }
  }

  it should "return a list of one directory when listing watched directories" in {
    Get("/api/directory/list") ~> routes ~> check {
      responseAs[List[String]].size should be(1)
    }
  }

  it should "be possible to remove a watched directory" in {
    Delete("/api/directory", watchDir) ~> routes ~> check {
      responseAs[String] should be(s"Stopped watching directory $tempDir")
    }
  }
}
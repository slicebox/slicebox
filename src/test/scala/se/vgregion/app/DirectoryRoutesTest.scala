package se.vgregion.app

import java.nio.file.Files
import java.nio.file.Paths

import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.util.TestUtil

class DirectoryRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:directoryroutestest;DB_CLOSE_DELAY=-1"

  val tempDir = Files.createTempDirectory("slicebox-watch-dir-")
  val watchDir = WatchDirectory(tempDir.toString)
  val tempFile = Files.createTempFile("slicebox-temp-file-", ".tmp")
  val watchFile = WatchDirectory(tempFile.toString)
  val watchStorage = WatchDirectory(storage.toString)

  override def afterAll {
    super.afterAll();
    TestUtil.deleteFolder(tempDir)
    Files.delete(tempFile)
  }

  "The system" should "return a monitoring message when asked to watch a new directory" in {
    Post("/api/directorywatches", watchDir) ~> routes ~> check {
      responseAs[String] should be(s"Now watching directory $tempDir")
    }
  }

  it should "respond with BadRequest when asking to watch a path which is not a directory" in {
    Post("/api/directorywatches", watchFile) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "respond with BadRequest when asking to watch the storage directory" in {
    Post("/api/directorywatches", watchStorage) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return an empty list of patients when watching an empty directory and return one patient after a file has been copied to that directory" in {
    Get("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(0)
    }

    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    // sleep for a while and let the OS find out there was a new file in the watched directory. It will be picked up by slicebox
    Thread.sleep(1000)
    
    println(s"Number of files: ${tempDir.toFile().listFiles().length}");

    Get("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(1)
    }
  }

  it should "not pick up a secondary capture file (unsupported SOP Class)" in {

    val fileName = "cat.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    // sleep for a while and let the OS find out there was a new file in the watched directory. It will be picked up by slicebox
    Thread.sleep(1000)

    Get("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(1)
    }
  }

  it should "return a list of one directory when listing watched directories" in {
    Get("/api/directorywatches") ~> routes ~> check {
      responseAs[List[WatchedDirectory]].size should be (1)
    }
  }

  it should "be possible to remove a watched directory" in {
    // TODO: this doesn't test that the watched directory is actually removed from db and that actor is stopped, it only tests that the request can be handled
    Delete("/api/directorywatches/1", watchDir) ~> routes ~> check {
      responseAs[String] should be ("Stopped watching directory 1")
    }
  }
}
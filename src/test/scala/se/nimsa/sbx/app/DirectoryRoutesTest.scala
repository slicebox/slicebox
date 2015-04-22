package se.nimsa.sbx.app

import java.nio.file.Files
import java.nio.file.Paths

import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.util.TestUtil

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

  "The system" should "return 201 Created and the watched directory when asking to watch a new directory" in {
    PostAsAdmin("/api/directorywatches", watchDir) ~> routes ~> check {
      status should be (Created)
      responseAs[WatchedDirectory] should not be (null)
    }
  }

  it should "respond with BadRequest when asking to watch a path which is not a directory" in {
    PostAsAdmin("/api/directorywatches", watchFile) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "respond with BadRequest when asking to watch the storage directory" in {
    PostAsAdmin("/api/directorywatches", watchStorage) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return an empty list of patients when watching an empty directory and return one patient after a file has been copied to that directory" in {
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(0)
    }

    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    // sleep for a while and let the OS find out there was a new file in the watched directory. It will be picked up by slicebox
    Thread.sleep(1000)
    
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
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

    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(1)
    }
  }

  it should "return a list of one directory when listing watched directories" in {
    GetAsUser("/api/directorywatches") ~> routes ~> check {
      responseAs[List[WatchedDirectory]].size should be (1)
    }
  }

  it should "be possible to remove a watched directory" in {
    // TODO: this doesn't test that the watched directory is actually removed from db and that actor is stopped, it only tests that the request can be handled
    DeleteAsAdmin("/api/directorywatches/1") ~> routes ~> check {
      status should be (NoContent)
    }
  }
}
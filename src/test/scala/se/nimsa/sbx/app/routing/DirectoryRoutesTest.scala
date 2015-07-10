package se.nimsa.sbx.app.routing

import java.nio.file.Files

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.nimsa.sbx.dicom.DicomHierarchy.Patient
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.util.TestUtil
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._

class DirectoryRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:directoryroutestest;DB_CLOSE_DELAY=-1"

  val tempDir = Files.createTempDirectory("slicebox-watch-dir-")
  val watchDir = WatchDirectory("test dir", tempDir.toString)
  val watchDir2 = WatchDirectory("test dir 2", tempDir.toString)
  val tempFile = Files.createTempFile("slicebox-temp-file-", ".tmp")
  val watchFile = WatchDirectory("test file", tempFile.toString)
  val watchStorage = WatchDirectory("test storage", storage.toString)

  override def afterAll {
    super.afterAll();
    TestUtil.deleteFolder(tempDir)
    Files.delete(tempFile)
  }

  "Directory watch routes" should "return 201 Created and the watched directory when asking to watch a new directory" in {
    PostAsAdmin("/api/directorywatches", watchDir) ~> routes ~> check {
      status should be (Created)
      responseAs[WatchedDirectory] should not be (null)
    }
  }

  it should "return 201 Created and the watched directory but not add anything when adding the same directory twice" in {
    PostAsAdmin("/api/directorywatches", watchDir) ~> routes ~> check {
      status should be (Created)
      responseAs[WatchedDirectory] should not be (null)
    }
    GetAsUser("/api/directorywatches") ~> routes ~> check {
      responseAs[List[WatchedDirectory]].size should be (1)
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

  it should "respond with BadRequest when watching the same directory twice with different names" in {
    PostAsAdmin("/api/directorywatches", watchDir2) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return an empty list of patients when watching an empty directory and return one patient after a file has been copied to that directory" in {
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(0)
    }

    val dcmFile = TestUtil.testImageFile
    Files.copy(dcmFile.toPath, tempDir.resolve(dcmFile.getName))

    // sleep for a while and let the OS find out there was a new file in the watched directory. It will be picked up by slicebox
    Thread.sleep(1000)
    
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be(1)
    }
  }

  it should "not pick up a secondary capture file (unsupported SOP Class)" in {

    val scFile = TestUtil.testSecondaryCaptureFile
    Files.copy(scFile.toPath, tempDir.resolve(scFile.getName))

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
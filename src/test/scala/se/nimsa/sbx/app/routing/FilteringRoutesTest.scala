package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dicom.data.{Tag, TagTree}
import se.nimsa.sbx.app.GeneralProtocol.SourceType
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class FilteringRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("filteringroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  override def afterEach(): Unit = await(filteringDao.clear())


  val filter = TagFilter(-1, "filter name", TagFilterType.WHITELIST)
  val filterTagPath1 = TagFilterTagPath(-1, -1, TagTree.fromTag(0x00080005))
  val filterTagPath2 = TagFilterTagPath(-1, -1, TagTree.fromTag(0x00100010))

  "Filtering routes" should "return 201 Created and the created filter when creating a new filter" in {
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
      val createdFilter = responseAs[TagFilter]
      createdFilter should not be null
      createdFilter.id.toInt should be > 0
    }
  }

  it should "return 201 Created when adding more than one filter with the same name and type" in {
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
    }
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/filtering/filters") ~> routes ~> check {
      responseAs[List[TagFilter]] should have length 2
    }
  }

  it should "return 201 Created when adding more than one filter with the same name but different type" in {
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
    }
    PostAsAdmin("/api/filtering/filters", filter.copy(tagFilterType = TagFilterType.BLACKLIST)) ~> routes ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/filtering/filters") ~> routes ~> check {
      responseAs[List[TagFilter]] should have length 2
    }
  }

  it should "return 200 and a list of filters when listing filters" in {
    val addedFilter = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilter]
    }
    GetAsUser("/api/filtering/filters") ~> routes ~> check {
      status shouldBe OK
      val rules = responseAs[List[TagFilter]]
      rules should not be empty
      rules.length shouldBe 1
      rules.head shouldBe addedFilter
    }
  }

  it should "return 204 No Content and remove the referenced filter when deleting a filter" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilter]
    }
    val addedFilter2 = PostAsAdmin("/api/filtering/filters", filter.copy(name = "Something else")) ~> routes ~> check {
      responseAs[TagFilter]
    }
    DeleteAsAdmin(s"/api/filtering/filters/${addedFilter1.id}") ~> routes ~> check {
      status shouldBe NoContent
    }
    val filters = GetAsUser("/api/filtering/filters") ~> routes ~> check {
      responseAs[List[TagFilter]]
    }
    filters.length shouldBe 1
    filters.head shouldBe addedFilter2
  }

  it should "return 200 OK and a list of tag paths for a tag filter" in {
    val addedFilter = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilter]
    }
    val tagPath = TagFilterTagPath(-1, addedFilter.id, TagTree.fromTag(Tag.PatientName))
    val addedTagPath1 = PostAsAdmin(s"/api/filtering/filters/${addedFilter.id}/tagpaths", tagPath) ~> routes ~> check {
      status shouldBe Created
      responseAs[TagFilterTagPath]
    }
    val addedTagPath2 = PostAsAdmin(s"/api/filtering/filters/${addedFilter.id}/tagpaths", tagPath) ~> routes ~> check {
      responseAs[TagFilterTagPath]
    }
    GetAsUser(s"/api/filtering/filters/${addedFilter.id}/tagpaths") ~> routes ~> check {
      status shouldBe OK
      responseAs[Seq[TagFilterTagPath]] shouldBe Seq(addedTagPath1, addedTagPath2)
    }
    GetAsUser(s"/api/filtering/filters/${addedFilter.id}/tagpaths?startindex=1") ~> routes ~> check {
      responseAs[Seq[TagFilterTagPath]] shouldBe Seq(addedTagPath2)
    }
    GetAsUser(s"/api/filtering/filters/${addedFilter.id}/tagpaths?count=1") ~> routes ~> check {
      responseAs[Seq[TagFilterTagPath]] shouldBe Seq(addedTagPath1)
    }
  }

  it should "return 204 No Content and remove tag path for filter" in {
    val addedFilter = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilter]
    }
    val tagPath = TagFilterTagPath(-1, addedFilter.id, TagTree.fromTag(Tag.PatientName))
    val addedTagPath1 = PostAsAdmin(s"/api/filtering/filters/${addedFilter.id}/tagpaths", tagPath) ~> routes ~> check {
      responseAs[TagFilterTagPath]
    }
    val addedTagPath2 = PostAsAdmin(s"/api/filtering/filters/${addedFilter.id}/tagpaths", tagPath) ~> routes ~> check {
      responseAs[TagFilterTagPath]
    }
    GetAsUser(s"/api/filtering/filters/${addedFilter.id}/tagpaths") ~> routes ~> check {
      responseAs[Seq[TagFilterTagPath]] shouldBe Seq(addedTagPath1, addedTagPath2)
    }
    DeleteAsAdmin(s"/api/filtering/filters/${addedFilter.id}/tagpaths/${addedTagPath1.id}") ~> routes ~> check {
      status shouldBe NoContent
    }
    GetAsUser(s"/api/filtering/filters/${addedFilter.id}/tagpaths") ~> routes ~> check {
      responseAs[Seq[TagFilterTagPath]] shouldBe Seq(addedTagPath2)
    }
  }

  it should "return 201 Created when associating a Source with an existing filter" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilter]
    }
    val sourceTagFilter = SourceTagFilter(-1, SourceType.IMPORT, "my import", 1, addedFilter1.id, addedFilter1.name)
    val addedSourceTagFilter = PostAsAdmin("/api/filtering/associations", sourceTagFilter) ~> routes ~> check {
      status shouldBe Created
      responseAs[SourceTagFilter]
    }
    val associations = GetAsUser("/api/filtering/associations") ~> routes ~> check {
      responseAs[List[SourceTagFilter]]
    }
    associations.length shouldBe 1
    associations.head shouldBe addedSourceTagFilter
  }

  it should "return 204 No Content and remove the Source Filter association when deleting an association" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilter]
    }
    val addedSourceTagFilter1 = PostAsAdmin("/api/filtering/associations", SourceTagFilter(-1, SourceType.SCP, "my scp", 1, addedFilter1.id, addedFilter1.name)) ~> routes ~> check {
      status shouldBe Created
      responseAs[SourceTagFilter]
    }
    val addedSourceTagFilter2 = PostAsAdmin("/api/filtering/associations", SourceTagFilter(-1, SourceType.BOX, "my box", 1, addedFilter1.id, addedFilter1.name)) ~> routes ~> check {
      status shouldBe Created
      responseAs[SourceTagFilter]
    }
    DeleteAsAdmin(s"/api/filtering/associations/${addedSourceTagFilter1.id}") ~> routes ~> check {
      status shouldBe NoContent
    }
    val associations = GetAsUser("/api/filtering/associations") ~> routes ~> check {
      responseAs[List[SourceTagFilter]]
    }
    associations.length shouldBe 1
    associations.head shouldBe addedSourceTagFilter2
    DeleteAsAdmin(s"/api/filtering/filters/${addedFilter1.id}") ~> routes ~> check {
      status shouldBe NoContent
    }
    GetAsUser("/api/filtering/associations") ~> routes ~> check {
      val res = responseAs[List[SourceTagFilter]]
      res.length shouldBe 0
    }
  }
}

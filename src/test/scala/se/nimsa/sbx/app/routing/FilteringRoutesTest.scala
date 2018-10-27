package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dicom.data.TagPath
import se.nimsa.sbx.app.GeneralProtocol.SourceType
import se.nimsa.sbx.filtering.FilteringProtocol.{SourceTagFilter, TagFilterSpec, TagFilterType}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class FilteringRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("filteringroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  override def afterEach() = await(filteringDao.clear())

  val filter = TagFilterSpec(-1, "filter name", TagFilterType.WHITELIST, Seq(TagPath.fromTag(0x00080005), TagPath.fromTag(0x00100010)))

  "Filtering routes" should "return 201 Created and the created filter when creating a new filter" in {
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
      val createdRule = responseAs[TagFilterSpec]
      createdRule should not be null
      createdRule.id.toInt should be > 0
    }
  }

  it should "return 201 Created when adding an already added filter" in {
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
    }
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/filtering/filters") ~> routes ~> check {
      responseAs[List[TagFilterSpec]] should have length 1
    }
  }

  it should "return 201 Created when adding an already added filter but with different TagFilterType" in {
    PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      status shouldBe Created
    }
    PostAsAdmin("/api/filtering/filters", filter.copy(tagFilterType = TagFilterType.BLACKLIST)) ~> routes ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/filtering/filters") ~> routes ~> check {
      responseAs[List[TagFilterSpec]] should have length 1
    }
  }

  it should "return 200 and a list of filters when listing filters" in {
    val addedRule = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    GetAsUser("/api/filtering/filters") ~> routes ~> check {
      status shouldBe OK
      val rules = responseAs[List[TagFilterSpec]]
      rules should not be empty
      rules.length shouldBe 1
      rules.head shouldBe addedRule.copy(tags = Seq())
    }
  }

  it should "return 204 No Content and remove the referenced filter when deleting a filter" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    val addedFilter2 = PostAsAdmin("/api/filtering/filters", filter.copy(name = "Something else", tags = Seq())) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    DeleteAsAdmin(s"/api/filtering/filters/${addedFilter1.id}") ~> routes ~> check {
      status shouldBe NoContent
    }
    val filters = GetAsUser("/api/filtering/filters") ~> routes ~> check {
      responseAs[List[TagFilterSpec]]
    }
    filters.length shouldBe 1
    filters.head shouldBe addedFilter2
  }

  it should "return 201 Created when associating a Source with an existing filter" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    val sourceTagFilter = SourceTagFilter(-1, SourceType.IMPORT, 1, addedFilter1.id)
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

  it should "return 201 Created when updating an association for a Source" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    val addedFilter2 = PostAsAdmin("/api/filtering/filters", filter.copy(name = "second filter")) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    val sourceTagFilter = SourceTagFilter(-1, SourceType.IMPORT, 1, addedFilter1.id)
    val addedSourceTagFilter1 = PostAsAdmin("/api/filtering/associations", sourceTagFilter) ~> routes ~> check {
      status shouldBe Created
      responseAs[SourceTagFilter]
    }
    val associations1 = GetAsUser("/api/filtering/associations") ~> routes ~> check {
      responseAs[List[SourceTagFilter]]
    }
    associations1.length shouldBe 1
    associations1.head shouldBe addedSourceTagFilter1

    val addedSourceTagFilter2 = PostAsAdmin("/api/filtering/associations", sourceTagFilter.copy(tagFilterId = addedFilter2.id)) ~> routes ~> check {
      status shouldBe Created
      responseAs[SourceTagFilter]
    }
    val associations2 = GetAsUser("/api/filtering/associations") ~> routes ~> check {
      responseAs[List[SourceTagFilter]]
    }
    associations2.length shouldBe 1
    associations2.head shouldBe addedSourceTagFilter2
  }

  it should "return 204 No Content and remove the Source Filter association when deleting an association" in {
    val addedFilter1 = PostAsAdmin("/api/filtering/filters", filter) ~> routes ~> check {
      responseAs[TagFilterSpec]
    }
    val addedSourceTagFilter1 = PostAsAdmin("/api/filtering/associations", SourceTagFilter(-1, SourceType.SCP, 1, addedFilter1.id)) ~> routes ~> check {
      status shouldBe Created
      responseAs[SourceTagFilter]
    }
    val addedSourceTagFilter2 = PostAsAdmin("/api/filtering/associations", SourceTagFilter(-1, SourceType.BOX, 1, addedFilter1.id)) ~> routes ~> check {
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

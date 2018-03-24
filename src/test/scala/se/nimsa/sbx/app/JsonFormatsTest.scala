package se.nimsa.sbx.app

import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import se.nimsa.dicom.TagPath
import se.nimsa.dicom.TagPath.TagPathTag

class JsonFormatsTest extends FlatSpec with Matchers with JsonFormats {

  "The JSON representation of a tag path" should "be valid" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.prettyPrint(Json.toJson(tagPath)) shouldBe
      """{
        |  "tag" : 3,
        |  "previous" : {
        |    "tag" : 2,
        |    "item" : "*",
        |    "previous" : {
        |      "tag" : 1,
        |      "item" : "1"
        |    }
        |  }
        |}""".stripMargin
  }

  it should "work also for paths ending with a sequence" in {
    val tagPath = TagPath.fromSequence(4).thenSequence(5,6)
    Json.prettyPrint(Json.toJson(tagPath)) shouldBe
      """{
        |  "tag" : 5,
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 4,
        |    "item" : "*"
        |  }
        |}""".stripMargin
  }

  "The JSON represenation of a tag path" should "turn into an equivalent of its original object" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.fromJson[TagPathTag](Json.toJson(tagPath))(tagPathTagFormat.reads _).map {
      _ shouldBe tagPath
    }
  }

}

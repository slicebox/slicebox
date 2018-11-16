package se.nimsa.sbx.app

import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.dicom.data.TagTree.{TagTreeAnyItem, TagTreeItem, TagTreeTag}
import se.nimsa.dicom.data.{Tag, TagPath, TagTree}

class JsonFormatsTest extends FlatSpec with Matchers with JsonFormats {

  "JSON formatting of a tag path" should "be valid" in {
    val tagPath = TagPath.fromItem(Tag.PatientName, 1).thenItem(Tag.PatientName, 2).thenTag(Tag.PatientID)

    Json.prettyPrint(Json.toJson(tagPath)) shouldBe
      """{
        |  "tag" : 1048608,
        |  "name" : "PatientID",
        |  "previous" : {
        |    "tag" : 1048592,
        |    "name" : "PatientName",
        |    "item" : "2",
        |    "previous" : {
        |      "tag" : 1048592,
        |      "name" : "PatientName",
        |      "item" : "1"
        |    }
        |  }
        |}""".stripMargin
  }

  "JSON parsing of a tag path" should "work for tag paths pointing to a tag" in {
    val tagPath = TagPath.fromItem(1, 1).thenItem(2, 2).thenTag(3)

    Json.fromJson[TagPathTag](Json.toJson(tagPath)).get shouldBe tagPath
  }

  it should "work for general tag paths" in {
    val tagPath = TagPath.fromItem(1, 1).thenItem(2, 2).thenTag(3)

    Json.fromJson[TagPath](Json.toJson(tagPath)).map { tp =>
      tp shouldBe tagPath
      tp shouldBe a[TagPathTag]
    }
  }

  it should "support missing keywords" in {
    Json.fromJson[TagPath](Json.parse(
      """{
        |  "tag" : 1048608,
        |  "name" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 561685,
        |    "name" : "DerivationCodeSequence",
        |    "item" : "1"
        |  }
        |}""".stripMargin))
      .get shouldBe TagPath.fromItem(Tag.DerivationCodeSequence, 1).thenItem(Tag.PatientID, 6)
  }

  it should "support keywords instead of tag numbers" in {
    Json.fromJson[TagPath](Json.parse(
      """{
        |  "name" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "name" : "DerivationCodeSequence",
        |    "item" : "1"
        |  }
        |}""".stripMargin))
      .get shouldBe TagPath.fromItem(Tag.DerivationCodeSequence, 1).thenItem(Tag.PatientID, 6)
  }

  it should "throw error for unknown keyword and tag is not specified" in {
    Json.fromJson[TagPathTag](Json.parse(
      """{
        |  "name" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe true
  }

  it should "not throw error for unknown keyword when tag is specified" in {
    Json.fromJson[TagPathTag](Json.parse(
      """{
        |  "tag" : 1048608,
        |  "name" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe false
  }

  "JSON formatting of a tag tree" should "be valid" in {
    val tagTree = TagTree.fromItem(Tag.PatientName, 1).thenAnyItem(Tag.PatientName).thenTag(Tag.PatientID)

    Json.prettyPrint(Json.toJson(tagTree)) shouldBe
      """{
        |  "tag" : 1048608,
        |  "name" : "PatientID",
        |  "previous" : {
        |    "tag" : 1048592,
        |    "name" : "PatientName",
        |    "item" : "*",
        |    "previous" : {
        |      "tag" : 1048592,
        |      "name" : "PatientName",
        |      "item" : "1"
        |    }
        |  }
        |}""".stripMargin
  }

  it should "work also for paths ending with a sequence" in {
    val tagTree = TagTree.fromAnyItem(4).thenItem(5, 6)
    Json.prettyPrint(Json.toJson(tagTree)) shouldBe
      """{
        |  "tag" : 5,
        |  "name" : "",
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 4,
        |    "name" : "",
        |    "item" : "*"
        |  }
        |}""".stripMargin
  }

  "JSON parsing of a tag tree" should "work for tag trees pointing to a tag" in {
    val tagTree = TagTree.fromItem(1, 1).thenAnyItem(2).thenTag(3)

    Json.fromJson[TagTreeTag](Json.toJson(tagTree)).get shouldBe tagTree
  }

  it should "work for tag trees pointing to a sequence" in {
    val tagTree = TagTree.fromItem(1, 1).thenItem(2, 1)

    Json.fromJson[TagTreeItem](Json.toJson(tagTree)).get shouldBe tagTree
  }

  it should "work for tag trees pointing to all items in a sequence" in {
    val tagTree = TagTree.fromItem(1, 1).thenAnyItem(2)

    Json.fromJson[TagTreeAnyItem](Json.toJson(tagTree)).get shouldBe tagTree
  }

  it should "work for general tag trees" in {
    val tagTree = TagTree.fromItem(1, 1).thenAnyItem(2).thenTag(3)

    Json.fromJson[TagTree](Json.toJson(tagTree)).map { tp =>
      tp shouldBe tagTree
      tp shouldBe a[TagTreeTag]
    }
  }

  it should "support missing keywords" in {
    Json.fromJson[TagTree](Json.parse(
      """{
        |  "tag" : 1048608,
        |  "name" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 561685,
        |    "name" : "DerivationCodeSequence",
        |    "item" : "*"
        |  }
        |}""".stripMargin))
      .get shouldBe TagTree.fromAnyItem(Tag.DerivationCodeSequence).thenItem(Tag.PatientID, 6)
  }

  it should "support keywords instead of tag numbers" in {
    Json.fromJson[TagTree](Json.parse(
      """{
        |  "name" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "name" : "DerivationCodeSequence",
        |    "item" : "*"
        |  }
        |}""".stripMargin))
      .get shouldBe TagTree.fromAnyItem(Tag.DerivationCodeSequence).thenItem(Tag.PatientID, 6)
  }

  it should "throw error for unknown keyword and tag is not specified" in {
    Json.fromJson[TagTreeTag](Json.parse(
      """{
        |  "name" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe true
  }

  it should "not throw error for unknown keyword when tag is specified" in {
    Json.fromJson[TagTreeTag](Json.parse(
      """{
        |  "tag" : 1048608,
        |  "name" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe false
  }
}

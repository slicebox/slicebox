/*
 * Copyright 2018 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import scala.xml.{Elem, NodeSeq, XML}

object CreateSources {

  val nonAlphaNumeric = "[^a-zA-Z0-9_]"

  val part15: Elem = XML.loadFile("project/part15.xml")

  val chapters: NodeSeq = part15 \ "chapter"

  val e11: Option[NodeSeq] = chapters
    .find(_ \@ "label" == "E")
    .map(_ \ "section")
    .flatMap(_.find(_ \@ "label" == "E.1"))
    .map(_ \ "section")
    .flatMap(_.find(_ \@ "label" == "E.1.1"))
    .map(_ \ "table")
    .flatMap(_.find(_ \@ "label" == "E.1-1"))
    .map(_ \\ "tbody" \ "tr")

  val e3101: Option[NodeSeq] = chapters
    .find(_ \@ "label" == "E")
    .map(_ \ "section")
    .flatMap(_.find(_ \@ "label" == "E.3"))
    .map(_ \ "section")
    .flatMap(_.find(_ \@ "label" == "E.3.10"))
    .map(_ \\ "table")
    .flatMap(_.find(_ \@ "label" == "E.3.10-1"))
    .map(_ \\ "tbody" \ "tr")

  case class Attribute(name: String, tag: String, mask: String, basic: String, retainSafePrivate: String, retainUids: String, retainDeviceId: String, retainInstituionId: String, retainPatientCharacteristics: String, retainDates: String, retainModifiedDates: String, cleanDescriptors: String, cleanStructuredContent: String, cleanGraphics: String)
  case class PrivateAttribute(tag: String, creator: String, meaning: String)

  val ops: Seq[Attribute] = {
    def toAttributes(nodes: NodeSeq): Seq[Attribute] = nodes
      .map { node =>
        val cells = node \ "td"
        val name = cells.head.text.trim
        val tag1 = if (name == "Private attributes") "xxx1xxxx" else cells(1).text.trim.replaceAll(nonAlphaNumeric, "")
        val tag = tag1.replaceAll("x", "0")
        val mask = if (name == "Private attributes") "00010000" else tag1.replaceAll("[^x]", "F").replaceAll("x", "0")
        Attribute(
          name, tag, mask,
          cells(4).text.trim,
          cells(5).text.trim,
          cells(6).text.trim,
          cells(7).text.trim,
          cells(8).text.trim,
          cells(9).text.trim,
          cells(10).text.trim,
          cells(11).text.trim,
          cells(12).text.trim,
          cells(13).text.trim,
          cells(14).text.trim)
      }

    toAttributes(e11.getOrElse(Seq.empty))
  }

  val privates: Seq[PrivateAttribute] = {
    def toPrivateAttributes(nodes: NodeSeq): Seq[PrivateAttribute] = nodes
      .map { node =>
        val cells = node \ "td"
        PrivateAttribute(
          cells.head.text.trim.replaceAll(nonAlphaNumeric, "").replaceAll("x", "0"),
          cells(1).text.trim,
          cells(4).text.trim)
      }

    toPrivateAttributes(e3101.getOrElse(Seq.empty))
  }

  def generate(): String =
    s"""package se.nimsa.sbx.anonymization
      |
      |object AnonymizationProfiles {
      |  import ConfidentialityOption._
      |  import AnonymizationOp._
      |
      |  case class TagMask(tag: Int, mask: Int) {
      |    def contains(otherTag: Int): Boolean = (otherTag & mask) == tag
      |  }
      |
      |  val profiles: Map[ConfidentialityOption, Map[TagMask, AnonymizationOp]] = Map(
      |    BASIC_PROFILE -> Map(
      |${ops.filter(_.basic.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.basic}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_SAFE_PRIVATE -> Map(
      |${ops.filter(_.retainSafePrivate.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainSafePrivate}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_UIDS -> Map(
      |${ops.filter(_.retainUids.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainUids}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_DEVICE_IDENTITY -> Map(
      |${ops.filter(_.retainDeviceId.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainDeviceId}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_INSTITUTION_IDENTITY -> Map(
      |${ops.filter(_.retainInstituionId.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainInstituionId}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_PATIENT_CHARACTERISTICS -> Map(
      |${ops.filter(_.retainPatientCharacteristics.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainPatientCharacteristics}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION -> Map(
      |${ops.filter(_.retainDates.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainDates}")""").mkString(",\r\n")}
      |    ),
      |    RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION_MODIFIED_DATES -> Map(
      |${ops.filter(_.retainModifiedDates.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.retainModifiedDates}")""").mkString(",\r\n")}
      |    ),
      |    CLEAN_DESCRIPTORS -> Map(
      |${ops.filter(_.cleanDescriptors.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.cleanDescriptors}")""").mkString(",\r\n")}
      |    ),
      |    CLEAN_STRUCTURED_CONTENT -> Map(
      |${ops.filter(_.cleanStructuredContent.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.cleanStructuredContent}")""").mkString(",\r\n")}
      |    ),
      |    CLEAN_GRAPHICS -> Map(
      |${ops.filter(_.cleanGraphics.nonEmpty).map(op => s"""      TagMask(0x${op.tag}, 0x${op.mask}) -> forCode("${op.cleanGraphics}")""").mkString(",\r\n")}
      |    )
      |  )
      |  
      |  val safePrivateAttributes: Seq[TagMask] = Seq(
      |${privates.map(p => s"""    TagMask(0x${p.tag}, 0xFFFF00FF)""").mkString(",\r\n")}
      |  )
      |}
      |""".stripMargin
}

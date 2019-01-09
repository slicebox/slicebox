/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Partition}
import se.nimsa.dicom.data.DicomParts.{DicomPart, MetaPart}
import se.nimsa.dicom.data._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeyOpResult, TagValue}

object DicomStreamUtil {

  case class AnonymizationKeyOpResultPart(result: AnonymizationKeyOpResult) extends MetaPart

  val identityFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]

  def isAnonymous(elements: Elements): Boolean = elements.getString(Tag.PatientIdentityRemoved).exists(_.toUpperCase == "YES")

  def elementsContainTagValues(elements: Elements, tagValues: Seq[TagValue]): Boolean = tagValues
    .forall(tv => tv.value.isEmpty || elements.getString(tv.tagPath).forall(_ == tv.value))

  /**
    * Branching flow where the flow is redirected to the detour flow at the first occurrence where the supplied partial
    * function is truthy. The default flow is pass-through only (identity). Once the detour flow is taken, it will never
    * revert back.
    *
    * Note that the resulting flow preserves the ordering of messages only because the default identity flow contains no
    * async nor buffering elements. The detour flow may very well be async and/or buffering so it is only safe to switch
    * to this flow, not from it. See:
    * * https://discuss.lightbend.com/t/akka-stream-conditional-via-partition/970
    * * https://stackoverflow.com/questions/33817241/conditionally-skip-flow-using-akka-streams
    * * https://groups.google.com/forum/#!topic/akka-user/E0TcZlkPQz4
    * for discussions on the difficulties of preserving order in branching flows.
    *
    * @param takeDetour partial function that when defined and truthy will trigger the detour
    * @param detour     detour flow
    * @return a flow
    */
  def detourFlow(takeDetour: PartialFunction[DicomPart, Boolean], detour: Flow[DicomPart, DicomPart, _]): Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val decider = builder.add(Flow[DicomPart]
        .statefulMapConcat {
          var route = 0

          () => {
            case part if route == 0 && takeDetour.isDefinedAt(part) =>
              if (takeDetour(part))
                route = 1
              (part, route) :: Nil
            case part => (part, route) :: Nil
          }
        })
      val partition = builder.add(Partition[(DicomPart, Int)](2, _._2))
      val mapper = Flow.fromFunction[(DicomPart, Int), DicomPart](_._1)
      val merge = builder.add(Merge[DicomPart](2))

      decider ~> partition
      partition.out(0) ~> mapper ~> merge
      partition.out(1) ~> mapper ~> detour ~> merge
      FlowShape(decider.in, merge.out)
    })

}

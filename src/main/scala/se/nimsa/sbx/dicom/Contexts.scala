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

package se.nimsa.sbx.dicom

import com.typesafe.config.ConfigFactory
import org.dcm4che3.data.UID

import scala.collection.JavaConverters._

object Contexts {

  case class Context(sopClassUid: String, transferSyntaxUids: Seq[String])

  private lazy val standardTS = Seq(UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian, UID.ExplicitVRBigEndianRetired, UID.DeflatedExplicitVRLittleEndian, UID.JPEGBaseline1)

  lazy val imageDataContexts = ConfigFactory.load().getStringList("slicebox.accepted-sop-classes.image-data").asScala.map(uid => Context(uid, standardTS))

  lazy val extendedContexts = imageDataContexts ++ ConfigFactory.load().getStringList("slicebox.accepted-sop-classes.extended").asScala.map(uid => Context(uid, standardTS))

  def asNamePairs(contexts: Seq[Context]): Seq[(String, String)] = contexts.flatMap(context => context.transferSyntaxUids.map((context.sopClassUid, _)))

}

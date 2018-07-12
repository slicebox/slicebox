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

package se.nimsa.sbx.anonymization

import java.util.UUID

import akka.util.ByteString
import se.nimsa.dicom.data.VR
import se.nimsa.sbx.dicom.DicomUtil.toAsciiBytes
import se.nimsa.dicom.data._

import scala.util.Random

object AnonymizationUtil {

  def createAnonymousPatientName(sex: Option[String], age: Option[String]): String = {
    val sexString = sex.filter(_.nonEmpty).getOrElse("<unknown sex>")
    val ageString = age.filter(_.nonEmpty).getOrElse("<unknown age>")
    s"Anonymous $sexString $ageString"
  }

  def createAccessionNumber(accessionNumberBytes: ByteString): ByteString = {
    val seed = UUID.nameUUIDFromBytes(accessionNumberBytes.toArray).getMostSignificantBits
    val rand = new Random(seed)
    val newNumber = (1 to 16).foldLeft("")((s, _) => s + rand.nextInt(10).toString)
    toAsciiBytes(newNumber, VR.SH)
  }

  def createUid(baseValue: String): String =
    if (baseValue == null || baseValue.isEmpty)
      createUID()
    else
      createNameBasedUID(ByteString(baseValue))

  def createUid(baseValue: ByteString): ByteString = toAsciiBytes(
    if (baseValue == null || baseValue.isEmpty)
      createUID()
    else
      createNameBasedUID(baseValue), VR.UI)
}

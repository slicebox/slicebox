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

package se.nimsa.sbx.box

import play.api.libs.json._
import se.nimsa.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import se.nimsa.sbx.box.BoxProtocol._

trait BoxJsonFormats {

  private def enumFormat[A](f: String => A) = Format(Reads[A] {
    case JsString(string) => JsSuccess(f(string))
    case _ => JsError("Enumeration expected")
  }, Writes[A](a => JsString(a.toString)))

  implicit val confidentialityOptionFormat: Format[ConfidentialityOption] = enumFormat(ConfidentialityOption.withName)
  implicit val anonymizationProfileFormat: Format[AnonymizationProfile] = Format(Reads[AnonymizationProfile] {
    case JsObject(a) => a.get("options").map(v => Json.fromJson[Seq[ConfidentialityOption]](v))
      .map(_.map(AnonymizationProfile.apply))
      .getOrElse(JsError("Missing field \"options\""))
    case _ => JsError("Json object expected")
  }, Writes[AnonymizationProfile](a => JsObject(Map("options" -> Json.toJson(a.options)))))
  implicit val transactionStatusFormat: Format[TransactionStatus] = enumFormat(TransactionStatus.withName)
  implicit val boxTransactionStatusFormat: Format[BoxTransactionStatus] = Json.format[BoxTransactionStatus]
  implicit val outgoingEntryFormat: Format[OutgoingTransaction] = Json.format[OutgoingTransaction]
  implicit val outgoingImageFormat: Format[OutgoingImage] = Json.format[OutgoingImage]
  implicit val outgoingEntryImageFormat: Format[OutgoingTransactionImage] = Json.format[OutgoingTransactionImage]
  implicit val failedOutgoingEntryFormat: Format[FailedOutgoingTransactionImage] = Json.format[FailedOutgoingTransactionImage]

}

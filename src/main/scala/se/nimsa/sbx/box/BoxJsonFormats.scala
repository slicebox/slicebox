package se.nimsa.sbx.box

import play.api.libs.json._
import se.nimsa.sbx.box.BoxProtocol._

trait BoxJsonFormats {

  private def enumFormat[A](f: String => A) = Format(Reads[A] {
    case JsString(string) => JsSuccess(f(string))
    case _ => throw new IllegalArgumentException("Enumeration expected")
  }, Writes[A](a => JsString(a.toString)))

  implicit val transactionStatusFormat: Format[TransactionStatus] = enumFormat(TransactionStatus.withName)
  implicit val outgoingEntryFormat: Format[OutgoingTransaction] = Json.format[OutgoingTransaction]
  implicit val outgoingImageFormat: Format[OutgoingImage] = Json.format[OutgoingImage]
  implicit val outgoingEntryImageFormat: Format[OutgoingTransactionImage] = Json.format[OutgoingTransactionImage]
  implicit val failedOutgoingEntryFormat: Format[FailedOutgoingTransactionImage] = Json.format[FailedOutgoingTransactionImage]

}

package se.nimsa.sbx.box

import se.nimsa.sbx.box.BoxProtocol._
import spray.json._

trait BoxJsonFormats extends DefaultJsonProtocol {

  implicit object TransactionStatusFormat extends JsonFormat[TransactionStatus] {
    def write(obj: TransactionStatus) = JsString(obj.toString())

    def read(json: JsValue): TransactionStatus = json match {
      case JsString(string) => TransactionStatus.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }
  implicit val outgoingEntryFormat = jsonFormat8(OutgoingTransaction)
  implicit val outgoingImageFormat = jsonFormat5(OutgoingImage)
  implicit val outgoingEntryImageFormat = jsonFormat2(OutgoingTransactionImage)
  implicit val failedOutgoingEntryFormat = jsonFormat2(FailedOutgoingTransactionImage)

}

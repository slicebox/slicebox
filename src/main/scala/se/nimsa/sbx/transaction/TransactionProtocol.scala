package se.nimsa.sbx.transaction

import se.nimsa.sbx.app.GeneralProtocol.{Destination, Source}
import se.nimsa.sbx.model.Entity

object TransactionProtocol {

  case class Transaction(id: Long, incoming: Boolean, clientId: Long, clientName: String, clientType: String, files: Long, added: Long, created: Long, updated: Long) extends Entity

  case class TransactionImage(id: Long, transactionId: Long, overwrite: Boolean, imageId: Long) extends Entity

}

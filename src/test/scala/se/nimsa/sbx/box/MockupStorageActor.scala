package se.nimsa.sbx.box

import akka.actor.Actor
import akka.actor.Status.Failure
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{ImageType, InstanceNumber, SOPInstanceUID}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.TestUtil

class MockupStorageActor extends Actor {

  import MockupStorageActor._

  var nStoredDicomDatas = 3

  var badBehavior = false
  var exception: Exception = _

  def receive = {
    case ShowBadBehavior(e) =>
      badBehavior = true
      exception = e

    case ShowGoodBehavior(n) =>
      badBehavior = false
      nStoredDicomDatas = n

    case CheckDicomData(_, _) =>
      sender ! true

    case AddDicomData(_, _, _) =>
      if (badBehavior)
        sender ! Failure(exception)
      else
        sender ! DicomDataAdded(Image((math.random * 1000).toLong, (math.random * 1000).toLong, SOPInstanceUID("sop uid"), ImageType("image type"), InstanceNumber("instance number")), overwrite = false)

    case GetDicomData(image, _) =>
      if (badBehavior)
        sender ! Failure(exception)
      else
        sender ! (image.id match {
          case id if id <= nStoredDicomDatas => TestUtil.createDicomData()
          case _ =>
            Failure(new IllegalArgumentException("Dicom data not found"))
        })

    case MoveDicomData(source, target) =>
      if (badBehavior)
        sender ! Failure(exception)
      else
        sender ! DicomDataMoved(source, target)
  }
}

object MockupStorageActor {

  case class ShowGoodBehavior(nStoredDicomDatas: Int)

  case class ShowBadBehavior(e: Exception)

}
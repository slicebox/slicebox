package se.nimsa.sbx.box

import akka.actor.Actor
import akka.actor.Status.Failure
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.ImageType
import se.nimsa.sbx.dicom.DicomPropertyValue.InstanceNumber
import se.nimsa.sbx.dicom.DicomPropertyValue.SOPInstanceUID
import se.nimsa.sbx.storage.StorageProtocol.{AddDicomData, CheckDicomData, DicomDataAdded, GetDicomData}
import se.nimsa.sbx.util.TestUtil

class MockupStorageActor extends Actor {

  import MockupStorageActor._
  
  var nStoredDicomDatas = 3

  var badBehavior = false
  var exception: Exception = null

  def receive = {
    case ShowBadBehavior(e) =>
      badBehavior = true
      exception = e

    case ShowGoodBehavior(n) =>
      badBehavior = false
      nStoredDicomDatas = n

    case CheckDicomData(dicomData, restrictSopClass) =>
      sender ! true

    case AddDicomData(dicomData, source, image) =>
      if (badBehavior)
        sender ! Failure(exception)
      else
        sender ! DicomDataAdded(Image((math.random * 1000).toLong, (math.random * 1000).toLong, SOPInstanceUID("sop uid"), ImageType("image type"), InstanceNumber("instance number")), overwrite = false)

    case GetDicomData(image, withPixelData) =>
      if (badBehavior)
        sender ! Failure(exception)
      else
        sender ! (image.id match {
          case id if id <= nStoredDicomDatas => Some(TestUtil.createDicomData())
          case _ =>
            None
        })
  }
}

object MockupStorageActor {
  case class ShowGoodBehavior(nStoredDicomDatas: Int)
  case class ShowBadBehavior(e: Exception)
}
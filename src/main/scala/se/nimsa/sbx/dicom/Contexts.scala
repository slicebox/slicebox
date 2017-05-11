/*
 * Copyright 2017 Lars Edenbrandt
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

import se.nimsa.sbx.dicom.SopClasses._
import se.nimsa.sbx.dicom.TransferSyntaxes._


object Contexts {

  case class Context(sopClass: SopClass, transferSyntaxes: Seq[TransferSyntax])

  private val standardTS = Seq(ImplicitVrLittleEndian, ExplicitVrLittleEndian, ExplicitVrBigEndian, DeflatedExplicitVrLittleEndian, JpegBaselineProcess1)
  
  val imageDataContexts = Seq(
    Context(ComputedRadiographyImageStorage, standardTS),
    Context(DigitalXRayImageStorageForPresentation, standardTS),
    Context(DigitalXRayImageStorageForProcessing, standardTS),
    Context(DigitalMammographyXRayImageStorageForPresentation, standardTS),
    Context(DigitalMammographyXRayImageStorageForProcessing, standardTS),
    Context(DigitalIntraOralXRayImageStorageForPresentation, standardTS),
    Context(DigitalIntraOralXRayImageStorageForProcessing, standardTS),
    Context(CTImageStorage, standardTS),
    Context(UltrasoundMultiframeImageStorage, standardTS),
    Context(MRImageStorage, standardTS),
    Context(MRSpectroscopyStorage, standardTS),
    Context(UltrasoundImageStorage, standardTS),
    Context(XRayAngiographicImageStorage, standardTS),
    Context(EnhancedXAImageStorage, standardTS),
    Context(XRayRadiofluoroscopicImageStorage, standardTS),
    Context(EnhancedXRFImageStorage, standardTS),
    Context(XRay3DAngiographicImageStorage, standardTS),
    Context(XRay3DCraniofacialImageStorage, standardTS),
    Context(BreastTomosynthesisImageStorage, standardTS),
    Context(IntravascularOpticalCoherenceTomographyImageStorageForPresentation, standardTS),
    Context(IntravascularOpticalCoherenceTomographyImageStorageForProcessing, standardTS),
    Context(NuclearMedicineImageStorage, standardTS),
    Context(VLEndoscopicImageStorage, standardTS),
    Context(VLMicroscopicImageStorage, standardTS),
    Context(VLSlidecoordinatesMicroscopicImageStorage, standardTS),
    Context(OphthalmicPhotography8BitImageStorage, standardTS),
    Context(OphthalmicPhotography16BitImageStorage, standardTS),
    Context(OphthalmicTomographyImageStorage, standardTS),
    Context(VLWholeSlideMicroscopyImageStorage, standardTS),
    Context(PositronEmissionTomographyImageStorage, standardTS),
    Context(RTImageStorage, standardTS)
  )

  val extendedContexts = imageDataContexts ++ Seq(
    Context(SecondaryCaptureImageStorage, standardTS),
    Context(MultiframeSingleBitSecondaryCaptureImageStorage, standardTS),
    Context(MultiframeGrayscaleByteSecondaryCaptureImageStorage, standardTS),
    Context(MultiframeGrayscaleWordSecondaryCaptureImageStorage, standardTS),
    Context(MultiframeColorSecondaryCaptureImageStorage, standardTS)
  )


  def asNamePairs(contexts: Seq[Context]): Seq[(String, String)] = {
    contexts.flatMap { context =>
      context.transferSyntaxes.map((context.sopClass, _))
    }.map { pair =>
      (pair._1.uid, pair._2.uid)
    }
  }
}

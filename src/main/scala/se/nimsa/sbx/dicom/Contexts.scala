package se.nimsa.sbx.dicom

import se.nimsa.sbx.dicom.SopClasses._
import se.nimsa.sbx.dicom.TransferSyntaxes._

object Contexts {

  case class Context(sopClass: SopClass, transferSyntaxes: Seq[TransferSyntax])

  private val standardTS = Seq(ImplicitVrLittleEndian, ExplicitVrLittleEndian, ExplicitVrBigEndian, JpegBaselineProcess1)
  
  val imageDataContexts = Seq(
    Context(DigitalXRayImageStorageForPresentation, standardTS),
    Context(DigitalXRayImageStorageForProcessing, standardTS),
    Context(DigitalMammographyXRayImageStorageForPresentation, standardTS),
    Context(DigitalMammographyXRayImageStorageForProcessing, standardTS),
    Context(DigitalIntraOralXRayImageStorageForPresentation, standardTS),
    Context(DigitalIntraOralXRayImageStorageForProcessing, standardTS),
    Context(CTImageStorage, standardTS),
    Context(EnhancedCTImageStorage, standardTS),
    Context(LegacyConvertedEnhancedCTImageStorage, standardTS),
    Context(UltrasoundMultiframeImageStorage, standardTS),
    Context(MRImageStorage, standardTS),
    Context(EnhancedMRImageStorage, standardTS),
    Context(MRSpectroscopyStorage, standardTS),
    Context(LegacyConvertedEnhancedMRImageStorage, standardTS),
    Context(UltrasoundImageStorage, standardTS),
    Context(EnhancedUSVolumeStorage, standardTS),
    Context(GrayscaleSoftcopyPresentationStateStorage, standardTS),
    Context(ColorSoftcopyPresentationStateStorage, standardTS),
    Context(PseudocolorSoftcopyPresentationStateStorage, standardTS),
    Context(BlendingSoftcopyPresentationStateStorage, standardTS),
    Context(XaXrfGrayscaleSoftcopyPresentationStateStorage, standardTS),
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
    Context(RawDataStorage, standardTS),
    Context(VLEndoscopicImageStorage, standardTS),
    Context(VLMicroscopicImageStorage, standardTS),
    Context(VLSlidecoordinatesMicroscopicImageStorage, standardTS),
    Context(OphthalmicPhotography8BitImageStorage, standardTS),
    Context(OphthalmicPhotography16BitImageStorage, standardTS),
    Context(OphthalmicTomographyImageStorage, standardTS),
    Context(VLWholeSlideMicroscopyImageStorage, standardTS),
    Context(PositronEmissionTomographyImageStorage, standardTS),
    Context(EnhancedPETImageStorage, standardTS),
    Context(LegacyConvertedEnhancedPETImageStorage, standardTS),
    Context(BasicStructuredDisplayStorage, standardTS),
    Context(RTImageStorage, standardTS)
  )

  val extendedContexts = imageDataContexts ++ Seq(
    Context(SecondaryCaptureImageStorage, standardTS),
    Context(MultiframeSingleBitSecondaryCaptureImageStorage, standardTS),
    Context(MultiframeGrayscaleByteSecondaryCaptureImageStorage, standardTS),
    Context(MultiframeGrayscaleWordSecondaryCaptureImageStorage, standardTS),
    Context(MultiframeColorSecondaryCaptureImageStorage, standardTS)
  )
}

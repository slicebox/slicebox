/*
 * Copyright 2016 Lars Edenbrandt
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

object SopClasses {

  case class SopClass(sopClassName: String, sopClassUID: String, iodSpecification: String, included: Boolean) {
    override def toString = sopClassUID
  }

  // copy-paste from the DICOM standard @ http://medical.nema.org/medical/dicom/current/output/html/part04.html#sect_I.4
  // support relevant image storage classes only

  val MediaStorageDirectoryStorage = SopClass("Media Storage Directory Storage", "1.2.840.10008.1.3.10", "Basic Directory IOD", included = false)
  val ComputedRadiographyImageStorage = SopClass("Computed Radiography Image Storage", "1.2.840.10008.5.1.4.1.1.1", "Computed Radiography Image IOD", included = true)
  val DigitalXRayImageStorageForPresentation = SopClass("Digital X-Ray Image Storage - For Presentation", "1.2.840.10008.5.1.4.1.1.1.1", "Digital X-Ray Image IOD", included = true)
  val DigitalXRayImageStorageForProcessing = SopClass("Digital X-Ray Image Storage - For Processing", "1.2.840.10008.5.1.4.1.1.1.1.1", "Digital X-Ray Image IOD", included = true)
  val DigitalMammographyXRayImageStorageForPresentation = SopClass("Digital Mammography X-Ray Image Storage - For Presentation", "1.2.840.10008.5.1.4.1.1.1.2", "Digital Mammography X-Ray Image IOD", included = true)
  val DigitalMammographyXRayImageStorageForProcessing = SopClass("Digital Mammography X-Ray Image Storage - For Processing", "1.2.840.10008.5.1.4.1.1.1.2.1", "Digital Mammography X-Ray Image IOD", included = true)
  val DigitalIntraOralXRayImageStorageForPresentation = SopClass("Digital Intra-Oral X-Ray Image Storage - For Presentation", "1.2.840.10008.5.1.4.1.1.1.3", "Digital Intra-Oral X-Ray Image IOD", included = true)
  val DigitalIntraOralXRayImageStorageForProcessing = SopClass("Digital Intra-Oral X-Ray Image Storage - For Processing", "1.2.840.10008.5.1.4.1.1.1.3.1", "Digital Intra-Oral X-Ray Image IOD", included = true)
  val CTImageStorage = SopClass("CT Image Storage", "1.2.840.10008.5.1.4.1.1.2", "Computed Tomography Image IOD", included = true)
  val EnhancedCTImageStorage = SopClass("Enhanced CT Image Storage", "1.2.840.10008.5.1.4.1.1.2.1", "Enhanced CT Image IOD", included = true)
  val LegacyConvertedEnhancedCTImageStorage = SopClass("Legacy Converted Enhanced CT Image Storage", "1.2.840.10008.5.1.4.1.1.2.2", "Legacy Converted Enhanced CT Image IOD", included = true)
  val UltrasoundMultiframeImageStorage = SopClass("Ultrasound Multi-frame Image Storage", "1.2.840.10008.5.1.4.1.1.3.1", "Ultrasound Multi-frame Image IOD", included = true)
  val MRImageStorage = SopClass("MR Image Storage", "1.2.840.10008.5.1.4.1.1.4", "Magnetic Resonance Image IOD", included = true)
  val EnhancedMRImageStorage = SopClass("Enhanced MR Image Storage", "1.2.840.10008.5.1.4.1.1.4.1", "Enhanced MR Image IOD", included = true)
  val MRSpectroscopyStorage = SopClass("MR Spectroscopy Storage", "1.2.840.10008.5.1.4.1.1.4.2", "MR Spectroscopy IOD", included = true)
  val EnhancedMRColorImageStorage = SopClass("Enhanced MR Color Image Storage", "1.2.840.10008.5.1.4.1.1.4.3", "Enhanced MR Color Image IOD", included = true)
  val LegacyConvertedEnhancedMRImageStorage = SopClass("Legacy Converted Enhanced MR Image Storage", "1.2.840.10008.5.1.4.1.1.4.4", "Legacy Converted Enhanced MR Image IOD", included = true)
  val UltrasoundImageStorage = SopClass("Ultrasound Image Storage", "1.2.840.10008.5.1.4.1.1.6.1", "Ultrasound Image IOD", included = true)
  val EnhancedUSVolumeStorage = SopClass("Enhanced US Volume Storage", "1.2.840.10008.5.1.4.1.1.6.2", "Enhanced US Volume IOD", included = true)
  val SecondaryCaptureImageStorage = SopClass("Secondary Capture Image Storage", "1.2.840.10008.5.1.4.1.1.7", "Secondary Capture Image IOD", included = false)
  val MultiframeSingleBitSecondaryCaptureImageStorage = SopClass("Multi-frame Single Bit Secondary Capture Image Storage", "1.2.840.10008.5.1.4.1.1.7.1", "Multi-frame Single Bit Secondary Capture Image IOD", included = false)
  val MultiframeGrayscaleByteSecondaryCaptureImageStorage = SopClass("Multi-frame Grayscale Byte Secondary Capture Image Storage", "1.2.840.10008.5.1.4.1.1.7.2", "Multi-frame Grayscale Byte Secondary Capture Image IOD", included = false)
  val MultiframeGrayscaleWordSecondaryCaptureImageStorage = SopClass("Multi-frame Grayscale Word Secondary Capture Image Storage", "1.2.840.10008.5.1.4.1.1.7.3", "Multi-frame Grayscale Word Secondary Capture Image IOD", included = false)
  val MultiframeColorSecondaryCaptureImageStorage = SopClass("Multi-frame Color Secondary Capture Image Storage", "1.2.840.10008.5.1.4.1.1.7.4", "Multi-frame included = true Color Secondary Capture Image IOD", included = false)
  val ECG12leadWaveformStorage = SopClass("12-lead ECG Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.1.1", "12-Lead Electrocardiogram IOD", included = false)
  val ECGGeneralWaveformStorage = SopClass("General ECG Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.1.2", "General Electrocardiogram IOD", included = false)
  val ECGAmbulatoryWaveformStorage = SopClass("Ambulatory ECG Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.1.3", "Ambulatory Electrocardiogram IOD", included = false)
  val HemodynamicWaveformStorage = SopClass("Hemodynamic Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.2.1", "Hemodynamic IOD", included = false)
  val CardiacElectrophysiologyWaveformStorage = SopClass("Cardiac Electrophysiology Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.3.1", "Basic Cardiac Electrophysiology IOD", included = false)
  val BasicVoiceAudioWaveformStorage = SopClass("Basic Voice Audio Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.4.1", "Basic Voice Audio IOD", included = false)
  val GeneralAudioWaveformStorage = SopClass("General Audio Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.4.2", "General Audio Waveform IOD", included = false)
  val ArterialPulseWaveformStorage = SopClass("Arterial Pulse Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.5.1", "Arterial Pulse Waveform IOD", included = false)
  val RespiratoryWaveformStorage = SopClass("Respiratory Waveform Storage", "1.2.840.10008.5.1.4.1.1.9.6.1", "Respiratory Waveform IOD", included = false)
  val GrayscaleSoftcopyPresentationStateStorage = SopClass("Grayscale Softcopy Presentation State Storage", "1.2.840.10008.5.1.4.1.1.11.1", "Grayscale Softcopy Presentation State IOD", included = false)
  val ColorSoftcopyPresentationStateStorage = SopClass("Color Softcopy Presentation State Storage", "1.2.840.10008.5.1.4.1.1.11.2", "Color Softcopy Presentation State IOD", included = false)
  val PseudocolorSoftcopyPresentationStateStorage = SopClass("Pseudo-Color Softcopy Presentation State Storage", "1.2.840.10008.5.1.4.1.1.11.3", "Pseudo-color Softcopy Presentation State IOD", included = false)
  val BlendingSoftcopyPresentationStateStorage = SopClass("Blending Softcopy Presentation State Storage", "1.2.840.10008.5.1.4.1.1.11.4", "Blending Softcopy Presentation State IOD", included = false)
  val XaXrfGrayscaleSoftcopyPresentationStateStorage = SopClass("XA/XRF Grayscale Softcopy Presentation State Storage", "1.2.840.10008.5.1.4.1.1.11.5", "XA/XRF Grayscale Softcopy Presentation State IOD", included = false)
  val XRayAngiographicImageStorage = SopClass("X-Ray Angiographic Image Storage", "1.2.840.10008.5.1.4.1.1.12.1", "X-Ray Angiographic Image IOD", included = true)
  val EnhancedXAImageStorage = SopClass("Enhanced XA Image Storage", "1.2.840.10008.5.1.4.1.1.12.1.1", "Enhanced X-Ray Angiographic Image IOD", included = true)
  val XRayRadiofluoroscopicImageStorage = SopClass("X-Ray Radiofluoroscopic Image Storage", "1.2.840.10008.5.1.4.1.1.12.2", "X-Ray RF Image IOD", included = true)
  val EnhancedXRFImageStorage = SopClass("Enhanced XRF Image Storage", "1.2.840.10008.5.1.4.1.1.12.2.1", "Enhanced X-Ray RF Image IOD", included = true)
  val XRay3DAngiographicImageStorage = SopClass("X-Ray 3D Angiographic Image Storage", "1.2.840.10008.5.1.4.1.1.13.1.1", "X-Ray 3D Angiographic Image IOD", included = true)
  val XRay3DCraniofacialImageStorage = SopClass("X-Ray 3D Craniofacial Image Storage", "1.2.840.10008.5.1.4.1.1.13.1.2", "X-Ray 3D Craniofacial Image IOD", included = true)
  val BreastTomosynthesisImageStorage = SopClass("Breast Tomosynthesis Image Storage", "1.2.840.10008.5.1.4.1.1.13.1.3", "Breast Tomosynthesis Image IOD", included = true)
  val IntravascularOpticalCoherenceTomographyImageStorageForPresentation = SopClass("Intravascular Optical Coherence Tomography Image Storage - For Presentation", "1.2.840.10008.5.1.4.1.1.14.1", "Intravascular OCT IOD", included = true)
  val IntravascularOpticalCoherenceTomographyImageStorageForProcessing = SopClass("Intravascular Optical Coherence Tomography Image Storage - For Processing", "1.2.840.10008.5.1.4.1.1.14.2", "Intravascular OCT IOD", included = true)
  val NuclearMedicineImageStorage = SopClass("Nuclear Medicine Image Storage", "1.2.840.10008.5.1.4.1.1.20", "Nuclear Medicine Image IOD", included = true)
  val RawDataStorage = SopClass("Raw Data Storage", "1.2.840.10008.5.1.4.1.1.66", "Raw Data IOD", included = true)
  val SpatialRegistrationStorage = SopClass("Spatial Registration Storage", "1.2.840.10008.5.1.4.1.1.66.1", "Spatial Registration IOD", included = false)
  val SpatialFiducialsStorage = SopClass("Spatial Fiducials Storage", "1.2.840.10008.5.1.4.1.1.66.2", "Spatial Fiducials IOD", included = false)
  val DeformableSpatialRegistrationStorage = SopClass("Deformable Spatial Registration Storage", "1.2.840.10008.5.1.4.1.1.66.3", "Deformable Spatial Registration IOD", included = false)
  val SegmentationStorage = SopClass("Segmentation Storage", "1.2.840.10008.5.1.4.1.1.66.4", "Segmentation IOD", included = false)
  val SurfaceSegmentationStorage = SopClass("Surface Segmentation Storage", "1.2.840.10008.5.1.4.1.1.66.5", "Surface Segmentation IOD", included = false)
  val RealWorldValueMappingStorage = SopClass("Real World Value Mapping Storage", "1.2.840.10008.5.1.4.1.1.67", "Real World Value Mapping IOD", included = false)
  val SurfaceScanMeshStorage = SopClass("Surface Scan Mesh Storage", "1.2.840.10008.5.1.4.1.1.68.1", "Surface Scan Mesh IOD", included = false)
  val SurfaceScanPointCloudStorage = SopClass("Surface Scan Point Cloud Storage", "1.2.840.10008.5.1.4.1.1.68.2", "Surface Scan Point Cloud IOD", included = false)
  val VLEndoscopicImageStorage = SopClass("VL Endoscopic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.1", "VL Endoscopic Image IOD", included = true)
  val VideoEndoscopicImageStorage = SopClass("Video Endoscopic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.1.1", "Video Endoscopic Image IOD", included = true)
  val VLMicroscopicImageStorage = SopClass("VL Microscopic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.2", "VL Microscopic Image IOD", included = true)
  val VideoMicroscopicImageStorage = SopClass("Video Microscopic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.2.1", "Video Microscopic Image IOD", included = true)
  val VLSlidecoordinatesMicroscopicImageStorage = SopClass("VL Slide-Coordinates Microscopic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.3", "VL Slide-coordinates Microscopic Image IOD", included = true)
  val VLPhotographicImageStorage = SopClass("VL Photographic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.4", "VL Photographic Image IOD", included = true)
  val VideoPhotographicImageStorage = SopClass("Video Photographic Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.4.1", "Video Photographic Image IOD", included = true)
  val OphthalmicPhotography8BitImageStorage = SopClass("Ophthalmic Photography 8 Bit Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.5.1", "Ophthalmic Photography 8 Bit Image IOD", included = true)
  val OphthalmicPhotography16BitImageStorage = SopClass("Ophthalmic Photography 16 Bit Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.5.2", "Ophthalmic Photography 16 Bit Image IOD", included = true)
  val StereometricRelationshipStorage = SopClass("Stereometric Relationship Storage", "1.2.840.10008.5.1.4.1.1.77.1.5.3", "Stereometric Relationship IOD", included = false)
  val OphthalmicTomographyImageStorage = SopClass("Ophthalmic Tomography Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.5.4", "Ophthalmic Tomography Image IOD", included = true)
  val VLWholeSlideMicroscopyImageStorage = SopClass("VL Whole Slide Microscopy Image Storage", "1.2.840.10008.5.1.4.1.1.77.1.6", "VL Whole Slide Microscopy Image IOD", included = true)
  val LensometryMeasurementsStorage = SopClass("Lensometry Measurements Storage", "1.2.840.10008.5.1.4.1.1.78.1", "Lensometry Measurements IOD", included = false)
  val AutorefractionMeasurementsStorage = SopClass("Autorefraction Measurements Storage", "1.2.840.10008.5.1.4.1.1.78.2", "Autorefraction Measurements IOD", included = false)
  val KeratometryMeasurementsStorage = SopClass("Keratometry Measurements Storage", "1.2.840.10008.5.1.4.1.1.78.3", "Keratometry Measurements IOD", included = false)
  val SubjectiveRefractionMeasurementsStorage = SopClass("Subjective Refraction Measurements Storage", "1.2.840.10008.5.1.4.1.1.78.4", "Subjective Refraction Measurements IOD", included = false)
  val VisualAcuityStorageMeasurementsStorage = SopClass("Visual Acuity Storage Measurements Storage", "1.2.840.10008.5.1.4.1.1.78.5", "Visual Acuity Measurements IOD", included = false)
  val SpectaclePrescriptionReportStorage = SopClass("Spectacle Prescription Report Storage", "1.2.840.10008.5.1.4.1.1.78.6", "Spectacle Prescription Report IOD", included = false)
  val OphthalmicAxialMeasurementsStorage = SopClass("Ophthalmic Axial Measurements Storage", "1.2.840.10008.5.1.4.1.1.78.7", "Ophthalmic Axial Measurements IOD", included = false)
  val IntraocularLensCalculationsStorage = SopClass("Intraocular Lens Calculations Storage", "1.2.840.10008.5.1.4.1.1.78.8", "Intraocular Lens Calculations IOD", included = false)
  val MacularGridThicknessAndVolumeReport = SopClass("Macular Grid Thickness and Volume Report", "1.2.840.10008.5.1.4.1.1.79.1", "Macular Grid Thickness and Volume Report IOD", included = false)
  val OphthalmicVisualFieldStaticPerimetryMeasurementsStorage = SopClass("Ophthalmic Visual Field Static Perimetry Measurements Storage", "1.2.840.10008.5.1.4.1.1.80.1", "Ophthalmic Visual Field Static Perimetry Measurements IOD", included = false)
  val OphthalmicThicknessMapStorage = SopClass("Ophthalmic Thickness Map Storage", "1.2.840.10008.5.1.4.1.1.81.1", "Ophthalmic Thickness Map IOD", included = false)
  val CornealTopographyMapStorage = SopClass("Corneal Topography Map Storage", "1.2.840.10008.5.1.4.1.1.82.1", "Corneal Topography Map IOD", included = false)
  val BasicTextSR = SopClass("Basic Text SR", "1.2.840.10008.5.1.4.1.1.88.11", "Basic Text SR IOD", included = false)
  val EnhancedSR = SopClass("Enhanced SR", "1.2.840.10008.5.1.4.1.1.88.22", "Enhanced SR IOD", included = false)
  val ComprehensiveSR = SopClass("Comprehensive SR", "1.2.840.10008.5.1.4.1.1.88.33", "Comprehensive SR IOD", included = false)
  val Comprehensive3DSR = SopClass("Comprehensive 3D SR", "1.2.840.10008.5.1.4.1.1.88.34", "Comprehensive 3D SR IOD", included = false)
  val ProcedureLog = SopClass("Procedure Log", "1.2.840.10008.5.1.4.1.1.88.40", "Procedure Log IOD", included = false)
  val MammographyCadSr = SopClass("Mammography CAD SR", "1.2.840.10008.5.1.4.1.1.88.50", "Mammography CAD SR IOD", included = false)
  val KeyObjectSelectionDocument = SopClass("Key Object Selection Document", "1.2.840.10008.5.1.4.1.1.88.59", "Key Object Selection Document IOD", included = false)
  val ChestCadSr = SopClass("Chest CAD SR", "1.2.840.10008.5.1.4.1.1.88.65", "Chest CAD SR IOD", included = false)
  val XRayRadiationDoseSR = SopClass("X-Ray Radiation Dose SR", "1.2.840.10008.5.1.4.1.1.88.67", "X-Ray Radiation Dose SR IOD", included = false)
  val ColonCadSr = SopClass("Colon CAD SR", "1.2.840.10008.5.1.4.1.1.88.69", "Colon CAD SR IOD", included = false)
  val ImplantationPlanSRDocumentStorage = SopClass("Implantation Plan SR Document Storage", "1.2.840.10008.5.1.4.1.1.88.70", "Implantation Plan SR Document IOD", included = false)
  val EncapsulatedPDFStorage = SopClass("Encapsulated PDF Storage", "1.2.840.10008.5.1.4.1.1.104.1", "Encapsulated PDF IOD", included = false)
  val EncapsulatedCDAStorage = SopClass("Encapsulated CDA Storage", "1.2.840.10008.5.1.4.1.1.104.2", "Encapsulated CDA IOD", included = false)
  val PositronEmissionTomographyImageStorage = SopClass("Positron Emission Tomography Image Storage", "1.2.840.10008.5.1.4.1.1.128", "Positron Emission Tomography Image IOD", included = true)
  val EnhancedPETImageStorage = SopClass("Enhanced PET Image Storage", "1.2.840.10008.5.1.4.1.1.130", "Enhanced PET Image IOD", included = true)
  val LegacyConvertedEnhancedPETImageStorage = SopClass("Legacy Converted Enhanced PET Image Storage", "1.2.840.10008.5.1.4.1.1.128.1", "Legacy Converted Enhanced PET Image IOD", included = true)
  val BasicStructuredDisplayStorage = SopClass("Basic Structured Display Storage", "1.2.840.10008.5.1.4.1.1.131", "Basic Structured Display IOD", included = false)
  val RTImageStorage = SopClass("RT Image Storage", "1.2.840.10008.5.1.4.1.1.481.1", "RT Image IOD", included = true)
  val RTDoseStorage = SopClass("RT Dose Storage", "1.2.840.10008.5.1.4.1.1.481.2", "RT Dose IOD", included = false)
  val RTStructureSetStorage = SopClass("RT Structure Set Storage", "1.2.840.10008.5.1.4.1.1.481.3", "RT Structure Set IOD", included = false)
  val RTBeamsTreatmentRecordStorage = SopClass("RT Beams Treatment Record Storage", "1.2.840.10008.5.1.4.1.1.481.4", "RT Beams Treatment Record IOD", included = false)
  val RTPlanStorage = SopClass("RT Plan Storage", "1.2.840.10008.5.1.4.1.1.481.5", "RT Plan IOD", included = false)
  val RTBrachyTreatmentRecordStorage = SopClass("RT Brachy Treatment Record Storage", "1.2.840.10008.5.1.4.1.1.481.6", "RT Brachy Treatment Record IOD", included = false)
  val RTTreatmentSummaryRecordStorage = SopClass("RT Treatment Summary Record Storage", "1.2.840.10008.5.1.4.1.1.481.7", "RT Treatment Summary Record IOD", included = false)
  val RTIonPlanStorage = SopClass("RT Ion Plan Storage", "1.2.840.10008.5.1.4.1.1.481.8", "RT Ion Plan IOD", included = false)
  val RTIonBeamsTreatmentRecordStorage = SopClass("RT Ion Beams Treatment Record Storage", "1.2.840.10008.5.1.4.1.1.481.9", "RT Ion Beams Treatment Record IOD", included = false)
  val RTBeamsDeliveryInstructionStorage = SopClass("RT Beams Delivery Instruction Storage", "1.2.840.10008.5.1.4.34.7", "RT Beams Delivery Instruction IOD", included = false)
  val HangingProtocolStorage = SopClass("Hanging Protocol Storage", "1.2.840.10008.5.1.4.38.1", "Hanging Protocol IOD", included = false)
  val ColorPaletteStorage = SopClass("Color Palette Storage", "1.2.840.10008.5.1.4.39.1", "Color Palette IOD", included = false)
  val GenericImplantTemplateStorage = SopClass("Generic Implant Template Storage", "1.2.840.10008.5.1.4.43.1", "Generic Implant Template IOD", included = false)
  val ImplantAssemblyTemplateStorage = SopClass("Implant Assembly Template Storage", "1.2.840.10008.5.1.4.44.1", "Implant Assembly Template IOD", included = false)
  val ImplantTemplateGroupStorage = SopClass("Implant Template Group Storage", "1.2.840.10008.5.1.4.45.1", "Implant Template Group IOD", included = false)

}

/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import java.util.List;

/**
 * Immutable data models for WCO Unified File Format ({@code UFF 2.0} and {@code UFF 3.0.0})
 * cargo Non-Intrusive Inspection (NII) packages.
 */
public final class WcoUffModels {

    private WcoUffModels() {}

    public record HemdZMapBand(
            String name,
            int paletteStart,
            int paletteEnd,
            int effectiveZStart,
            int effectiveZEnd
    ) {}

    public record XRayFileInfo(
            String fileId,
            String entryPath,
            long sizeBytes,
            String view,
            String modality,
            String scanningMode,
            List<Double> energiesMeV,
            Double horizontalPixelSizeMm,
            Double verticalPixelSizeMm,
            Double averageSpeedMmPerSec,
            String hemdEntryPath,
            long hemdSizeBytes,
            List<HemdZMapBand> zMaps
    ) {}

    public record OcrResultInfo(
            String ocrType,
            String value,
            String normalizedValue,
            Double confidence,
            String plateLocation,
            String view,
            String imageEntryPath,
            String containerType
    ) {}

    public record DeclarationInfo(
            String customsId,
            String consignorName,
            String consignorLocation,
            String consigneeName,
            String consigneeLocation,
            String hsCode,
            String goodsDescription,
            String unit,
            Double quantity
    ) {}

    public record AnnotationInfo(
            String annotationId,
            String description,
            String comment,
            Integer confidence,
            String fileUri,
            String shape,
            double top,
            double left,
            double bottom,
            double right
    ) {}

    public record RadiationFileInfo(
            String fileId,
            String entryPath,
            long sizeBytes,
            String type
    ) {}

    /**
     * Complete parsed metadata for a single {@code .uff} archive.
     */
    public record UffCaseMetadata(
            String uffUuid,
            String uffVersion,
            String archiveFileName,
            String relativePath,
            long archiveSizeBytes,
            long archiveMtimeMillis,
            String scannerId,
            String scannerManufacturer,
            String scannerModel,
            String scannerSerial,
            String scannerCaseId,
            String country,
            Double latitude,
            Double longitude,
            String scanTimestamp,
            long scanEpochMillis,
            Long scanDurationMs,
            String containerNum,
            String frontPlateNum,
            String vehicleType,
            Double containerWeightKg,
            Double vehicleWeightKg,
            Integer containerLengthFt,
            List<String> allNormalizedVehicleIds,
            List<String> allDisplayVehicleIds,
            List<OcrResultInfo> ocrResults,
            DeclarationInfo declaration,
            String manifestEntryPath,
            long manifestSizeBytes,
            List<RadiationFileInfo> radiationFiles,
            List<XRayFileInfo> xrayFiles,
            String analysisAssessment,
            String analysisResult,
            String analysisAlgorithmName,
            String analysisVendor,
            List<AnnotationInfo> annotations,
            String previewEntryPath,
            long previewSizeBytes
    ) {}

    /**
     * Compact search/index item returned to the UI during SMB browse, Vehicle ID correlation,
     * and omnibox UUID / filename lookup.
     */
    public record UffSearchMatch(
            String uffUuid,
            String uffVersion,
            String archiveFileName,
            String relativePath,
            long archiveSizeBytes,
            String scannerId,
            String scannerManufacturer,
            String scannerModel,
            String scanTimestamp,
            long scanEpochMillis,
            String containerNum,
            String frontPlateNum,
            List<String> displayVehicleIds,
            String hsCode,
            String goodsDescription,
            Double containerWeightKg,
            boolean hasPreview,
            boolean hasHemd,
            boolean hasManifestPdf,
            boolean hasRadiationN42,
            int annotationCount,
            String analysisResult,
            boolean fuzzyMatch,
            String matchedIdentifier,
            Long timeDeltaSeconds
    ) {}
}

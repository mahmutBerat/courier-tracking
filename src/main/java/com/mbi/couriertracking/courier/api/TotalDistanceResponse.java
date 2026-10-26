package com.mbi.couriertracking.courier.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record TotalDistanceResponse(
        @Schema(example = "courier-1") String courierId,
        @Schema(example = "981.66") double distanceMeters,
        @Schema(example = "Ataşehir MMM Migros", description = "Last entered store; null before the first entry", nullable = true) String lastStoreName,
        @Schema(example = "2026-10-21T12:00:00Z", description = "Time of the last physical entry; null before the first entry", nullable = true) Instant lastStoreEntryTime,
        @Schema(example = "in-store", allowableValues = {"in-store", "out-store"}) String currentStatus,
        @Schema(example = "true", description = "True when the last processed report opened a new store visit (a logged entrance)") boolean isEntry
) {
}

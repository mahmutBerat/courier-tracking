package com.mbi.couriertracking.courier.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;

import java.time.Instant;

public record CourierLocationInput(
        @Schema(example = "2026-10-21T12:00:00Z", description = "Input instant; truncated to milliseconds") @NotNull Instant time,
        @Schema(example = "courier-1") @NotBlank String courier,
        @Schema(example = "40.9923307") @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @Schema(example = "29.1244229") @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng
) {
}

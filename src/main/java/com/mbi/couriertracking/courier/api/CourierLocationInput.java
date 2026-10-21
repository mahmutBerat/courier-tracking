package com.mbi.couriertracking.courier.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CourierLocationInput(
        @NotNull Instant time,
        @NotBlank String courier,
        @NotNull Double lat,
        @NotNull Double lng
) {
}

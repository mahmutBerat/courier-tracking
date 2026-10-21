package com.mbi.couriertracking.courier.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateCourierRequest(@Schema(example = "courier-1") @NotBlank String courierId) {
}

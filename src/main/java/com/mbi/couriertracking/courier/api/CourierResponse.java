package com.mbi.couriertracking.courier.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record CourierResponse(@Schema(example = "courier-1") String courierId) {
}

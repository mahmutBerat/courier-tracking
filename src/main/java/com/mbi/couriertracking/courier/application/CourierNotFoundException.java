package com.mbi.couriertracking.courier.application;

import lombok.Getter;

/**
 * Raised when a use case requires a registered courier that does not exist.
 */
@Getter
public class CourierNotFoundException extends RuntimeException {

    private final String courierId;

    public CourierNotFoundException(String courierId) {
        super("Courier not found: " + courierId);
        this.courierId = courierId;
    }
}

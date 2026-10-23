package com.mbi.couriertracking.courier.application;

import lombok.Getter;

/**
 * Raised when registration is attempted for a courier ID that already exists.
 */
@Getter
public class CourierAlreadyExistsException extends RuntimeException {

    private final String courierId;

    public CourierAlreadyExistsException(String courierId) {
        super("Courier already exists: " + courierId);
        this.courierId = courierId;
    }
}

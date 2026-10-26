package com.mbi.couriertracking.courier.domain;

/** Whether the courier is currently inside a store area; derived from the courier's current store. */
public enum StoreStatus {
    IN_STORE("in-store"),
    OUT_STORE("out-store");

    private final String value;

    StoreStatus(String value) {
        this.value = value;
    }

    /** The API representation. */
    public String value() {
        return value;
    }
}

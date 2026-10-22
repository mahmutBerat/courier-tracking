package com.mbi.couriertracking.store.domain;

import com.mbi.couriertracking.common.geo.GeoLocation;

/** A supplied Migros store; the name is its identity. */
public record Store(String name, GeoLocation location) {

    public Store {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Store name must not be blank.");
        }
        if (location == null) {
            throw new IllegalArgumentException("Store location is required.");
        }
    }
}

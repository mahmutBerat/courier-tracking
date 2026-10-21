package com.mbi.couriertracking.common.geo;

/** Geographic coordinates in degrees. */
public record GeoLocation(double latitude, double longitude) {

    public GeoLocation {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be finite and between -90 and 90 degrees.");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be finite and between -180 and 180 degrees.");
        }
    }
}

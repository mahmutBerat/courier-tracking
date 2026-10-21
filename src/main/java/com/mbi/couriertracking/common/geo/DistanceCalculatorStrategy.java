package com.mbi.couriertracking.common.geo;

/** Strategy for geographic separation in meters. */
public interface DistanceCalculatorStrategy {

    double distanceMeters(GeoLocation from, GeoLocation to);
}

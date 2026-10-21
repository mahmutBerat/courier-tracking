package com.mbi.couriertracking.common.geo;

import org.springframework.stereotype.Component;

@Component
public class HaversineDistanceCalculatorStrategy implements DistanceCalculatorStrategy {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    @Override
    public double distanceMeters(GeoLocation from, GeoLocation to) {
        double latitudeFrom = Math.toRadians(from.latitude());
        double latitudeTo = Math.toRadians(to.latitude());
        double latitudeDelta = Math.toRadians(to.latitude() - from.latitude());
        double longitudeDelta = Math.toRadians(Math.IEEEremainder(to.longitude() - from.longitude(), 360));

        double latitudeSin = Math.sin(latitudeDelta / 2);
        double longitudeSin = Math.sin(longitudeDelta / 2);
        double a = latitudeSin * latitudeSin
                + Math.cos(latitudeFrom) * Math.cos(latitudeTo) * longitudeSin * longitudeSin;
        // Floating-point drift near antipodal points must not make either square root negative.
        a = Math.clamp(a, 0, 1);
        double centralAngle = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * centralAngle;
    }
}

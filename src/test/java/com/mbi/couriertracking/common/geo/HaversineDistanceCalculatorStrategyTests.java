package com.mbi.couriertracking.common.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaversineDistanceCalculatorStrategyTests {

    private final DistanceCalculatorStrategy calculator = new HaversineDistanceCalculatorStrategy();

    @Test
    void identicalLocationsHaveZeroDistance() {
        var point = new GeoLocation(40.9923307, 29.1244229);
        assertEquals(0.0, calculator.distanceMeters(point, point));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0, 1, 111194.926644559",
            "0, 0, 1, 0, 111194.926644559",
            "0, 0, 0, 90, 10007543.398010286",
            "0, 179.5, 0, -179.5, 111194.926644559",
            "0, 0, 0, 180, 20015086.796020572",
            "90, 0, -90, 0, 20015086.796020572"
    })
    void calculatesKnownSphericalDistances(double fromLat, double fromLng,
                                           double toLat, double toLng, double expectedMeters) {
        var from = new GeoLocation(fromLat, fromLng);
        var to = new GeoLocation(toLat, toLng);

        assertEquals(expectedMeters, calculator.distanceMeters(from, to), 0.000001);
        assertEquals(expectedMeters, calculator.distanceMeters(to, from), 0.000001);
    }

    @Test
    void equivalentDateLineCoordinatesHaveZeroDistance() {
        assertEquals(0.0, calculator.distanceMeters(new GeoLocation(40, 180), new GeoLocation(40, -180)));
    }

    @Test
    void nearlyAntipodalPointsProduceFiniteDistance() {
        double distance = calculator.distanceMeters(new GeoLocation(40, 29),
                new GeoLocation(-40.00000001, -150.99999999));
        assertTrue(Double.isFinite(distance));
        assertEquals(20015086.796020572, distance, 1.0);
    }
}

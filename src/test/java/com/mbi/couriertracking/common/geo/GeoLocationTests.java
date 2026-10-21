package com.mbi.couriertracking.common.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoLocationTests {

    @ParameterizedTest
    @ValueSource(doubles = {-90.000001, 90.000001, Double.NaN,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsInvalidLatitude(double latitude) {
        assertThrows(IllegalArgumentException.class, () -> new GeoLocation(latitude, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.000001, 180.000001, Double.NaN,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsInvalidLongitude(double longitude) {
        assertThrows(IllegalArgumentException.class, () -> new GeoLocation(0, longitude));
    }

    @Test
    void acceptsInclusiveBoundsAsImmutableValues() {
        assertEquals(new GeoLocation(-90, -180), new GeoLocation(-90, -180));
        assertEquals(new GeoLocation(90, 180), new GeoLocation(90, 180));
    }
}

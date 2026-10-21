package com.mbi.couriertracking.courier.domain;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.common.geo.HaversineDistanceCalculatorStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CourierTests {

    private final Instant start = Instant.parse("2026-10-21T12:00:00Z");
    private final DistanceCalculatorStrategy distance = new HaversineDistanceCalculatorStrategy();

    @Test
    void firstLocationStartsAtZeroWithoutCalculatingASegment() {
        var courier = new Courier("courier-1");
        courier.updateDistance(new GeoLocation(0, 0), start,
                (from, to) -> {
                    throw new AssertionError("First input has no segment");
                });

        assertEquals(0.0, courier.getTotalDistanceMeters());
        assertEquals(start, courier.getLastProcessedTime());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void ignoredLocationDoesNotBecomeNextSegmentOrigin(long offsetSeconds) {
        var courier = new Courier("courier-1");
        courier.updateDistance(new GeoLocation(0, 0), start, distance);
        courier.updateDistance(new GeoLocation(0, 1), start.plusSeconds(2), distance);
        courier.updateDistance(new GeoLocation(50, 50), start.plusSeconds(2 + offsetSeconds), distance);
        courier.updateDistance(new GeoLocation(0, 2), start.plusSeconds(3), distance);

        assertEquals(222389.853289117, courier.getTotalDistanceMeters(), 0.000001);
        assertEquals(0.0, courier.getLastLatitude());
        assertEquals(2.0, courier.getLastLongitude());
        assertEquals(start.plusSeconds(3), courier.getLastProcessedTime());
    }

    @Test
    void orderingUsesTruncatedMilliseconds() {
        var courier = new Courier("courier-1");
        courier.updateDistance(new GeoLocation(0, 0), start.plusNanos(123_000_001), distance);
        courier.updateDistance(new GeoLocation(1, 1), start.plusNanos(123_999_999), distance);

        assertEquals(0.0, courier.getTotalDistanceMeters());
        assertEquals(0.0, courier.getLastLatitude());
        assertEquals(start.plusMillis(123), courier.getLastProcessedTime());
    }

    @Test
    void newerStationaryLocationAdvancesTimeWithoutDistance() {
        var courier = new Courier("courier-1");
        courier.updateDistance(new GeoLocation(1, 2), start, distance);
        courier.updateDistance(new GeoLocation(1, 2), start.plusSeconds(1), distance);

        assertEquals(0.0, courier.getTotalDistanceMeters());
        assertEquals(start.plusSeconds(1), courier.getLastProcessedTime());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidCalculatedDistanceLeavesPriorStateUnchanged(double segment) {
        var courier = new Courier("courier-1");
        courier.updateDistance(new GeoLocation(1, 2), start, distance);

        assertThrows(IllegalStateException.class,
                () -> courier.updateDistance(new GeoLocation(3, 4), start.plusSeconds(1),
                        (from, to) -> segment));

        assertEquals(0.0, courier.getTotalDistanceMeters());
        assertEquals(1.0, courier.getLastLatitude());
        assertEquals(2.0, courier.getLastLongitude());
        assertEquals(start, courier.getLastProcessedTime());
    }
}

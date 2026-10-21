package com.mbi.couriertracking.courier.domain;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.common.geo.GeoLocation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Current travel state; input history is not needed to advance it. */
@Entity
@Table(name = "couriers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Courier {

    @Id
    @Column(name = "courier_id", columnDefinition = "text")
    private String courierId;

    @Column(name = "last_latitude")
    private Double lastLatitude;

    @Column(name = "last_longitude")
    private Double lastLongitude;

    @Column(name = "last_processed_time")
    private Instant lastProcessedTime;

    @Column(name = "total_distance_meters", nullable = false)
    private double totalDistanceMeters;

    public Courier(String courierId) {
        if (courierId == null || courierId.isBlank()) {
            throw new IllegalArgumentException("Courier ID must not be blank.");
        }
        this.courierId = courierId;
    }

    public void updateDistance(GeoLocation location, Instant inputTime, DistanceCalculatorStrategy calculator) {
        Instant time = inputTime.truncatedTo(ChronoUnit.MILLIS);
        if (lastProcessedTime != null && !time.isAfter(lastProcessedTime)) {
            return;
        }

        double segment = lastProcessedTime == null ? 0
                : calculator.distanceMeters(new GeoLocation(lastLatitude, lastLongitude), location);
        double updatedTotal = totalDistanceMeters + segment;
        if (segment < 0 || !Double.isFinite(updatedTotal)) {
            throw new IllegalStateException("Distance calculation must produce a finite nonnegative total.");
        }
        totalDistanceMeters = updatedTotal;
        lastLatitude = location.latitude();
        lastLongitude = location.longitude();
        lastProcessedTime = time;
    }
}

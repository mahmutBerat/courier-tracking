package com.mbi.couriertracking.courier.domain;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Current travel state and store summary; input history is not needed to advance either.
 * The summary mirrors the courier's store visits: the store currently inside (if any), the last
 * entered store, and the time of that entry.
 */
@Entity
@Table(name = "couriers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Courier extends BaseEntity {

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

    /** Null while the courier is outside every store. */
    @Column(name = "current_store_name", columnDefinition = "text")
    private String currentStoreName;

    @Column(name = "last_store_name", columnDefinition = "text")
    private String lastStoreName;

    @Column(name = "last_store_entry_time")
    private Instant lastStoreEntryTime;

    /** True when the last processed report opened a new visit (a logged entrance); false after any other processed report. */
    @Column(name = "last_report_entered", nullable = false)
    private boolean lastReportEntered;

    public Courier(String courierId) {
        if (courierId == null || courierId.isBlank()) {
            throw new IllegalArgumentException("Courier ID must not be blank.");
        }
        this.courierId = courierId;
    }

    /**
     * Adds the segment from the last processed location when the report is strictly newer.
     *
     * @return true when the report advanced travel state; false when it was older or same-time and ignored
     */
    public boolean updateDistance(GeoLocation location, Instant inputTime, DistanceCalculatorStrategy calculator) {
        Instant time = inputTime.truncatedTo(ChronoUnit.MILLIS);
        if (lastProcessedTime != null && !time.isAfter(lastProcessedTime)) {
            return false;
        }

        double distance = lastProcessedTime == null ? 0 : calculator.distanceMeters(new GeoLocation(lastLatitude, lastLongitude), location);
        double updatedTotal = totalDistanceMeters + distance;

        if (distance < 0 || !Double.isFinite(updatedTotal)) {
            throw new IllegalStateException("Distance calculation must produce a finite nonnegative total.");
        }
        totalDistanceMeters = updatedTotal;
        lastLatitude = location.latitude();
        lastLongitude = location.longitude();
        lastProcessedTime = time;
        return true;
    }

    /** Remembers the visit entered or resumed by a report; the current store is set separately once every store is evaluated. */
    public void recordStoreEntry(String storeName, Instant entryTime) {
        lastStoreName = storeName;
        lastStoreEntryTime = entryTime.truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * @param storeName the store the courier is inside after the report, empty when outside every store
     * @param entrance  whether the report opened a new visit
     */
    public void updateCurrentStore(Optional<String> storeName, boolean entrance) {
        if (storeName.isPresent() && lastStoreName == null) {
            throw new IllegalStateException("A courier cannot be inside a store before its first entry is recorded.");
        }
        currentStoreName = storeName.orElse(null);
        lastReportEntered = entrance;
    }

    public StoreStatus getStoreStatus() {
        return currentStoreName == null ? StoreStatus.OUT_STORE : StoreStatus.IN_STORE;
    }
}

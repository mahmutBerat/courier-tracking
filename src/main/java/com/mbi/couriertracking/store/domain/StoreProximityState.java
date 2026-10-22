package com.mbi.couriertracking.store.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Inside/outside state of one courier–store pair and the time of its latest physical entry.
 * Owns the entry rules: the inclusive 100 m boundary and the one-minute re-entry rule.
 * A pair that has never entered the store has no persisted row; {@link #initial} represents it.
 */
@Entity
@Table(name = "courier_store_proximity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreProximityState {

    public static final double ENTRY_RADIUS_METERS = 100;
    public static final Duration MINIMUM_INTERVAL_BETWEEN_COUNTED_ENTRIES = Duration.ofSeconds(60);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "courier_id", nullable = false, columnDefinition = "text")
    private String courierId;

    @Column(name = "store_name", nullable = false, columnDefinition = "text")
    private String storeName;

    @Column(nullable = false)
    private boolean inside;

    /** Null only before the pair's first physical entry; such a state is never persisted. */
    @Column(name = "last_physical_entry_time")
    private Instant lastPhysicalEntryTime;

    private StoreProximityState(String courierId, String storeName) {
        this.courierId = courierId;
        this.storeName = storeName;
    }

    /** State of a pair with no prior observation: outside, no entry yet. */
    public static StoreProximityState initial(String courierId, String storeName) {
        return new StoreProximityState(courierId, storeName);
    }

    /**
     * Applies one observation. Observations arrive in increasing input time because only strictly
     * newer reports are processed.
     *
     * @return the physical entry when the courier moved from outside to inside, otherwise empty
     */
    public Optional<StoreEntry> checkStoreEntry(double distanceMeters, Instant time) {
        boolean insideNow = distanceMeters <= ENTRY_RADIUS_METERS;
        boolean entered = insideNow && !inside;
        inside = insideNow;
        if (!entered) {
            return Optional.empty();
        }

        boolean counted = isCountedEntry(time);
        lastPhysicalEntryTime = time;
        return Optional.of(new StoreEntry(courierId, storeName, time, counted));
    }

    /** The first entry always counts; a re-entry counts only after the minimum interval. */
    private boolean isCountedEntry(Instant time) {
        if (lastPhysicalEntryTime == null) {
            return true;
        }
        Duration sincePreviousEntry = Duration.between(lastPhysicalEntryTime, time);
        return sincePreviousEntry.compareTo(MINIMUM_INTERVAL_BETWEEN_COUNTED_ENTRIES) >= 0;
    }
}

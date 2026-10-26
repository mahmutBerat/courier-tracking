package com.mbi.couriertracking.store.domain;

import com.mbi.couriertracking.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * One visit of a courier to a store area, which is one logged entrance. It opens on a physical
 * entry, closes on the first report outside the area, and resumes when the courier is back inside
 * less than one minute after the entry: such a re-entry is not a new entrance and adds no row. An
 * open visit means the courier is inside. Owns the inclusive 100 m boundary and the one-minute rule.
 */
@Entity
@Table(name = "courier_store_visits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreVisit extends BaseEntity {

    public static final double ENTRY_RADIUS_METERS = 100;
    public static final Duration MINIMUM_INTERVAL_BETWEEN_ENTRIES = Duration.ofSeconds(60);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "courier_id", nullable = false, columnDefinition = "text")
    private String courierId;

    @Column(name = "store_name", nullable = false, columnDefinition = "text")
    private String storeName;

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    /** Null while the courier is inside. */
    @Column(name = "exit_time")
    private Instant exitTime;

    private StoreVisit(String courierId, String storeName, Instant entryTime) {
        this.courierId = courierId;
        this.storeName = storeName;
        this.entryTime = entryTime;
    }

    public static boolean isInside(double distanceMeters) {
        return distanceMeters <= ENTRY_RADIUS_METERS;
    }

    /** A new entrance: the pair's first visit, or a re-entry at least the minimum interval after the previous entry. */
    public static StoreVisit open(String courierId, String storeName, Instant entryTime) {
        return new StoreVisit(courierId, storeName, entryTime);
    }

    /**
     * True when being back inside at this time is less than the minimum interval after this visit's
     * entry, so the re-entry continues this visit instead of opening a new one.
     */
    public boolean continuesAt(Instant reentryTime) {
        Duration sinceEntry = Duration.between(entryTime, reentryTime);
        return sinceEntry.compareTo(MINIMUM_INTERVAL_BETWEEN_ENTRIES) < 0;
    }

    /** Reopens a closed visit after a brief absence; the entry time stays. */
    public void resume() {
        if (isOpen()) {
            throw new IllegalStateException("Visit is already open.");
        }
        this.exitTime = null;
    }

    public void close(Instant exitTime) {
        if (!isOpen()) {
            throw new IllegalStateException("Visit is already closed.");
        }
        this.exitTime = exitTime;
    }

    public boolean isOpen() {
        return exitTime == null;
    }
}

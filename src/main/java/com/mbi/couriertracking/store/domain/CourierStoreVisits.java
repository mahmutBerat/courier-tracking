package com.mbi.couriertracking.store.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The latest visit of each store a courier has ever entered, loaded to evaluate one location
 * report. An open latest visit means the courier is inside that store; a store never entered has
 * no visit. Applies the report store by store: opens a visit on entry (or resumes the latest one
 * when the courier is back inside less than one minute after its entry), closes the open visit on
 * exit, and then reports the outcome. Reports arrive in increasing input time because only
 * strictly newer reports are processed.
 */
public class CourierStoreVisits {

    private final String courierId;
    private final Map<String, StoreVisit> latestByStoreName;
    private StoreVisit entered;
    private boolean entrance;

    private CourierStoreVisits(String courierId, Map<String, StoreVisit> latestByStoreName) {
        this.courierId = courierId;
        this.latestByStoreName = latestByStoreName;
    }

    /** @param latestPerStore at most one visit per store: the one with the greatest entry time */
    public static CourierStoreVisits of(String courierId, List<StoreVisit> latestPerStore) {
        Map<String, StoreVisit> latestByStoreName = new HashMap<>();
        for (StoreVisit visit : latestPerStore) {
            latestByStoreName.put(visit.getStoreName(), visit);
        }
        return new CourierStoreVisits(courierId, latestByStoreName);
    }

    /**
     * Applies the report's distance to one store.
     *
     * @return the visit opened, resumed, or closed by it, which needs saving; empty when the
     * courier stayed on the same side of the boundary
     */
    public Optional<StoreVisit> checkProximity(String storeName, double distanceMeters, Instant time) {
        StoreVisit latest = latestByStoreName.get(storeName);
        boolean wasInside = latest != null && latest.isOpen();
        boolean inside = StoreVisit.isInside(distanceMeters);
        if (inside == wasInside) {
            return Optional.empty();
        }
        if (!inside) {
            latest.close(time);
            return Optional.of(latest);
        }
        if (latest != null && latest.continuesAt(time)) {
            latest.resume(); // a re-entry within one minute is not a new entrance
            entered = latest;
        } else {
            entered = StoreVisit.open(courierId, storeName, time);
            entrance = true;
            latestByStoreName.put(storeName, entered);
        }
        return Optional.of(entered);
    }

    /** The visit opened or resumed by this report, if any, and the store the courier is inside afterwards. */
    public StoreVisitResult outcome() {
        return new StoreVisitResult(Optional.ofNullable(entered), entrance, currentStoreName());
    }

    /** The store entered by this report, else the most recently entered store the courier is still inside. */
    private Optional<String> currentStoreName() {
        if (entered != null) {
            return Optional.of(entered.getStoreName());
        }
        return latestByStoreName.values().stream()
                .filter(StoreVisit::isOpen)
                .max(Comparator.comparing(StoreVisit::getEntryTime))
                .map(StoreVisit::getStoreName);
    }
}

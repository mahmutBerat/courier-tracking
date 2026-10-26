package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.store.domain.CourierStoreVisits;
import com.mbi.couriertracking.store.domain.Store;
import com.mbi.couriertracking.store.domain.StoreCatalog;
import com.mbi.couriertracking.store.domain.StoreVisitResult;
import com.mbi.couriertracking.store.persistence.StoreVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Evaluates one processed courier location against every store in the catalog: opens a visit on
 * entry, closes the open visit on exit, and reports which store the courier is inside afterwards.
 * Runs inside the caller's courier transaction, which already holds the courier row lock, so
 * visits need no lock of their own.
 */
@Service
@RequiredArgsConstructor
public class StoreEntryDetector {

    private final StoreCatalog storeCatalog;
    private final StoreVisitRepository storeVisitRepository;
    private final DistanceCalculatorStrategy distanceCalculator;

    @Transactional(propagation = Propagation.MANDATORY)
    public StoreVisitResult detectEntries(String courierId, GeoLocation location, Instant inputTime) {
        // One query: the latest visit per store carries both "open?" and the previous entry time.
        CourierStoreVisits visits = CourierStoreVisits.of(courierId, storeVisitRepository.findLatestPerStore(courierId));

        for (Store store : storeCatalog.stores()) {
            double distanceMeters = distanceCalculator.distanceMeters(location, store.location());
            visits.checkProximity(store.name(), distanceMeters, inputTime).ifPresent(storeVisitRepository::save);
        }
        return visits.outcome();
    }
}

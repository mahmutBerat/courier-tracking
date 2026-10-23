package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.store.domain.ProximityCheck;
import com.mbi.couriertracking.store.domain.Store;
import com.mbi.couriertracking.store.domain.StoreCatalog;
import com.mbi.couriertracking.store.domain.StoreProximityState;
import com.mbi.couriertracking.store.persistence.StoreEntryRepository;
import com.mbi.couriertracking.store.persistence.StoreProximityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evaluates one processed courier location against every store in the catalog, records each
 * entry, and keeps the inside/outside state of every pair current, so leaving one store while
 * entering another is recorded. Runs inside the caller's courier transaction, which already
 * holds the courier row lock, so pair state needs no lock of its own.
 */
@Service
@RequiredArgsConstructor
public class StoreEntryDetector {

    private final StoreCatalog storeCatalog;
    private final StoreProximityStateRepository proximityStateRepository;
    private final StoreEntryRepository storeEntryRepository;
    private final DistanceCalculatorStrategy distanceCalculator;

    @Transactional(propagation = Propagation.MANDATORY)
    public void detectEntries(String courierId, GeoLocation location, Instant inputTime) {
        // One query for the courier's pair states (at most one per store) instead of one per store.
        Map<String, StoreProximityState> statesByStoreName = proximityStateRepository.findByCourierId(courierId).stream()
                .collect(Collectors.toMap(StoreProximityState::getStoreName, Function.identity()));

        for (Store store : storeCatalog.stores()) {
            double distanceMeters = distanceCalculator.distanceMeters(location, store.location());

            StoreProximityState state = statesByStoreName.getOrDefault(store.name(),
                    StoreProximityState.initial(courierId, store.name()));

            ProximityCheck check = state.checkProximity(distanceMeters, inputTime);
            if (check.changedState()) {
                proximityStateRepository.save(state);
            }
            check.entry().ifPresent(storeEntryRepository::save);
        }
    }

}

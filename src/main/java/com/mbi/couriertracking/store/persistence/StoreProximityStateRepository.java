package com.mbi.couriertracking.store.persistence;

import com.mbi.couriertracking.store.domain.StoreProximityState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreProximityStateRepository extends JpaRepository<StoreProximityState, Long> {

    /** All pair states of one courier; at most one per store. */
    List<StoreProximityState> findByCourierId(String courierId);

    Optional<StoreProximityState> findByCourierIdAndStoreName(String courierId, String storeName);
}

package com.mbi.couriertracking.store.persistence;

import com.mbi.couriertracking.store.domain.StoreEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreEntryRepository extends JpaRepository<StoreEntry, Long> {

    List<StoreEntry> findByCourierIdOrderByEntryTime(String courierId);
}

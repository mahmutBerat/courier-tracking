package com.mbi.couriertracking.store.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One physical entry of a courier into a store area. {@code counted} is false when the
 * one-minute rule suppressed it; the entry is still recorded for traceability.
 */
@Entity
@Table(name = "store_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "courier_id", nullable = false, columnDefinition = "text")
    private String courierId;

    @Column(name = "store_name", nullable = false, columnDefinition = "text")
    private String storeName;

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(nullable = false)
    private boolean counted;

    StoreEntry(String courierId, String storeName, Instant entryTime, boolean counted) {
        this.courierId = courierId;
        this.storeName = storeName;
        this.entryTime = entryTime;
        this.counted = counted;
    }
}

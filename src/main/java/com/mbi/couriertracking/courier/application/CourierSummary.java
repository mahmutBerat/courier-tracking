package com.mbi.couriertracking.courier.application;

import com.mbi.couriertracking.courier.domain.StoreStatus;

import java.time.Instant;

/**
 * Read model of one courier for the API layer: travel total and store summary. Entities stay
 * behind the service boundary.
 */
public record CourierSummary(String courierId, double totalDistanceMeters, String lastStoreName,
                             Instant lastStoreEntryTime, StoreStatus storeStatus, boolean lastReportEntered) {
}

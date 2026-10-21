package com.mbi.couriertracking.courier.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CourierLocationRepository extends JpaRepository<CourierLocationRecord, Long> {
}

package com.mbi.couriertracking.courier.persistence;

import com.mbi.couriertracking.courier.domain.Courier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CourierRepository extends JpaRepository<Courier, String> {

    @Modifying
    @Query(value = """
            INSERT INTO couriers (courier_id, total_distance_meters)
            VALUES (:courierId, 0)
            ON CONFLICT (courier_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("courierId") String courierId);

    @Query(value = "SELECT * FROM couriers WHERE courier_id = :courierId FOR UPDATE", nativeQuery = true)
    Optional<Courier> findForUpdate(@Param("courierId") String courierId);
}

package com.mbi.couriertracking.courier.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourierLocationRepository extends JpaRepository<CourierLocationRecord, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO courier_location_inputs
                (input_time, courier_id, latitude, longitude)
            VALUES (:#{#record.time}, :#{#record.courierId},
                    :#{#record.latitude}, :#{#record.longitude})
            ON CONFLICT ON CONSTRAINT uq_courier_location_input DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("record") CourierLocationRecord record);
}

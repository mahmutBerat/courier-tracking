package com.mbi.couriertracking.courier.persistence;

import com.mbi.couriertracking.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Input history for traceability, not current courier calculation state.
 */
@Entity
@Table(name = "courier_location_inputs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourierLocationRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "input_time", nullable = false)
    private Instant time;

    @Column(name = "courier_id", nullable = false, columnDefinition = "text")
    private String courierId;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    public CourierLocationRecord(Instant time, String courierId, double latitude, double longitude) {
        this.time = time.truncatedTo(ChronoUnit.MILLIS); // TODO do we need to truncate?
        this.courierId = courierId;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}

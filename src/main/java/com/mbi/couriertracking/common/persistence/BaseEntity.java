package com.mbi.couriertracking.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Row audit timestamps shared by every JPA entity: when the row was inserted and when it was last
 * written. Both are wall-clock times with millisecond precision, set by JPA lifecycle callbacks;
 * native inserts that bypass JPA rely on the matching {@code DEFAULT now()} of the columns.
 * They are not business time: domain rules use the input time of reports only.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void stampCreation() {
        Instant now = now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void stampUpdate() {
        updatedAt = now();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}

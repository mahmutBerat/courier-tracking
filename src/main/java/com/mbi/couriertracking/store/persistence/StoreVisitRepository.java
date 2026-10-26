package com.mbi.couriertracking.store.persistence;

import com.mbi.couriertracking.store.domain.StoreVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StoreVisitRepository extends JpaRepository<StoreVisit, Long> {

    /**
     * The latest visit of each store the courier has ever entered; an open one means the courier is inside.
     * JPQL rather than native SQL so that Hibernate flushes only pending visit changes before the
     * read and leaves the dirty courier for the commit (one UPDATE couriers per report).
     */
    @Query("""
            SELECT v FROM StoreVisit v
            WHERE v.courierId = :courierId
              AND v.entryTime = (SELECT MAX(v2.entryTime) FROM StoreVisit v2
                                 WHERE v2.courierId = v.courierId AND v2.storeName = v.storeName)
            """)
    List<StoreVisit> findLatestPerStore(@Param("courierId") String courierId);

    List<StoreVisit> findByCourierIdOrderByEntryTime(String courierId);
}

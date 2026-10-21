package com.mbi.couriertracking.courier.application;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.persistence.CourierLocationMapper;
import com.mbi.couriertracking.courier.persistence.CourierLocationRecord;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Stores distinct inputs and advances current travel state atomically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourierService {

    private final CourierLocationRepository locationRepository;
    private final CourierLocationMapper locationMapper;
    private final CourierRepository courierRepository;
    private final DistanceCalculatorStrategy distanceCalculator;

    @Transactional
    public void createCourier(String courierId) {
        if (courierRepository.insertIfAbsent(courierId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Courier already exists.");
        }
    }

    @Transactional(readOnly = true)
    public List<Courier> listCouriers() {
        return courierRepository.findAll(Sort.by("courierId"));
    }

    @Transactional
    public void submitLocation(CourierLocationInput input) {
        // TODO: Consider a bounded cache of committed inputs to skip DB access for recent duplicates.
        Courier courier = courierRepository.findForUpdate(input.courier())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier not found."));
        CourierLocationRecord record = locationMapper.toRecord(input);
        if (locationRepository.insertIfAbsent(record) == 0) {
            log.debug("Location data already inserted {}", input);
            return;
        }
        courier.updateDistance(locationMapper.toGeoLocation(record), record.getTime(), distanceCalculator);
        // TODO: Detect store entries and apply the per-pair one-minute counting rule.
    }

    @Transactional(readOnly = true)
    public OptionalDouble findTotalDistanceMeters(String courierId) {
        return courierRepository.findById(courierId)
                .map(courier -> OptionalDouble.of(courier.getTotalDistanceMeters()))
                .orElseGet(OptionalDouble::empty);
    }
}

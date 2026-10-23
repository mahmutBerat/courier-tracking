package com.mbi.couriertracking.courier.application;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.persistence.CourierLocationMapper;
import com.mbi.couriertracking.courier.persistence.CourierLocationRecord;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.application.StoreEntryDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Stores distinct inputs, advances travel state, and detects store entries in one transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourierService {

    private final CourierLocationRepository locationRepository;
    private final CourierLocationMapper locationMapper;
    private final CourierRepository courierRepository;
    private final DistanceCalculatorStrategy distanceCalculator;
    private final StoreEntryDetector storeEntryDetector;

    @Transactional
    public void createCourier(String courierId) {
        if (courierRepository.insertIfAbsent(courierId) == 0) {
            throw new CourierAlreadyExistsException(courierId);
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
                .orElseThrow(() -> new CourierNotFoundException(input.courier()));
        CourierLocationRecord courierLocation = locationMapper.toRecord(input);
        if (locationRepository.insertIfAbsent(courierLocation) == 0) {
            log.debug("Location data already inserted {}", input);
            return;
        }

        GeoLocation location = locationMapper.toGeoLocation(courierLocation);

        boolean isUpdated = courier.updateDistance(location, courierLocation.getTime(), distanceCalculator);
        if (isUpdated) {
            storeEntryDetector.detectEntries(courier.getCourierId(), location, courierLocation.getTime());
        }
    }

    @Transactional(readOnly = true)
    public OptionalDouble findTotalDistanceMeters(String courierId) {
        return courierRepository.findById(courierId)
                .map(courier -> OptionalDouble.of(courier.getTotalDistanceMeters()))
                .orElseGet(OptionalDouble::empty);
    }
}

package com.mbi.couriertracking.courier.application;

import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.persistence.CourierLocationMapper;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.OptionalDouble;

/** Stores submitted inputs; distance and store-entry processing are pending. */
@Service
@RequiredArgsConstructor
public class CourierService {

    private final CourierLocationRepository locationRepository;
    private final CourierLocationMapper locationMapper;

    @Transactional
    public void submitLocation(CourierLocationInput input) {
        locationRepository.save(locationMapper.toRecord(input));
        // TODO: Update the courier's last processed location and total travel distance.
        // TODO: Detect store entries and apply the per-pair one-minute counting rule.
    }

    public OptionalDouble findTotalDistanceMeters(String courierId) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Total distance queries are not implemented yet.");
    }
}

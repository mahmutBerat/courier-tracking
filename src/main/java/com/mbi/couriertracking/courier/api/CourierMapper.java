package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.common.mapping.BaseMapperConfig;
import com.mbi.couriertracking.courier.application.CourierSummary;
import com.mbi.couriertracking.courier.domain.StoreStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = BaseMapperConfig.class)
public interface CourierMapper {

    CourierResponse toResponse(CourierSummary summary);

    @Mapping(target = "distanceMeters", source = "totalDistanceMeters")
    @Mapping(target = "currentStatus", source = "storeStatus")
    @Mapping(target = "isEntry", source = "lastReportEntered")
    TotalDistanceResponse toTotalDistanceResponse(CourierSummary summary);

    default String toStatusValue(StoreStatus status) {
        return status.value();
    }
}

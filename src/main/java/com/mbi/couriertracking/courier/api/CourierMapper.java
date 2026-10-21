package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.common.mapping.BaseMapperConfig;
import com.mbi.couriertracking.courier.domain.Courier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = BaseMapperConfig.class)
public interface CourierMapper {

    CourierResponse toResponse(Courier courier);

    @Mapping(target = "courierId", source = "courierId")
    @Mapping(target = "distanceMeters", source = "distanceMeters")
    TotalDistanceResponse toTotalDistanceResponse(String courierId, double distanceMeters);
}

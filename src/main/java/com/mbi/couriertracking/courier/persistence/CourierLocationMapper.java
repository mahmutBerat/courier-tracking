package com.mbi.couriertracking.courier.persistence;

import com.mbi.couriertracking.common.mapping.BaseMapperConfig;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = BaseMapperConfig.class)
public interface CourierLocationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "courierId", source = "courier")
    @Mapping(target = "latitude", source = "lat")
    @Mapping(target = "longitude", source = "lng")
    CourierLocationRecord toRecord(CourierLocationInput input);

    GeoLocation toGeoLocation(CourierLocationRecord record);
}

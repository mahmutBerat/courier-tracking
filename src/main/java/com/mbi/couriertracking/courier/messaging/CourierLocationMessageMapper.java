package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.common.mapping.BaseMapperConfig;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import org.mapstruct.Mapper;

@Mapper(config = BaseMapperConfig.class)
public interface CourierLocationMessageMapper {

    CourierLocationInput toInput(CourierLocationMessage message);
}

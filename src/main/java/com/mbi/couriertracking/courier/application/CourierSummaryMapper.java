package com.mbi.couriertracking.courier.application;

import com.mbi.couriertracking.common.mapping.BaseMapperConfig;
import com.mbi.couriertracking.courier.domain.Courier;
import org.mapstruct.Mapper;

@Mapper(config = BaseMapperConfig.class)
public interface CourierSummaryMapper {

    CourierSummary toSummary(Courier courier);
}

package com.ssafy.thorp.regime.infrastructure.persistence;

import com.ssafy.thorp.regime.domain.model.RegimeBaselineMonth;

/** 기준월 행을 도메인 모델로 옮긴다. <b>분야 이름은 여기 없다</b> — 그것은 등록표 포트가 준다(data-model §3). */
final class RegimeBaselineMonthMapper {

    private RegimeBaselineMonthMapper() {}

    static RegimeBaselineMonth toDomain(RegimeBaselineMonthJpaEntity entity) {
        return new RegimeBaselineMonth(
                entity.getScopeId(),
                JsonReader.toMonth(entity.getBaselineMonth()),
                JsonReader.toMonth(entity.getEarliestMonth()),
                entity.getRegistryVersion(),
                entity.getDataVersion());
    }
}

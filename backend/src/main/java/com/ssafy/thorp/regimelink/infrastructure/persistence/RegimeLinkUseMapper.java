package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;

/** {@code regime_link_use} 행과 도메인 사이의 변환 (data-model §4a). */
final class RegimeLinkUseMapper {

    private RegimeLinkUseMapper() {}

    static RegimeLinkUseJpaEntity toEntity(RegimeLinkUse use) {
        return new RegimeLinkUseJpaEntity(
                use.linkSearchId(),
                use.linkId(),
                use.role().name(),
                use.originRunId(),
                use.originRequestId(),
                use.createdRequestId());
    }

    static RegimeLinkUse toDomain(RegimeLinkUseJpaEntity entity) {
        return new RegimeLinkUse(
                entity.getId(),
                entity.getLinkSearchId(),
                entity.getLinkId(),
                LinkRole.valueOf(entity.getRole()),
                entity.getOriginRunId(),
                entity.getOriginRequestId(),
                entity.getCreatedRequestId(),
                entity.getCreatedAt());
    }
}

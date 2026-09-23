package com.ssafy.thorp.sector.domain.model;

import com.ssafy.thorp.sector.domain.vo.SectorCode;
import java.util.Objects;

/**
 * 섹터 하나와 그에 연결된 대표 ETF (FR-001).
 *
 * <p><b>불변식: 대표 ETF가 없는 섹터는 만들 수 없다.</b> SC-001이 "섹터 이름 선택 외에 어떤 자료도 입력하지 않고 진단을 시작할 수 있다"를 요구하므로,
 * 대표 ETF가 비어 있는 항목은 목록에 실려서는 안 된다. 저장 스키마도 {@code sector_etf_map_entry.etf_id}를 NOT NULL FK로 두어 같은
 * 규칙을 강제한다. 여기서 한 번 더 막는 이유는, 조회 경로가 조인을 잘못 짜서 빈 대표 ETF를 만들어 내는 실수를 값 생성 시점에 잡기 위함이다.
 */
public record Sector(
        SectorCode code,
        String name,
        Classification classification,
        RepresentativeEtf representativeEtf) {

    public Sector {
        Objects.requireNonNull(code, "sector code must not be null");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("sector name must not be blank: " + name);
        }
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(
                representativeEtf, "every sector must have a representative ETF (SC-001)");
    }

    /**
     * 섹터 구분. 저장 스키마의 {@code ck_sector_classification}과 같은 두 값이다(data-model.md).
     *
     * <p>중첩 enum으로 두는 이유: 이 값은 {@link Sector} 밖에서 홀로 쓰이지 않고, 두 값의 집합이 섹터 표의 CHECK 제약과 1:1로 묶여 있다.
     */
    public enum Classification {
        /** 업종. */
        INDUSTRY,
        /** 테마. */
        THEME
    }
}

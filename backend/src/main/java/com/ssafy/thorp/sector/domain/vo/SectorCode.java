package com.ssafy.thorp.sector.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 섹터 코드. 대응표의 외부 식별자이며 PK(id)는 노출하지 않는다(ADR-0002 D2).
 *
 * <p>패턴은 대응표를 발행하는 파이프라인({@code data/src/data_pipeline/sector_map.py}의 {@code
 * SECTOR_CODE_PATTERN})과 같은 값이다. 이 값은 URL과 JSON에 그대로 실리므로(contracts/sector-api.md) 두 쪽이 다른 집합을 허용하면
 * 파이프라인이 넣을 수 있는데 API가 거부하는 코드가 생긴다.
 */
public record SectorCode(String value) {

    /** 저장 스키마의 {@code sector.code VARCHAR(64)}와 같은 한도다. */
    public static final int MAX_LENGTH = 64;

    public static final String REGEX = "^[A-Z][A-Z0-9_]{1,63}$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public SectorCode {
        Objects.requireNonNull(value, "sector code must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid sector code format: " + value);
        }
    }
}

package com.ssafy.thorp.learning.domain.vo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 글의 예시 섹터와 비중(data-model §5). 값 표현은 002 selection-handoff 계약을 그대로 따른다. 비중은 0.1%p 단위 정수이고 {@code
 * 1000}이 100%다.
 *
 * <p>여기서 검사하는 것은 저장할 수 있는 모양이다(코드 형식, 중복 없음, {@code 0..1000}, 최대 20개). <b>합계 1000과 두 개 이상은 검사하지
 * 않는다.</b> 그것은 게시 전 검사의 몫이며, 초안은 쓰는 도중의 값을 담을 수 있어야 한다.
 *
 * <p>섹터 코드 형식은 001 {@code SectorCode}와 같은 규칙이지만 그 타입을 import하지 않는다. 도메인은 다른 도메인을 참조하지 않는다.
 */
public record ExampleAllocation(List<SectorWeight> sectors) {

    public static final int MAX_SECTORS = 20;
    public static final int FULL_WEIGHT_TENTHS = 1000;

    public ExampleAllocation {
        Objects.requireNonNull(sectors, "example sectors must not be null");
        sectors = List.copyOf(sectors);
        if (sectors.size() > MAX_SECTORS) {
            throw new IllegalArgumentException(
                    "example must have at most " + MAX_SECTORS + " sectors: " + sectors.size());
        }
        Set<String> seen = new HashSet<>();
        for (SectorWeight sector : sectors) {
            if (!seen.add(sector.code())) {
                throw new IllegalArgumentException(
                        "duplicate sector code in example: " + sector.code());
            }
        }
    }

    /** 비중 합계(0.1%p 단위). */
    public int totalWeightTenths() {
        return sectors.stream().mapToInt(SectorWeight::weightTenths).sum();
    }

    /** 예시 섹터 하나. */
    public record SectorWeight(String code, int weightTenths) {

        /** 001 {@code SectorCode.REGEX}와 같은 값이다. */
        public static final String CODE_REGEX = "^[A-Z][A-Z0-9_]{1,63}$";

        private static final Pattern CODE_PATTERN = Pattern.compile(CODE_REGEX);

        public SectorWeight {
            Objects.requireNonNull(code, "sector code must not be null");
            if (!CODE_PATTERN.matcher(code).matches()) {
                throw new IllegalArgumentException("invalid sector code format: " + code);
            }
            if (weightTenths < 0 || weightTenths > FULL_WEIGHT_TENTHS) {
                throw new IllegalArgumentException(
                        "weightTenths must be within 0.."
                                + FULL_WEIGHT_TENTHS
                                + ": "
                                + weightTenths);
            }
        }
    }
}

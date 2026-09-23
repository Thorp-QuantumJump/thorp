package com.ssafy.thorp.strategy.application.port;

import java.util.Objects;

/**
 * 전략과 무관한 분석 조건 하나의 등록 정보(data-model §7).
 *
 * <p>기본값을 범위와 따로 두지 않고 한 덩이로 담는다 — 둘이 흩어지면 기본값이 범위 밖으로 벗어난 채 화면이 열릴 수 있다. 로더가 기동 시 {@code
 * range.contains(defaultValue)}를 검사한다.
 *
 * @param range 허용 범위. 목록형·입력형 어느 쪽이든 된다
 * @param defaultValue 기본값. 범위 안이어야 한다
 * @param unit 단위 표시 문구. 없으면 null
 * @param usedBy 이 조건을 쓰는 시나리오. 주 분석에서 늘 쓰면 null이다
 */
public record AnalysisCondition(
        AllowedRange range, Object defaultValue, String unit, String usedBy) {

    public AnalysisCondition {
        Objects.requireNonNull(range, "analysis condition range must not be null");
        Objects.requireNonNull(defaultValue, "analysis condition default must not be null");
    }
}

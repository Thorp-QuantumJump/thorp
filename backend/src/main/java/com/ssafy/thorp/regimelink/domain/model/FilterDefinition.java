package com.ssafy.thorp.regimelink.domain.model;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 가용성 필터가 무엇으로 정해졌는지 (data-model §2, research R4).
 *
 * <p><b>성과에 의존하는 값이 없다</b>(FR-003). 기존 실행의 분석 기간도 넣지 않는다 — 기간은 {@code OUT_OF_PERIOD} 판정에만 쓰이고 필터
 * 조건이 아니라서, 넣으면 같은 묶음이 기간만 다른 두 키를 갖게 된다.
 *
 * @param instruments {@code instrumentId} 오름차순으로 정렬해 담는다. 순서가 흔들리면 같은 묶음이 다른 키를 낸다
 * @param maxWarmupTradingDays 묶음 안 실행들의 워밍업 거래일 최댓값
 * @param dataAsOf 자료 전체의 마감일
 * @param postCutoff 사후 완성 판정 기준일. 보통 {@code dataAsOf}이고, 검색 기준월이 과거면 그 달의 마지막 거래일이다(T090)
 */
public record FilterDefinition(
        List<InstrumentHistory> instruments,
        int maxWarmupTradingDays,
        int minPostTradingDays,
        String mappingVersion,
        String dataVersion,
        LocalDate dataAsOf,
        LocalDate postCutoff) {

    public FilterDefinition {
        Objects.requireNonNull(instruments, "instruments must not be null");
        Objects.requireNonNull(mappingVersion, "mappingVersion must not be null");
        Objects.requireNonNull(dataVersion, "dataVersion must not be null");
        Objects.requireNonNull(dataAsOf, "dataAsOf must not be null");
        Objects.requireNonNull(postCutoff, "postCutoff must not be null");
        if (maxWarmupTradingDays < 0) {
            throw new IllegalArgumentException("워밍업 거래일 수가 음수입니다: " + maxWarmupTradingDays);
        }
        instruments =
                instruments.stream()
                        .sorted(Comparator.comparing(InstrumentHistory::instrumentId))
                        .toList();
    }
}

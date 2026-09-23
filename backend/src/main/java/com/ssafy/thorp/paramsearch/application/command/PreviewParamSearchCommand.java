package com.ssafy.thorp.paramsearch.application.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 실행 전 확인 입력(contracts/param-search-api.md §1). 컨트롤러가 요청 DTO를 이 모양으로 바꿔 넘긴다.
 *
 * <p><b>후보 숫자를 받지 않는다.</b> 사용자가 고른 것은 전략과 섹터뿐이고, 몇 건을 돌릴지는 등록표와 한도가 정한다(SC-001).
 *
 * @param requesterId 요청자. 인증 도입 전에는 null(research R4)
 * @param instrumentIds 고정한 섹터 후보 집합
 * @param strategyIds 고른 전략. 순서를 보존한다
 * @param start 요청 구간 시작. 생략하면 서버가 공통 평가 구간을 제시한다
 * @param end 요청 구간 끝. 생략하면 서버가 정한다
 * @param initialCash 초기 금액. 생략하면 서버 기본값
 * @param space 범위 좁히기. 전략 ID → (축 이름 → 후보 값). <b>생략하면 등록표 전체 범위</b>(FR-002)
 */
public record PreviewParamSearchCommand(
        Long requesterId,
        List<String> instrumentIds,
        List<String> strategyIds,
        LocalDate start,
        LocalDate end,
        BigDecimal initialCash,
        Map<String, Map<String, List<Object>>> space) {

    public PreviewParamSearchCommand {
        Objects.requireNonNull(instrumentIds, "instrumentIds must not be null");
        Objects.requireNonNull(strategyIds, "strategyIds must not be null");
        instrumentIds = List.copyOf(instrumentIds);
        strategyIds = List.copyOf(strategyIds);
        space = space == null ? Map.of() : Map.copyOf(space);
    }
}

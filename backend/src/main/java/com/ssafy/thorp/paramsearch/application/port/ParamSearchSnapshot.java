package com.ssafy.thorp.paramsearch.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 묶음이 시작될 때 고정한 실행 조건. 후보와 기준 실행을 조립하는 데 필요한 전부다(FR-007·FR-012).
 *
 * <p><b>발행 시점에 현재 시각의 값을 다시 읽지 않기 위해 존재한다.</b> 후보 수백 건이 며칠에 걸쳐 발행될 수 있고, 그 사이 자료·비용 버전이 바뀌면 같은 묶음
 * 안의 후보들이 서로 다른 조건으로 계산된다 — 나란히 놓고 본 숫자가 비교 가능한 값이 아니게 된다(원칙 IV).
 *
 * <p>기준 전략과 그 파라미터도 여기 담는다. 어느 전략이 기준인지는 등록표가 정하므로(FR-013d) 발행 코드가 알 필요가 없고, 알아서도 안 된다(FR-025).
 *
 * @param paramSearchId 묶음 PK
 * @param searchId 외부 노출 식별자
 * @param ownerUserId 요청자. 인증 도입 전에는 null(research R4)
 * @param universe 고정한 섹터 후보 집합. 후보와 기준이 같은 집합을 쓴다
 * @param mappingVersion 섹터-종목 대응표 버전
 * @param start 요청 구간 시작
 * @param end 요청 구간 끝
 * @param initialCash 초기 금액
 * @param costProfileVersion 비용 조건 버전
 * @param evaluationSplitVersion 평가 분할 버전. 미등록이면 null(research R5)
 * @param warmupTradingDays 묶음 공통 워밍업 거래일 수. 후보 전략 전체의 최대 관측 기간으로 정한다(004 R-15)
 * @param registryVersion 접수 시점에 고정한 등록표 버전(FR-028)
 * @param rulesVersionByStrategy 전략별 규칙 버전. 004 등록표가 준 값이다
 * @param baselineStrategyId 손실 제약의 기준 전략. 등록표가 지정하지 않으면 null(FR-013d)
 * @param baselineParameters 기준 전략의 등록된 기본 시작값
 */
public record ParamSearchSnapshot(
        Long paramSearchId,
        String searchId,
        Long ownerUserId,
        List<UniverseEntry> universe,
        String mappingVersion,
        LocalDate start,
        LocalDate end,
        BigDecimal initialCash,
        String costProfileVersion,
        String evaluationSplitVersion,
        Integer warmupTradingDays,
        String registryVersion,
        Map<String, String> rulesVersionByStrategy,
        String baselineStrategyId,
        Map<String, Object> baselineParameters) {

    public ParamSearchSnapshot {
        Objects.requireNonNull(universe, "universe must not be null");
        if (universe.isEmpty()) {
            throw new IllegalArgumentException("universe must not be empty");
        }
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        Objects.requireNonNull(initialCash, "initialCash must not be null");
        universe = List.copyOf(universe);
        rulesVersionByStrategy =
                rulesVersionByStrategy == null ? Map.of() : Map.copyOf(rulesVersionByStrategy);
        baselineParameters = baselineParameters == null ? Map.of() : Map.copyOf(baselineParameters);
    }

    /** 후보 섹터 집합의 크기. 등록표의 유효성 규칙이 이 값을 볼 수 있다. */
    public SearchContext searchContext() {
        return new SearchContext(universe.size());
    }

    /** 전략의 규칙 버전. 없으면 null이고 004가 접수에서 422로 거부한다 — 007이 기본 버전을 만들어 넣지 않는다. */
    public String rulesVersionOf(String strategyId) {
        return rulesVersionByStrategy.get(strategyId);
    }

    /** 섹터 하나와 그 대표 종목. {@code targetWeight}는 비중을 사용자가 정하는 전략에서만 채워진다. */
    public record UniverseEntry(String sectorId, String instrumentId, BigDecimal targetWeight) {

        public UniverseEntry {
            if (instrumentId == null || instrumentId.isBlank()) {
                throw new IllegalArgumentException("instrumentId must not be blank");
            }
        }
    }
}

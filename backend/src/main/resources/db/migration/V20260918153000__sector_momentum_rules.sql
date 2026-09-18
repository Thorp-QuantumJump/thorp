-- 섹터 모멘텀 규칙 확정(spec 004 T023, PD-03, Jira S15P21E205-342).
--
-- PENDING_DECISION 이던 `SECTOR_MOMENTUM/1` 을 REGISTERED 로 채운다. T022 의 최소분산 행과 같은
-- 방식이다 — 이 행을 가리키는 실행이 없으므로(접수가 422 로 거절해 왔다) 제자리에서 채워도
-- 재현이 깨지지 않는다. 버전 수명주기(`/2`·SUPERSEDED)는 Phase 10 T029 다.
--
-- 이 행이 채워지면 다섯 전략이 모두 열린다.
--
-- weightCap·investedFraction 은 1.0000 이다. 사용자가 고른 allocation.parameters.maxWeight(정수 %)가
-- 있으면 그것이 유효 상한이고, 규칙표 값은 고르지 않은 요청의 기본값 역할만 한다(PD-03 ⑥).
-- lookbackDays 아래 여섯도 같다 — 003 축이 오면 그것이 이기고 여기 값은 기본값이다.
--
-- selectionTypes 는 엔진이 계산에 쓰는 값이 아니라 **적용한 모멘텀 유형의 등록처**다(AC4).
-- 실행에는 규칙 버전과 고른 selectionMode 가 함께 기록되므로, 둘을 맞추면 그 실행이 상대
-- 모멘텀이었는지 절대 모멘텀이었는지가 확정된다. 결과 계약에 필드를 더하지 않은 이유는 그것이
-- 이미 기록된 두 값의 함수이기 때문이다.
--
-- inverseVolAllocation.lookbackSource 가 MOMENTUM_LOOKBACK 인 것은 PD-03 ⓪ 결정이다 — 모멘텀의
-- 관측 길이를 그대로 쓰되 **최근 구간 제외(skipRecentDays)는 적용하지 않는다**. 점수에서 최근을
-- 빼는 이유는 단기 반전을 피하려는 것이고, 변동성은 위험 추정이라 가장 최근 자료가 가장 중요하다.
UPDATE strategy_rules
SET status       = 'REGISTERED',
    provisional  = false,
    spec         = '{"lookbackDays": 60, "skipRecentDays": 0,
                     "selectionMode": "TOP_N", "topN": 3, "threshold": 0, "topPercent": 50,
                     "allocationMethod": "EQUAL",
                     "scoreMethod": "SIMPLE_RETURN", "priceField": "ADJUSTED_CLOSE",
                     "tieBreak": "INSTRUMENT_ID_ASC", "topPercentRounding": "CEIL",
                     "noSelection": "CASH", "insufficientHistory": "EXCLUDE_THEN_INCLUDE",
                     "selectionTypes": {"TOP_N": "RELATIVE", "TOP_PERCENT": "RELATIVE",
                                        "THRESHOLD": "ABSOLUTE", "ALL": "NONE"},
                     "inverseVolAllocation": {"lookbackSource": "MOMENTUM_LOOKBACK",
                                              "zeroVolatility": "EXCLUDE_THEN_ALLOCATE"},
                     "weightCap": "1.0000", "investedFraction": "1.0000",
                     "capOverflow": "CASH", "weightUnit": "0.000001",
                     "weightsSource": "COMPUTED"}'::jsonb,
    decision_ref = 'PD-03 확정 2026-09-17 — 단순 수익률 점수·선정 방식 넷·동일/역변동성 배분'
WHERE strategy_id = 'SECTOR_MOMENTUM'
  AND rules_version = '1';

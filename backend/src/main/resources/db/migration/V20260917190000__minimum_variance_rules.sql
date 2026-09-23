-- 최소분산 규칙 확정과 배분 비중 공통 출력 규칙(spec 004 T022, PD-03 ⑥·PD-04, Jira S15P21E205-330).
--
-- 두 가지를 한다. ⓐ PENDING_DECISION 이던 `MINIMUM_VARIANCE/1` 을 REGISTERED 로 채운다.
-- ⓑ `INVERSE_VOLATILITY/1` 을 **제자리에서** 갱신해 공통 출력 규칙 두 키를 더한다.
--
-- ⓑ가 새 버전이 아닌 이유: research R-08 이 이 행을 provisional=true 로 등록하며 "PD-02 가
-- 확정되면 그 값으로 행을 갱신한다"고 처음부터 계획했다. 임시 행은 갱신 대상이다. 버전 수명주기
-- (`/2`·SUPERSEDED·옛 버전 409)는 Phase 10 T029 다.
--
-- **다만 그 전제는 "보존해야 할 결과가 없다"이다.** 재현 키에 rules_version 이 들어가므로 옛 의미로
-- 계산한 `/1` 결과가 한 건이라도 남아 있으면 새 의미의 요청이 옛 의미의 결과를 REUSED 로 받는다 —
-- 오류도 표시도 없이. 사람의 확인에만 맡기지 않고 첫 문장에서 기동을 막는다.
DO $$
BEGIN
    IF EXISTS (SELECT 1
               FROM backtest_run
               WHERE strategy_id = 'INVERSE_VOLATILITY'
                 AND rules_version = '1') THEN
        RAISE EXCEPTION 'INVERSE_VOLATILITY/1 결과가 남아 있다 — 결과 표를 비우거나 Phase 10 T029를 먼저 적용';
    END IF;
END
$$;

-- ⓐ 최소분산(PD-04 확정 2026-09-17).
--
-- weightCap·investedFraction 은 1.0000 이다. 사용자가 고른 allocation.parameters.maxWeight(정수 %)가
-- 있으면 그것이 유효 상한이고, 규칙표 값은 고르지 않은 요청의 기본값 역할만 한다(PD-03 ⑥).
--
-- symmetryTolerance 아래 넷은 엔진이 읽는 값이 아니라 **어떤 수치 기준으로 계산했는지의 기록**이다.
-- 계산을 바꾸는 정책 값(lookbackDays·estimationMethod·weightCap·investedFraction·capOverflow·
-- weightUnit)만 엔진이 읽고, 이 넷은 결과를 나중에 설명할 때 쓴다.
UPDATE strategy_rules
SET status       = 'REGISTERED',
    provisional  = false,
    spec         = '{"lookbackDays": 120, "estimationMethod": "SAMPLE_COV",
                     "solver": "SLSQP", "maxIterations": 1000, "functionTolerance": 1e-9,
                     "symmetryTolerance": 1e-12, "eigenvalueTolerance": -1e-10,
                     "solutionTolerance": 1e-8,
                     "weightCap": "1.0000", "investedFraction": "1.0000",
                     "capOverflow": "CASH", "weightUnit": "0.000001",
                     "weightsSource": "COMPUTED"}'::jsonb,
    decision_ref = 'PD-04 확정 2026-09-17 — 표본공분산·SLSQP·유효 투자 목표'
WHERE strategy_id = 'MINIMUM_VARIANCE'
  AND rules_version = '1';

-- ⓑ 역변동성: 상한 잔여를 현금으로 두는 정책과 고정 단위 출력을 세 전략이 함께 쓴다.
-- provisional 은 true 그대로다 — PD-02(관측 길이 외 나머지)는 아직 확정이 아니다.
UPDATE strategy_rules
SET spec         = spec || '{"capOverflow": "CASH", "weightUnit": "0.000001"}'::jsonb,
    decision_ref = 'PD-02 미확정 — spec 004 FR-023 관측 길이만 확정, 나머지는 출발안'
                       || ' / PD-03 ⑥·PD-04 공통 출력(2026-09-17)'
WHERE strategy_id = 'INVERSE_VOLATILITY'
  AND rules_version = '1';

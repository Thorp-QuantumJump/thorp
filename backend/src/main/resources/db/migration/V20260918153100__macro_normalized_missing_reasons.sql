-- B/C 변환기가 실제로 내는 결측 사유도 원형 그대로 보존한다.
-- 기존 사유는 유지해 이전 자료 버전과 호환한다.
ALTER TABLE macro_calculation_registry_version
    ADD COLUMN nominal_weight_policy VARCHAR(240);

-- 값 지문에 들어간 원천 선택/구간 규칙도 정의 행에서 되짚을 수 있게 한다.
ALTER TABLE macro_indicator_definition
    ADD COLUMN org_id VARCHAR(16),
    ADD COLUMN classification JSONB,
    ADD COLUMN source_cycle CHAR(1),
    ADD COLUMN series_start CHAR(7),
    ADD COLUMN link_chain JSONB;
ALTER TABLE macro_indicator_definition
    ADD CONSTRAINT ck_macro_indicator_source_cycle
    CHECK (source_cycle IS NULL OR source_cycle IN ('D', 'M'));

-- 연결 근거에는 개편 경계·실측 계수·오차 설명이 함께 들어가므로 200자로 자르지 않는다.
ALTER TABLE macro_indicator_definition
    ALTER COLUMN break_link_rule TYPE TEXT;

ALTER TABLE macro_observation DROP CONSTRAINT ck_macro_observation_missing_reason;
ALTER TABLE macro_observation ADD CONSTRAINT ck_macro_observation_missing_reason CHECK (
    missing_reason IS NULL OR missing_reason IN (
        'SOURCE_NOT_RELEASED', 'COLLECTION_FAILED', 'BASE_MONTH_MISSING', 'BASE_MONTH_ZERO',
        'BEFORE_SERIES_START', 'MONTH_AVERAGE_INCOMPLETE', 'MONTH_END_NO_TRADING_DAY',
        'BREAK_SEGMENT_EXCLUDED', 'SOURCE_MISSING', 'NOT_TRANSFORMABLE', 'SERIES_START',
        'INCOMPLETE_MONTH'
    )
);

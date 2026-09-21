-- 지표별 이용 조건을 등록표 행에 남긴다 (T053, FR-018).
--
-- 등록표(data/)는 지표마다 상업적 이용 가부·재배포 규칙·내보내기에 실을 문구를 들고 있는데,
-- 지금까지 이 값들이 DB 로 가지 않아 006 의 내보내기 경로가 문구를 가져올 곳이 없었다.
-- 원자료가 파일로 빠져나가는 경로라 문구 없이 나가면 그대로 이용 조건 위반이다.
ALTER TABLE macro_indicator_definition
    ADD COLUMN commercial_use       VARCHAR(16),
    ADD COLUMN redistribution_rule  VARCHAR(300),
    ADD COLUMN export_notice        VARCHAR(300),
    ADD COLUMN license_reverify_by  DATE;

-- 셋 중 하나를 고르게 한다. 불리언으로 두면 "모른다"를 적을 데가 없는데, FR-016a 는 모르는
-- 것을 허용으로 읽지 말라고 하고 FR-018 은 모르는 것을 금지로 읽지 말라고 한다.
ALTER TABLE macro_indicator_definition
    ADD CONSTRAINT ck_macro_indicator_commercial_use CHECK (
        commercial_use IS NULL
        OR commercial_use IN ('ALLOWED', 'RESTRICTED', 'UNKNOWN'));

COMMENT ON COLUMN macro_indicator_definition.export_notice IS
    '내보내기 파일에 함께 실어야 하는 출처·이용 범위 문구 (FR-018)';

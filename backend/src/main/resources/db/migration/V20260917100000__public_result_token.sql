-- spec 005 결과 이해·비교 B01(T008): 비로그인 요청과 결과 공개 토큰.
--
-- ① 비로그인 사용자도 분석을 요청할 수 있게 requester_id 의 NOT NULL 을 푼다(004 research R-12 가 열어 둔 경로).
-- ② 모든 요청에 추측할 수 없는 공개 토큰(128비트, 소문자 16진 32자)을 붙인다. 결과 주소 /results/{token} 의 열쇠다.
-- ③ 비로그인 요청만 공개 토큰 만료 시각을 갖는다. 로그인 요청은 NULL(만료 없음)이다.
-- ④ 비로그인 요청은 만료 시각이 반드시 있다(data-model §1). 만료가 없는 익명 결과는 정리 작업이 영영 지우지 못한다.
--
-- 기존 행에도 토큰을 채운다. pgcrypto 의 gen_random_bytes 대신 PostgreSQL 13+ 내장 gen_random_uuid()(강한 난수) 두 개를
-- sha256 으로 섞어 앞 32자를 쓴다 — 확장 설치(CREATE EXTENSION)는 운영 DB 계정 권한에 기대야 해서 피했다. UUID 하나는
-- 122비트라 FR-019 의 최소 128비트에 못 미치므로 둘을 섞는다(입력 244비트 → 출력 128비트).

ALTER TABLE backtest_request ALTER COLUMN requester_id DROP NOT NULL;

ALTER TABLE backtest_request ADD COLUMN public_token VARCHAR(32);

UPDATE backtest_request
   SET public_token = substr(
           encode(sha256(convert_to(gen_random_uuid()::text || gen_random_uuid()::text, 'UTF8')), 'hex'),
           1, 32);

ALTER TABLE backtest_request ALTER COLUMN public_token SET NOT NULL;

CREATE UNIQUE INDEX uk_backtest_request_public_token ON backtest_request (public_token);

-- 형식은 값 타입(PublicResultToken)이 지키지만 DB 에도 둔다. VARCHAR 인 이유는 기존 식별자 컬럼(backtest_id)과 같다 —
-- CHAR 는 Hibernate 스키마 검증(ddl-auto: validate)이 String 매핑과 다른 타입으로 본다.
ALTER TABLE backtest_request
    ADD CONSTRAINT ck_backtest_request_public_token_format
        CHECK (public_token ~ '^[0-9a-f]{32}$');

ALTER TABLE backtest_request ADD COLUMN public_token_expires_at TIMESTAMP WITH TIME ZONE;

-- 만료 정리 작업(B02)이 비로그인 요청만 훑는다. 로그인 요청은 NULL 이라 인덱스에 들어오지 않는다.
CREATE INDEX idx_backtest_request_public_expiry
    ON backtest_request (public_token_expires_at)
    WHERE public_token_expires_at IS NOT NULL;

ALTER TABLE backtest_request
    ADD CONSTRAINT ck_backtest_request_public_expiry
        CHECK (requester_id IS NOT NULL OR public_token_expires_at IS NOT NULL);

-- 종목 식별자 형식을 "숫자 여섯 자리"에서 "영숫자 여섯 글자"로 넓힌다 (spec 001 T031, PD-01).
--
-- 2026-09-11 KRX 응답을 하루치 실측한 결과 ETF 1,168종 가운데 304종(26%)의 단축코드가
-- `0111J0`·`0182R0`·`0005D0`처럼 영문 대문자가 섞인 형태였다. 숫자만 받는 기존 CHECK는 이
-- 종목들을 대표 ETF로도 비교 대상으로도 담지 못하게 막는다. 자리 수는 여섯으로 그대로 두고
-- 허용 문자에 대문자만 더한다. 소문자를 받지 않는 것은 KRX 단축코드가 대문자로만 오고,
-- 대소문자를 함께 받으면 같은 종목이 두 값으로 저장될 수 있기 때문이다.
--
-- 제약을 넓히기만 하므로 기존 행은 어느 것도 새 제약을 위반하지 않는다. 따라서 "기존 행이
-- 위반하는 제약 추가"에 해당하지 않아 백업·승인 절차가 필요한 변경이 아니다.
--
-- V20260911114500__etf_market_data.sql을 직접 고치지 않는 까닭은 그 파일이 이미 dev에 병합되어
-- 팀원들의 로컬 DB에 적용되었기 때문이다. 적용된 파일의 내용을 바꾸면 Flyway 체크섬이 어긋나
-- 그 DB에서 기동이 막힌다 (backend/CLAUDE.md "운영에 이미 적용된 마이그레이션은 고치지 않는다").

ALTER TABLE representative_etf
    DROP CONSTRAINT ck_representative_etf_instrument_id_format;
ALTER TABLE representative_etf
    ADD CONSTRAINT ck_representative_etf_instrument_id_format
        CHECK (instrument_id ~ '^[0-9A-Z]{6}\.XKRX$');

ALTER TABLE daily_price
    DROP CONSTRAINT ck_daily_price_instrument_id_format;
ALTER TABLE daily_price
    ADD CONSTRAINT ck_daily_price_instrument_id_format
        CHECK (instrument_id ~ '^[0-9A-Z]{6}\.XKRX$');

ALTER TABLE corporate_action
    DROP CONSTRAINT ck_corporate_action_instrument_id_format;
ALTER TABLE corporate_action
    ADD CONSTRAINT ck_corporate_action_instrument_id_format
        CHECK (instrument_id ~ '^[0-9A-Z]{6}\.XKRX$');

ALTER TABLE data_gap
    DROP CONSTRAINT ck_data_gap_instrument_id_format;
ALTER TABLE data_gap
    ADD CONSTRAINT ck_data_gap_instrument_id_format
        CHECK (instrument_id ~ '^[0-9A-Z]{6}\.XKRX$');

ALTER TABLE suspension_period
    DROP CONSTRAINT ck_suspension_period_instrument_id_format;
ALTER TABLE suspension_period
    ADD CONSTRAINT ck_suspension_period_instrument_id_format
        CHECK (instrument_id ~ '^[0-9A-Z]{6}\.XKRX$');

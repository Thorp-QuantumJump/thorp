-- spec 005 결과 이해·비교 B02(T087): 만료로 지운 공개 토큰의 묘비.
--
-- 정리 작업은 만료된 비로그인 요청 행과 공개 토큰을 지운다(FR-021). 지운 뒤에 그 주소로 들어오면 "그런 토큰 없음"(404)과
-- 구분할 수 없어, spec Edge Case 가 요구한 "결과가 더 이상 남아 있지 않다" 안내(410)가 정리 주기 하나 뒤에 사라진다.
-- 그래서 지울 때 토큰의 SHA-256 해시와 만료 시각만 남긴다. 토큰 원문은 남기지 않는다 — 해시로는 결과를 열 수 없고, 128비트
-- 난수의 해시라 되돌려 원문을 찾을 수도 없다.
--
-- 묘비도 영구히 두지 않는다. 보관 기한(thorp.result.tombstone-retention, PD-23 확정 전 임시값)이 지난 묘비는 같은 정리
-- 작업이 지우고, 그 뒤로는 404 다. 새 표를 만들 뿐 기존 행을 바꾸지 않는다.

CREATE TABLE expired_public_token (
    token_hash VARCHAR(64)              NOT NULL PRIMARY KEY,
    expired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_expired_public_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

-- 보관 기한이 지난 묘비를 만료 시각 순으로 찾는다.
CREATE INDEX idx_expired_public_token_expired_at ON expired_public_token (expired_at);

COMMENT ON TABLE expired_public_token IS '만료로 지운 비로그인 공개 토큰의 해시(spec 005 T087). 지운 주소에 404 대신 410 안내를 주기 위한 것';
COMMENT ON COLUMN expired_public_token.token_hash IS '공개 토큰 원문의 SHA-256 소문자 16진. 원문은 저장하지 않는다';
COMMENT ON COLUMN expired_public_token.expired_at IS '원래 요청의 public_token_expires_at. 보관 기한은 이 시각부터 센다';

-- spec 014 소셜 로그인의 인증번호·시도 기록(2026-10-01 code review).
--
-- 연결 단계의 인증번호·비밀번호는 남의 계정에 들어가는 열쇠라 시도에 상한을 둔다. 상한을 세션 속성에 세면 같은 세션으로
-- 동시에 들어온 요청이 같은 사본을 읽어 함께 넘길 수 있으므로, 행 잠금(SELECT ... FOR UPDATE) 아래에서 센다.
-- 행 하나가 세션의 가입·연결 대기 상태 하나다. id는 대기 상태가 만든 UUID이고 URL이나 응답에 나가지 않는다.
CREATE TABLE social_pending_verifications (
    id                VARCHAR(36)  PRIMARY KEY,
    code_hash         VARCHAR(255),
    code_target       VARCHAR(30),
    code_expires_at   TIMESTAMP WITH TIME ZONE,
    code_attempts     INTEGER      NOT NULL,
    codes_sent        INTEGER      NOT NULL,
    last_code_sent_at TIMESTAMP WITH TIME ZONE,
    password_attempts INTEGER      NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 가입·연결이 끝나면 행을 지운다. 끝나지 않고 버려진 대기 상태의 행을 나중에 정리할 때 쓴다.
CREATE INDEX idx_social_pending_verifications_created_at ON social_pending_verifications (created_at);

-- spec 014 소셜 로그인: 기존 계정 하나를 향한 연결 확인 시도의 누적 기록(2026-10-01 남은 위험 보완).
--
-- 대기 상태마다 두는 상한(social_pending_verifications)은 제공자 로그인부터 다시 하면 새 기록으로 시작한다.
-- 같은 계정을 향한 인증번호·비밀번호 대조와 그 계정 이메일로의 발송을 한 시간 창으로 묶어 센다. 행 잠금 아래에서 센다.
CREATE TABLE social_link_targets (
    user_id           BIGINT       PRIMARY KEY REFERENCES users (id),
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts          INTEGER      NOT NULL,
    codes_sent        INTEGER      NOT NULL
);

-- 창이 끝난 지 오래된 행을 정리할 때 쓴다.
CREATE INDEX idx_social_link_targets_window ON social_link_targets (window_started_at);

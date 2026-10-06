-- spec 014 이메일 가입 인증번호의 대조·발송 상한(2026-10-01 남은 위험 보완).
--
-- 6자리 번호에 대조 상한이 없어 끝까지 대입할 수 있었고, 재발송에 간격이 없어 남의 주소로 메일을 계속 보낼 수 있었다.
-- 기존 행은 기본값(대조 0회, 발송 1회, 창 시작 = 발급 시각)으로 채운다 — 진행 중이던 인증은 그대로 이어진다.
ALTER TABLE email_verifications ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE email_verifications ADD COLUMN sends_in_window INTEGER NOT NULL DEFAULT 1;
ALTER TABLE email_verifications ADD COLUMN send_window_started_at TIMESTAMP WITH TIME ZONE;
UPDATE email_verifications SET send_window_started_at = created_at WHERE send_window_started_at IS NULL;
ALTER TABLE email_verifications ALTER COLUMN send_window_started_at SET NOT NULL;

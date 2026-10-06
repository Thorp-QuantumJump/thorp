-- spec 014 2026-10-02 결정: 가입(이메일·소셜)에서 휴대폰 번호를 받지 않는다.
--
-- 번호를 쓰는 기능이 없고, 인증하지 않은 고유 번호는 남의 번호로 먼저 가입해 주인의 가입을 막을 수 있다.
-- NOT NULL만 푼다. UNIQUE는 그대로 둔다 — 번호가 있는 계정끼리는 여전히 겹칠 수 없고, PostgreSQL은 NULL끼리
-- 겹치는 것을 허용한다. 기존 행은 모두 번호가 있으므로 데이터와 이전 버전 코드에 영향이 없다.
ALTER TABLE users ALTER COLUMN phone_number DROP NOT NULL;

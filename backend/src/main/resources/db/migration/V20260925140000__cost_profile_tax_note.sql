-- 비용 조건에 세금 규칙 설명 한 줄을 둔다(spec 015 도우미, Jira S15P21E205-444).
--
-- "세금은 왜 0원이야" 같은 질문에 도우미가 답하려면 값(0원)이 아니라 규칙이 근거로 있어야 한다.
-- 세금률 같은 수치는 싣지 않고 설명 문장 하나만 근거 팩에 올린다. 값은 바꾸지 않으므로 새 버전 행을
-- 만들지 않는다 — 두 행 모두 tax_bps 0 이고 이유가 같다(V20260919193708 주석).
ALTER TABLE cost_profile ADD COLUMN tax_note text;

UPDATE cost_profile
SET tax_note = '국내 상장 ETF 매도에는 증권거래세가 없어 0으로 둔다'
WHERE version IN ('KR-ETF-2026-09', 'PROVISIONAL-DEMO-1');

-- 확정 비용 조건 추가(spec 004 T021, PD-07, Jira S15P21E205-349).
--
-- PD-07(2026-09-17 팀 확정)의 기본 비용을 새 버전 행으로 넣는다. 기존 `PROVISIONAL-DEMO-1` 은
-- 고치지 않는다 — 그 버전을 가리키는 실행의 결과가 다른 비용으로 재해석되면 안 된다(R-07, 원칙 VIII).
-- 기본 버전 교체는 행이 아니라 설정(`thorp.backtest.default-cost-profile`)이 한다.
--
-- 수치 근거(spec PD-07):
--   commission_bps 1.5  온라인 위탁수수료 0.015%
--   spread_bps     3    한쪽 체결에 매기므로 호가 간격의 절반. 국내 섹터 ETF 1틱 기준 보수적
--   slippage_bps   5    데모 값 유지(월 1회·1천만 원 규모)
--   tax_bps        0    국내주식형 투자신탁 ETF 는 매도 증권거래세·농특세가 없다. 분배금 원천징수와
--                       다른 ETF 의 보유기간 과세는 이 필드에 넣지 않는다
-- 워커는 수수료·호가 차이·슬리피지를 사고팔 때 모두, 세금은 팔 때만 매기므로(worker/costs.py)
-- 한쪽 체결마다 9.5bps, 왕복 약 0.19% 다.
--
-- escalation(비용 상승 시나리오)은 아직 정하지 않아 null 이다. 정해지면 새 버전 행으로 넣는다.
INSERT INTO cost_profile (version, commission_bps, spread_bps, slippage_bps, tax_bps,
                          escalation, provisional, note)
VALUES ('KR-ETF-2026-09', 1.5, 3, 5, 0,
        NULL, false, 'PD-07 확정 2026-09-17');

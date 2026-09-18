# Research: 경제환경 사례와 전략 비교 연결

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Date**: 2026-09-18

plan의 Technical Context에서 `NEEDS CLARIFICATION`으로 남긴 항목을 여기서 하나씩 해소한다. 각 항목은
**결정 / 근거 / 버린 선택지** 순서다. 값의 소유가 다른 spec에 있는 것은 그 spec을 가리키고 다시
정하지 않는다.

---

## R1. 사후 구간 계산 작업을 어느 경로로 전달하나

**결정**: 백테스트 작업 큐와 **분리된** RabbitMQ 경로를 새로 둔다 — exchange `regime-link`(direct,
durable), 작업 큐 `regime-link.jobs`(quorum, `x-delivery-limit=1`, DLX → `regime-link.jobs.dlq`),
이벤트 큐 `regime-link.events`. 메시지 계약은 [contracts/regime-link-messaging.md](contracts/regime-link-messaging.md)의
`RegimeLinkJob` v1이다. Worker는 같은 코드베이스(`worker/`)에 **별도 엔트리포인트**
`python -m worker.regime_link_main`으로 소비자를 하나 더 두고, 백테스트 소비자(`consumer.py`)는
건드리지 않는다.

**근거**: `worker/contracts/engine_input.py`의 `EngineInput`은 `extra="forbid"`로 검증하고 소비자는
본문 전체를 `EngineInput`으로 파싱한다(`consumer.parse_job`). 잘라내기 작업은 입력 모양(저장된
시계열·거래 사본)이 백테스트 요청과 전혀 달라 같은 큐에 섞으면 계약 위반으로 DLQ에 간다. 토폴로지는
백엔드가 선언하고 워커는 선언하지 않는 규칙(`worker/CLAUDE.md`)을 그대로 따른다. 백테스트 소비자의
`WORKER_ROLE`(single/flex) 흐름과 무관하게 돌아야 하므로 프로세스도 나눈다.

**버린 선택지**: ① `backtest.jobs`에 `EngineInput`의 새 `scenario`로 끼워 넣기 — 엔진 러너가
백테스트를 다시 돌리게 되어 "잘라낸다"는 spec 정의(FR-010)와 어긋나고, 63거래일 결과가 전체 기간
연속 운용과 다른 값이 된다. ② Backend가 저장된 시계열을 직접 잘라 산출 — 구간 안 손실 기여·회전율은
일별 종목 수익률을 다시 곱하는 계산이라 "Backend는 계산하지 않는다"(AGENTS.md 원칙 1)에 걸린다.
③ 프론트에서 잘라 계산 — 재현성(원칙 VIII)이 사라지고 기록(FR-023)에 남길 값이 서버에 없다.

## R2. 잘라내기 작업의 입력은 어디서 오나 (워커가 결과 DB를 읽는가)

**결정**: **Backend가 작업 메시지에 입력 사본을 담아 보낸다.** 대상 실행의 `backtest_result`에서
사후 구간(앞뒤 1거래일 여유)만큼의 `portfolio_series`·`weights_series`·구간 안 `backtest_trade`를
잘라 메시지에 넣는다. 워커는 여기에 `market_data.py`(001 적재 표, `MARKET_DATA_URL`)로 같은 구간의
종목 조정 종가와 KOSPI 일별 자료를 더해 계산한다. 워커는 여전히 **결과 DB에 접근하지 않는다.**

**근거**: 워커 계약 §1 "워커는 PostgreSQL에 쓰지 않는다"와 워커가 접속하는 DB가 시장 자료
(`MARKET_DATA_URL`) 하나라는 현행 구조를 유지한다. 63거래일 × 종목 ≤ 10개면 메시지는 수십 KB라
브로커 부담이 없다. 잘라내기 범위를 Backend가 정하므로 "어느 구간을 잘랐나"가 메시지에 그대로 남아
재현할 수 있다.

**버린 선택지**: 워커에 결과 DB 접속을 추가 — 접속 정보가 하나 늘고 워커 역할 경계(ADR-0003)가
흐려진다. 잘라내기 하나 때문에 열 가치가 없다.

## R3. 구간 요약 항목의 정의

**결정**: 63·21거래일 두 구간(완성된 만큼)에 대해 아래를 낸다. 정의는 **004 워커의
`performance.calculate_analytics`와 같은 함수**를 구간 자료에 그대로 적용해 얻는다 — 전체 기간 값과
사후 구간 값이 같은 정의여야 나란히 놓을 수 있다(원칙 IV).

| 항목 | 정의 |
|---|---|
| 구간 수익률 | 연속 운용: `equity[마지막 완성일] / equity[d0 직전 거래일] − 1`. 현금 시작: `equity[마지막 완성일] / initialCash − 1` |
| 최대 하락 폭 | 구간 안 자산가치 경로의 최대 낙폭 |
| 거래와 비용 | 구간 안 체결된 거래 수, 네 갈래 비용 합계(수수료·호가차·슬리피지·세금) |
| 월당 거래횟수 | `거래 수 ÷ (완성 거래일 ÷ 21)` — 길이가 다른 구간을 같은 자로 비교(FR-011) |
| 회전율 | 004 `turnover` 정의를 구간 비중 변화에 적용 |
| 손실 기여 | 004 `lossContribution` 정의를 구간 일별 비중 × 종목 일별 수익률에 적용. 섹터별 |
| 보유 섹터·비중 | 구간 일별 `actual` 비중 시계열(그대로 전달) |
| KOSPI 참고 | 같은 구간의 KOSPI 수익률·최대 하락 폭 두 값 (Clarifications 09-18 Q4) |

`d0` = 관측 종료 후 첫 거래일. 63거래일이 미완성이면 완성된 거래일 수만큼 계산하고
`completedTradingDays`·`complete63`·`complete21`을 함께 낸다(FR-008). 워밍업이 d0 이후에 끝나는
실행(`effectiveStart > d0`)은 계산하지 않고 `DATA_INSUFFICIENT/WARMUP_SHORT`다(FR-017).

**근거**: spec FR-011이 "같은 척도"를 요구하고, 정의를 둘로 두면 전체 기간 값과 구간 값이 다른
방법으로 계산돼 사용자가 그 차이를 전략 차이로 읽는다. 함수 재사용은 정의 일치를 코드로 보장한다.

**버린 선택지**: 구간 수익률을 연율화 — 63거래일을 연율화하면 4배 확대된 숫자가 "더 벌 수 있다"로
읽힌다(원칙 III). 거래량만 월당으로 환산하고 수익률·하락 폭은 그대로 둔다.

## R4. 가용성 판정과 필터 키를 누가 만들고 어떻게 식별하나

**결정**: **Backend `regimelink` 도메인**이 만든다. 이것은 백테스트 계산이 아니라 004 접수 검증이
이미 하는 종류의 메타데이터 조회(상장일·워밍업·거래일 달력, `MarketMetadataPort`)다. 사례마다 다섯
판정을 순서대로 한다.

| 판정 | 조건 | 결과 |
|---|---|---|
| ETF 이력 | 비교 묶음 모든 종목의 상장일 ≤ (d0 − 그 실행의 워밍업 거래일) | 아니면 `DATA_INSUFFICIENT(UNLISTED)` |
| 워밍업 | 실행의 평가 시작일(`effectiveStart`) ≤ d0 | 아니면 `DATA_INSUFFICIENT(WARMUP_SHORT)` |
| 사후 자료 | d0부터 자료 마감일까지 완성 거래일 ≥ 21 | 아니면 `POST_INCOMPLETE` — **기본 검색 제외** |
| 분석 기간 | 실행의 `start ≤ d0` 그리고 `end ≥ d63`(또는 마감일) | 아니면 `OUT_OF_PERIOD` — 필터 조건이 아니라 표시(Clarifications 09-18 Q1) |
| 상장폐지 | 구간 안 폐지일이 있으면 | `PARTIAL(DELISTED)` 표시, 폐지일 이후 자료 없음 |

앞 셋이 **공통 가용 범위** 조건이고(FR-003), 셋 중 하나라도 걸리면 그 사례는 기본 검색에서 빠지고
`excludedCount`에 든다. 판정은 010 **표시 후보(≤10)에만** 한다(Clarifications 09-17 Q4).

**필터 키** = `sha256(정렬한 {instrumentId, listedOn} 목록 · 묶음의 최대 워밍업 거래일 · 사후 최소
21 · 대응표 버전 · 자료 버전 · 마감일)`의 16진 64자. 같은 묶음·같은 자료면 같은 키라 "같은 비교
묶음은 같은 후보"(FR-021)가 키 일치로 검증된다. `regime_link_filter`에 정의와 함께 저장한다.

**거래일 달력**: d0·d21·d63은 001 적재 표의 거래일(`MarketMetadataPort`가 이미 "워밍업 소진 후 첫
거래일"에 쓰는 달력)로 구한다. 워커처럼 자료 합집합으로 달력을 만들지 않고 KOSPI 일별 행을 기준
달력으로 쓴다 — KOSPI는 001 FR-009가 전 기간을 보장한다.

**버린 선택지**: 판정을 워커 작업으로 — 사례 10개의 판정에 작업 10개가 필요해지고 목록이 뜨기까지
기다리게 된다(010의 "읽기만 한다" 흐름과 어긋남).

## R5. 현금 시작 시나리오와 분석 기간 밖 재계산을 어떻게 요청하나

**결정**: 둘 다 **004의 접수 유스케이스를 애플리케이션 서비스로 호출**한다(HTTP를 스스로 부르지
않는다). `result` 도메인이 `BacktestResultQueryService`·`BacktestRetryService`를 import하는 것과
같은 방식으로 `regimelink`가 `backtest.application.service`의 접수 서비스를 쓴다.

- **현금 시작**: `scenario=FRESH_CASH_FROM`, `scenarioParams.startDate=d0`, `end=d63`(미완성이면
  마감일), `start`는 d0에서 워밍업 거래일만큼 앞, 나머지 조건(섹터 후보 집합·전략·설정·금액·비용
  버전·`warmupTradingDays`)은 기준 실행의 `config` 사본. 004의 동일 버전 재사용(`REUSED`)이 그대로
  작동한다. 실행이 `COMPLETED`되면 Backend의 결과 Consumer 후처리에서 그 실행을 입력으로 **잘라내기
  작업**(R1)을 발행해 링크 결과를 만든다 — 현금 시작 결과도 같은 항목·같은 정의로 나란히 놓기 위해서다.
- **분석 기간 밖 재계산**: `scenario=FULL_PERIOD`, `start`·`end`를 그 묶음의 **공통 가용 범위**
  전체로(003 FR-009의 산출값, `MarketMetadataPort`로 같은 값을 구한다), 나머지는 기준 실행 사본.
  사용자가 확인한 뒤에만 요청한다(`confirmFullPeriod=true`). 완료되면 그 실행이 이후 사례의
  잘라내기 입력이 된다.

**근거**: 004 계약(`contracts/rest-api.md`)에 두 시나리오가 이미 있어 새 실행 종류를 만들 필요가
없다. 동일 버전 재사용 덕에 같은 사례를 두 사용자가 켜도 계산은 한 번이다. Clarifications 09-17
Q3(켤 때만, 63에서 끊음)·09-18 Q1(처음 열 때 한 번, 이후 재사용)이 그대로 구현된다.

**버린 선택지**: 프론트가 `POST /backtests`를 직접 부르고 `POST /regime-links`에 토큰을 넘김 —
"기준 실행과 같은 조건"을 프론트가 조립하게 되어 조건이 어긋날 수 있고(원칙 IV), 로그인 요구
(FR-025)를 두 경로에서 따로 걸어야 한다.

## R6. 진입 계약과 사례 식별자

**결정**: 사례 식별자는 010 data-model이 정한 대로 **(snapshot_key, end_month)** 쌍이다. 화면
주소에서는 `case={snapshotKey}~{endMonth}`처럼 `~`로 이어 한 값으로 다닌다(`snapshot_key`에 `~`가
없음을 010 식별 규칙에서 확인했다 — `:`·`/`는 키 안에 쓰이므로 피한다). 프론트 진입 주소는 둘이다.

| 경로 | 주소 | 넘어오는 값 |
|---|---|---|
| 진단 결과(005 FR-018) | `/regime-link?base={publicToken}[&compare={publicToken}]` | 비교 묶음 = 기준·비교 대상 공개 토큰. 분야는 없다 → 분야 선택 화면부터(Clarifications 09-18 Q3) |
| 시장 흐름(009 FR-018) | `/regime-link/start?case={caseId}[&sector={sectorCode}]` | 사례 식별자와 선택 섹터(있으면). 002 → 003으로 이어지며 `case`를 주소에 유지, 분석 완료 후 `/regime-link?base={token}&case={caseId}`로 돌아온다 |

`frontend/src/marketflow/handoff.ts`의 `StrategyComparisonHandoff{sectorCode, caseId}`가 이미 이
모양이다. 005 FR-018의 "전달 형식은 011의 진입 계약이 소유한다"에 따라 위 표가 그 계약이다
([contracts/entry-handoff.md](contracts/entry-handoff.md)).

**근거**: 010이 사례의 동일성을 `end_month`로, 검색의 동일성을 `snapshot_key`로 판정한다. 둘을
합쳐야 "어느 검색의 어느 사례"가 정해지고 z·거리가 고정된다(010 계약 §3).

**버린 선택지**: `regime_search_case.id`(BIGINT)를 식별자로 — revision이 오르면 같은 사례가 다른
id가 되어 기록과 주소가 끊긴다. 내부 PK를 주소에 노출하지 않는 규칙(ADR-0002 D2)과도 어긋난다.

## R7. 로그인 요구를 어디서 거나

**결정**: `SecurityConfig`는 `/auth/me` 밖을 전부 `permitAll`로 두는 현행을 유지하고, **쓰기
엔드포인트의 컨트롤러가 `CurrentRequester`로 요청자를 확인**한다. 익명이면 `401 LOGIN_REQUIRED`
(`ErrorCode`에 spec 011 블록으로 추가). 대상은 `POST /regime-links`(현금 시작 시나리오도 이 요청의
`scenario` 필드로 켠다)와 `POST /regime-links/{id}/retry`다. 조회(`GET`)는 익명 허용이다(FR-025).

**근거**: `backtest`·`result`·`learning` 컨트롤러가 모두 같은 방식으로 요청자를 읽고 있어 규칙이
하나로 유지된다. 경로 매처를 늘리면 `/regime-links/**` 안에서 GET·POST를 다시 갈라야 해 설정이
두 곳으로 흩어진다.

**버린 선택지**: 프론트에서만 막기 — 직접 호출하면 계산이 시작되므로 FR-025의 "계산을 시작하지
않는다"를 보장하지 못한다.

## R8. 010·005가 미완인 상태에서 011을 어떻게 진행하나

**결정**: 011은 **010의 M6(마이그레이션 + 조회 API)과 005의 공개 토큰(머지됨)에 의존한다.** 010
M6이 dev에 들어오기 전에는 Backend `regimelink`가 010 표를 읽는 리포지토리를 **인터페이스 +
픽스처 구현**으로 두고, 마이그레이션은 011 표만 만든다(010 표를 011이 만들지 않는다). Frontend는
010의 결과 목록 컴포넌트가 없으면 011 화면이 목록을 그릴 수 없으므로 **FE 묶음은 010 M7 이후**로
순서를 둔다(plan 구현 순서). 계산 규칙(워커 잘라내기)과 Backend 판정·계약은 010 없이 검증할 수
있어 먼저 간다.

**근거**: `dev`에 `regime` 패키지가 없고 `feat(be)/282-regime-search-api`가 진행 중이다. 010이
`postPeriod.available=false`를 011 자리로 비워 두었으므로 계약 충돌은 없다. 007이 화면부터 만들어
숫자를 주입하게 된 전철(010 plan)을 피하기 위해 계산 → 판정 → 화면 순서를 지킨다.

## R9. 성능 목표

**결정**: **측정 목표만 두고 한도로 쓰지 않는다**(원칙 IX). 잘라내기 작업은 대기 포함 p95 3초를
측정 목표로 기록하고, 가용성 판정 조회는 p95 300ms(010 조회와 같은 목표). 현금 시작·전체 기간
재계산은 004 실행이라 004의 측정 체계(`backtest_run_metric`, `executionType`)에 그대로 잡힌다 —
011이 별도 측정을 만들지 않는다. 잘라내기 작업은 `regime_link`에 `queued_at`·`started_at`·
`finished_at`만 남기고 집계는 운영자 조회로 본다.

**버린 선택지**: 사후 구간 결과를 사례 목록 표시 시점에 미리 계산 — Clarifications 09-17 Q3·
09-18 Q1이 "켤 때만·처음 열 때만"으로 정했다.

## R10. 006 기록과의 경계

**결정**: 011은 **자기 표(`regime_link_search`·`regime_link`)에 FR-023 항목을 남기고**, 006이
"경제환경 탐색 기록" 화면을 만들 때 이 표를 읽는다. 006은 아직 spec만 있고 data-model이 없으므로
011이 006의 표를 만들지 않는다. 010의 `regime_search_log`가 검색 자체를 기록하듯 011은 연결을
기록한다 — 검색 경로별 별도 항목(006 Key Entities)은 `regime_link_search.entry_path`로 갈린다.

**근거**: 기록 항목(연결한 섹터·ETF·전략·설정, 사후 평가 구간, 시나리오, 가용성 필터 키, 검색 경로,
당시 정보 재현 여부)은 전부 011이 만드는 값이라 011이 소유하는 것이 맞다. 유지율(Clarifications
09-17 Q5)은 `regime_link_search.retention`에 **기록만** 한다.

## R11. 화면 문구의 통제

**결정**: 사후 구간 결과 영역의 고정 문구는 010과 같은 방식으로 **금지 표현 정적 검사**를
`frontend/src/components/regimelink/`에 건다. 검사 목록은 spec FR-014·FR-016·SC-007의 것
("추천", "지금도", "더 벌", "이겼", "승자", "확률", "유사도 %")에 010의 13개를 합친다. KOSPI 참고
정보 옆에 전략과의 차이 값을 두지 않는 것은 타입 수준에서(`RegimeLinkKospi`에 차이 필드 없음) 막는다.

---

## 해소 결과 요약

| Technical Context 항목 | 결론 |
|---|---|
| 계산 작업 전달 경로 | 별도 exchange/큐 `regime-link`, 별도 워커 엔트리포인트 (R1) |
| 워커 입력 | Backend가 시계열·거래 사본을 메시지에 담음, 워커는 시장 자료만 추가 (R2) |
| 구간 요약 정의 | 004 `calculate_analytics` 재사용, 월당 환산은 거래량만 (R3) |
| 가용성 판정·필터 키 | Backend 메타데이터 조회, sha256 키, KOSPI 달력 (R4) |
| 현금 시작·전체 기간 재계산 | 004 접수 서비스 호출, 완료 후 잘라내기 작업 연쇄 (R5) |
| 진입 계약·사례 식별자 | `(snapshotKey, endMonth)`, 주소 둘 (R6) |
| 로그인 | 컨트롤러 요청자 확인, `401 LOGIN_REQUIRED` (R7) |
| 010·005 의존 | 010 M6·M7 뒤에 BE 조회·FE, 계산·판정은 먼저 (R8) |
| 성능 | 측정 목표만, 004 측정 체계 재사용 (R9) |
| 006 경계 | 011 표에 기록, 006이 읽음 (R10) |
| 문구 통제 | 금지 표현 정적 검사 + 타입으로 차이 값 배제 (R11) |

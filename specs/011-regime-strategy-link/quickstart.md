# Quickstart: 경제환경 사례와 전략 비교 연결 — 검증 시나리오

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Contracts**: [contracts/](contracts/)

기능이 끝에서 끝까지 동작하는지를 확인하는 실행 안내다. 구현 코드·마이그레이션 본문·전체 테스트
목록은 여기 두지 않는다(tasks.md와 구현 몫). 각 시나리오는 **무엇을 실행하고 무엇이 보여야 하는지**만
적는다. 실행 기록은 M8에서 이 파일 끝에 남긴다.

## 전제

```bash
# 저장소 루트
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq

# 백엔드 (backend/.env 필요, backend/CLAUDE.md)
cd backend && ./gradlew bootRun

# 워커 두 개 — 백테스트 소비자와 잘라내기 소비자는 별도 프로세스다 (research R1)
cd worker && MARKET_DATA_URL=… RABBITMQ_URL=… python -m worker.main
cd worker && MARKET_DATA_URL=… RABBITMQ_URL=… python -m worker.regime_link_main

# 프론트
cd frontend && npm run dev
```

- 010 M6·M7은 dev에 병합되어 있고 011은 대역을 두지 않는다(research R8). 010 표는 사전 계산 배치가
  채우므로 수동 확인(S5~S7)은 010 검색 결과가 있는 환경을 전제로 한다. 자동 테스트는 Testcontainers에
  010 표 시드 행을 넣는다.
- 001 적재 표에 대표 ETF 일별 자료와 KOSPI 일별 자료가 있어야 한다. 없으면 워커 픽스처
  (`worker/examples/three_etf_24m/`)로 S1~S3만 돈다.

## 검증 명령 (묶음마다)

```bash
cd backend  && ./gradlew spotlessApply build          # ArchitectureTest 포함
cd worker   && ruff check src tests scripts && ruff format --check src tests scripts && pytest
cd frontend && npm run lint && npm run build && npm test   # skipped 수 확인
```

## 시나리오

### S1. 메시지 계약이 양쪽에서 같은 픽스처로 통과한다 (M1)

- 실행: `pytest worker/tests/regime_link/test_contracts.py` · `./gradlew test --tests '*RegimeLinkMessageContractTest*'`
- 기대: `docs/specs/regime-link-messaging/examples/*.json` 7개가 양쪽에서 round-trip. 토폴로지 상수 대조
  테스트는 M1에서 계약 문서와 대조하고 M5 T030 이후 RegimeLinkRabbitConfig 파일과 대조한다.

### S2. 잘라내기 정의가 수기 대조와 일치한다 (M2)

- 실행: `pytest worker/tests/regime_link/test_slicer.py`
- 기대: `reconciliation/manual_reconciliation.json`의 `window63`·`window21`·`kospi`와 일치. 미완성
  구간(`completedTradingDays=34`) 입력에서 `window63.tradingDays=34`, `complete=false`, `window21`은
  완성. `tradesPerMonth = tradeCount ÷ (tradingDays ÷ 21)`. 수익률·낙폭은 연환산하지 않고 회전율에는 ANNUALIZED_252 단위가 있다. 전체 구간 손익이 0이면 lossContribution을 생략하고 이익 종목도 포함한다. `FRESH_CASH`는 `initialCash` 기준 수익률.

### S3. 소비자가 오류를 세 갈래로 나눈다 (M3)

- 실행: `pytest worker/tests/regime_link/test_consumer.py`
- 기대: 깨진 JSON → reject(DLQ), 계산 실패 → `FINISHED(FAILED)` 발행 후 ACK, 발행 실패 → ACK 없이
  requeue. `FINISHED` 확인 전에 ACK가 나가는 경로가 없다. 백테스트 소비자 테스트 결과가 변경 전과
  같다(수·통과·skipped).

### S4. 가용성 판정이 표시 후보 안에서만 걸린다 (M4)

- 실행: 010 표(`regime_search_snapshot`·`regime_search_case`)에 표시 후보 10개 시드 행을 두고
  `GET /api/regime-links/availability?base=…&compare=…&mode=field&field=LABOR&entryPath=STRATEGY_RESULT`
- 기대: `cases[]`가 정확히 10개이고 `rank`가 010 값 그대로. 상장 전 사례는 `DATA_INSUFFICIENT/UNLISTED`
  · `shownInFiltered=false` · `excludedCount`에 포함. 완성 21 미만 사례는 `POST_INCOMPLETE`로 제외.
  분석 기간 밖 사례는 `OUT_OF_PERIOD`지만 `shownInFiltered=true`. `filterKey` 64자이고 같은 묶음으로
  다시 부르면 같은 값. `entryPath=MARKET_FLOW`면 `filter.applied=false`이고 전부 `shownInFiltered=true`.
  응답 어디에도 유지율·초과 수익·승패 키가 없다.

### S5. 링크 생성·재사용·확인·로그인 (M5)

1. 익명으로 `POST /api/regime-links` → `401 AUTHENTICATION_REQUIRED`, `regime_link` 행 0, 큐 메시지 0.
2. 로그인 후 POST /api/regime-link-searches로 목록과 linkSearchId를 만들고, 그 linkSearchId로 링크 요청(`CONTINUOUS`, 사례 NORMAL) → `202`, 링크 2개(`QUEUED`), 큐에 메시지 2개.
   워커가 처리하면 `GET /api/regime-links/{linkId}?token={base 토큰}`이 `COMPLETED`와 `result`를 돌려준다. 토큰 없이 부르면 400, 다른 실행의 토큰이면 404, 만료된 비로그인 토큰이면 410.
3. 같은 요청을 다시 → `202`, 두 링크 모두 `COMPLETED`, **큐 메시지 0**(재사용).
4. 분석 기간 밖 사례에 `confirmFullPeriod=false` → `200 OUT_OF_PERIOD_CONFIRM_REQUIRED`, 계산 0.
   `true`로 다시 → 004 `FULL_PERIOD` 실행이 접수되고 링크는 `PENDING_RUN`; 004 완료 후 자동으로
   `QUEUED → COMPLETED`. 원래 실행과 결과 주소는 그대로 열린다.
5. `scenario=FRESH_CASH` → 004에 `FRESH_CASH_FROM`(`startDate=d0`, `end=d63`) 접수, 링크
   `PENDING_RUN` → 완료 후 `COMPLETED`. `result.window63.return`이 `initialCash` 기준.
6. `DATA_INSUFFICIENT` 사례에 요청 → 링크는 `COMPLETED`·`result=null`·`availability.reason` 있음,
   큐 메시지 0. 실패(`FAILED`)와 화면 표기가 다르다.
7. 워커를 죽여 DLQ로 보내면 링크 `FAILED(SYSTEM_FAILURE)`; `POST …/retry` → `QUEUED`, `attempt=2`.

### S6. 시장 흐름 경로 (M6)

- 실행: 010 경제환경 검색의 사례 상세에서 '이 기간으로 전략 비교'(T087)를 누르거나
  `/regime-link/start?case={caseId}&sector=SEMICONDUCTOR`를 직접 연다. 익명으로 → 로그인 안내 → 로그인
  → 같은 주소 복귀 → 002(반도체 초기 선택) → 003.
- 기대: 003 '기간·금액·조건'에서 사례 사후 구간을 덮지 않는 시작일·종료일을 고를 수 없고 한 문장
  안내가 보인다. '분석하기'가 **접수되면 곧바로** `/regime-link?base=…&case=…`로 이동하고 기준 실행의 진행
  상태 → 사후 구간 계산의 진행 상태 → 결과 순으로 채워진다. 검색 대상은 전체 사례(필터 없음).

### S7. 진단 결과 경로 화면 (M7)

- 실행: 005 결과 화면의 '경제환경이 비슷했던 과거 보기'(T086)로 `/regime-link?base=…&compare=…`에 들어온다.
- 기대: 분야·기준월 선택 화면이 먼저(종합은 선택형, 기준월 기본은 최신). 고르면 주소에 `snapshot`이 붙고 기본 목록에 `shownInFiltered=true`만,
  "선택 ETF로 비교 가능한 기간"과 제외 수, '전체 경제환경 탐색으로 전환'. 전환하면 10개 전부 보이고
  자료 부족 사례는 전략 결과 자리만 '자료 부족'. 사례를 열면 63거래일 결과가 기본, 21거래일·손실
  기여는 상세, 전체 기간 결과 요약이 같은 화면에, KOSPI 두 값만, "최신 개정자료를 이용한 역사 탐색"
  과 "역사 설명용 기준" 표시. 미완성 사례는 "○○거래일 완성(미완성)". 현금 시작 토글을 켜면 그때
  진행 상태가 보이고 연속 운용과 다른 이유 한 문장이 붙는다. 비교 대상을 바꿔 다시 들어오면 한 문장
  알림과 제외·추가 사례가 보이고 **유지율 숫자는 화면에 없다**(DB `retention`에는 있다). 이때 분야 선택을 다시
  묻지 않고 기준월이 그대로다. 새 탭에서 같은 `base`로 들어오면 분야 선택부터이고 알림이 없다. 당시 뉴스
  영역은 발행 시각이 관측 구간 안인 뉴스만 보이고 없으면 012의 빈 상태 문구가 보인다.
- 정적 검사: `npm test`의 `forbiddenPhrases.test.ts`가 `components/regimelink/` 전 파일에서 금지
  표현 0건.

### S8. (결번)

- 010 읽기 대역을 두지 않게 되어 대역 격리 시나리오를 삭제했다(research R8, 2026-09-20). 번호는
  재사용하지 않는다.

### S9. 기록 항목 (FR-023, SC-006)

- 실행: S5·S7 뒤 `regime_link_search`·`regime_link` 조회.
- 기대: 검색 경로별 별도 행, `snapshot_key` 사본, `filter_id`, `shown_cases`에 순위·판정,
  `excluded_count`, 비교 묶음 변경 시 `previous_search_id`와 `retention`, `basis=HISTORICAL_REVISION`,
  링크에 `post_window`·`scenario`·`availability`. 결과 표에 사용자 id가 없다.

### S10. 측정 기록 (M8, T089)

- 실행: 표시 후보 10개 × 실행 2개 × 연속 운용. `GET /api/regime-links/availability` 30회 순차 호출의 응답 시간과,
  링크 20개의 `regime_link.queued_at → finished_at`(워커 1개)을 잰다.
  `SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - queued_at)) FROM regime_link WHERE finished_at IS NOT NULL;`
- 기록: 날짜·장비·자료 규모·p50/p95. 목표(300ms·3초)는 측정 목표이며 통과 조건이 아니다. 넘으면 수치와 병목
  추정만 남긴다.

## 실행 기록

### 2026-09-21 (M8 · T083)

**이 절은 실제로 돌린 것만 적는다.** 돌리지 못한 시나리오에는 결과를 적지 않고 "미실행"과 그 이유만
남긴다. 아래 표의 "확인함"은 명령을 돌려 출력을 본 것이고, "미실행"은 이번에 돌리지 않은 것이다.

#### 묶음 검증 명령 (전체 합계)

기준선은 `origin/dev` `b36ccbab`이다. 세 파트 모두 011 작업 뒤 실패 0이다.

| 파트 | 명령 | 기준선 | 2026-09-21 |
|---|---|---|---|
| 백엔드 | `./gradlew build` | tests=1883 · failures=0 · errors=0 · **skipped=4** | tests=1918 · failures=0 · errors=0 · **skipped=4** |
| 워커 | `pytest` | 456 passed · **1 skipped** | 516 passed · **0 skipped** |
| 프론트 | `npm test` | 117 files / 1150 tests | 135 files / 1256 tests · 실패 0 |
| 프론트 | `npm run lint` | 오류 0 · 경고 20 | 오류 0 · 경고 20 |

**백엔드 skipped 4건은 011과 무관하다.** `MarketLiveApiTest::realEndpointsProduceSanitizedResults`,
`NewsPerformanceIT` 2건, `RequiredDockerTest::Docker를 요구하는 환경에서는 Docker에 붙을 수 있다`이다.
이 회차에는 Docker 데몬이 떠 있어 DB 통합 시험이 실제로 돌았다 — 데몬이 꺼져 있던 앞선 회차에서는
592건이 조용히 건너뛰어졌으므로, 이 숫자를 인용할 때는 **Docker가 떠 있었는지를 함께 확인한다.**

#### 시나리오별

| 시나리오 | 결과 |
|---|---|
| S1 (워커 몫) | **확인함.** 아래 명령에 포함 |
| S1 (백엔드 몫) | **미실행.** `RegimeLinkMessageContractTest`를 따로 돌리려 했으나 이 작업 환경에 JDK가 없어(`JAVA_HOME` 미설정·`java` 없음) `./gradlew`가 기동하지 않았다. 백엔드 전체 `build`는 위 표대로 통과했으므로 이 시험도 그 안에서 돌았다 |
| S2 | **확인함** |
| S3 | **확인함** |
| S4~S7 · S9 | **미실행.** PostgreSQL·RabbitMQ·백엔드·워커 둘·프론트를 모두 띄우고 010 검색 결과가 있는 자료가 필요하다. 이번 회차에 그 기동을 하지 못했다 |
| S10 (T089) | **미실행.** 아래 "T089 측정" 참고 |

#### 확인한 명령과 출력

```bash
cd worker && uv run pytest tests/regime_link/test_contracts.py     tests/regime_link/test_slicer.py tests/regime_link/test_consumer.py -q
# 72 passed in 0.33s   (skipped 0)
```

- S1(워커 몫): `test_contracts.py`가 `docs/specs/regime-link-messaging/examples/*.json`을 round-trip한다.
- S2: `test_slicer.py`가 `examples/reconciliation/manual_reconciliation.json`과 대조한다.
- S3: `test_consumer.py`가 오류 세 갈래(reject → DLQ / `FINISHED(FAILED)` 후 ACK / 발행 실패 requeue)를 본다.

#### 확인 필요로 남은 것

- **큐를 실제로 지나는 왕복은 아직 아무도 확인하지 않았다.** T036(Testcontainers RabbitMQ·PostgreSQL
  왕복)이 미작성이고 S5의 수동 확인도 미실행이라, 백엔드가 발행한 작업이 워커에 닿아 이벤트로 돌아오는
  경로 전체를 끝에서 끝까지 본 기록이 없다. 단위·통합 시험은 양쪽을 따로 볼 뿐이다.
- S5~S7·S9의 화면·DB 기록 항목은 자동 시험이 같은 조건을 흉내 낸 것으로 갈음했고 사람이 눈으로 본
  기록은 없다.

### T089 측정 — 미실행

S10의 두 측정(① `GET /regime-links/availability` 30회 순차 호출의 p95, ② `regime_link.queued_at →
finished_at`의 p95)은 **이번 회차에 재지 않았다.** 전체 기동과 010 검색 결과가 있는 자료가 있어야
하는데 확보하지 못했다. 목표(300ms·3초)는 측정 목표이지 통과 조건이 아니므로(원칙 IX, research R9)
**측정하지 않았다는 사실만 남기고 추정치를 적지 않는다.** 다시 잴 때는 S10의 명령과 함께 날짜·장비·
자료 규모·p50/p95를 이 절에 적는다.

관련해 T110(가용성 조회의 반복 질의)이 미해결로 남아 있다 —
`DatabaseTradingCalendarAdapter.calendarQuery`가 호출마다 자료 버전을 다시 읽어 달력 질의가 두 배가
된다는 지적이며, 실측이 이루어지면 그 태스크의 판단 근거가 된다.

## 2026-09-20 추가 인수 시나리오 (아직 미실행)

- 입력 게이트: DEP-004-011에서 개별 체결·청산 비용·구간 경계 보유·분배금을 저장 필드와 대조한다.
  입력 대응이 미완료면 M1 계산 fixture 동결·M2 착수를 통과로 기록하지 않는다.
- 2010년부터 원천이 있고 2022년부터 실행한 전략으로 2018년 사례 조회: 기본 목록 유지,
  OUT_OF_PERIOD, 확인 전 계산 0건, 확인 후 전체 기간 실행 재사용.
- 구간 전 매수→구간 내 일부 매도·분배금·종료 보유·청산 비용: R3 독립 계산과 손익 합·회전율 일치.
  전체 손익 0은 기여도 생략, 이익/손실 종목 혼합은 모두 포함. FRESH_CASH는 baseline 자산 행 불필요.
- 검색 생성 POST 뒤 사례 열기 없이 종료: 변경 기록·알림 존재. 새 목록 0건도 동일하고 큐 메시지 0건.
  다른 탭의 검색은 previousLinkSearchId 없이 섞지 않음. 같은 clientRequestId 재시도는 행 1개.
- 익명 검색·기존 공개 결과 조회는 가능, 계산·검색 기록 POST는 401. 로그인 후 같은 사례·조건 복귀.
  익명 목록 변경은 현재 탐색 흐름에서 표시하며 서버 개인 기록은 만들지 않음.
- 구간 10거래일째 상장폐지: 기본 목록 제외·제외 수 포함, 전체 탐색에서 그 전략 자리에 확보 거래일 수와 사유만,
  큐 메시지 0건. 40거래일째: `window63.tradingDays=40`·`partial`, `window21` 완성, 다른 전략의 63거래일 값 불변.
- 조립 결함(기준일 행 누락) 메시지: `FAILED(INPUT_INCOMPLETE)`, 화면에 '자료 부족' 없음, 재시도가 같은 코드로
  실패하면 '다시 시도'가 사라짐. 상장 전 사례 자리에는 처음부터 '다시 시도' 없음.
- 같은 조건의 2022년 시작·2015년 시작 요청이 같은 전체 기간 재계산을 공유: 실행·링크 행 1개, `regime_link_use`는
  각자, '전체 기간 결과 보기'는 각자의 결과 주소. 만료된 비로그인 요청 행이 지워져도 011 표의 FK가 막지 않음.
- 사례 계산 요청은 위 검색 생성으로 얻은 linkSearchId를 사용한다. 구형 base/compare 직접 POST 예시는
  linkSearchId로 치환하여 검증한다. 회전율의 ANNUALIZED_252 단위가 표시된다.

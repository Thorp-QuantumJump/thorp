# 사례 연결 메시지 계약 (Backend ↔ Worker, RabbitMQ)

- 상태: **schemaVersion 1** (2026-09-20, spec 011 T001). 설계 근거는
  [`specs/011-regime-strategy-link/contracts/regime-link-messaging.md`](../../../specs/011-regime-strategy-link/contracts/regime-link-messaging.md)와
  research R1~R15이다. 구간 산식은 research R3, 004 저장 결과와의 입력 대응은 R3의 DEP-004-011이다.
- 백테스트 메시징(`docs/specs/backtest-messaging/`)과 **분리된 경로**다(research R1). 표기 규칙은 같고
  exchange·큐·워커 엔트리포인트가 다르다.
- 이 폴더의 `examples/*.json`이 **양쪽 코드의 단일 기준**이다. 백엔드
  (`regimelink/application/port/message`)와 워커(`worker/src/worker/regime_link/contracts.py`)가 이
  파일을 테스트에서 직접 읽어 round-trip을 검증한다. 필드를 바꾸면 이 폴더 → 백엔드 record → 워커
  모델 순서로 같은 MR에서 함께 고친다.

## 표기 규칙

| 항목 | 규칙 |
|---|---|
| 소수 | plain string (`"10412000.00"`). 부동소수로 싣지 않는다 |
| 정수 | 숫자 (`63`, `179`). 수량·거래일 수가 여기 해당한다 |
| 날짜 | `YYYY-MM-DD` |
| 시각 | UTC ISO-8601 (`2026-09-18T02:00:00Z`) |
| null | **키를 생략한다.** 폐지되지 않은 종목에 `delistedOn`이 없는 것이 그 예다 |
| `schemaVersion` | 이벤트는 **본문 첫 필드**에 싣고, 작업은 본문에 두지 않고 AMQP 헤더로만 보낸다 |

## 메시지 속성과 토폴로지

- exchange `regime-link`(direct, durable), 큐 `regime-link.jobs`(quorum, `x-delivery-limit=1`,
  DLX `regime-link.dlx` → `regime-link.jobs.dlq`), `regime-link.events`.
- routing key: 작업 `regime-link.job.requested`, 이벤트 `regime-link.event`.
- 속성: `content_type=application/json`, `delivery_mode=2`, header `schemaVersion: 1`,
  작업 `message_id = "RLJ-" + linkId + "-" + attempt`, `correlation_id = linkId`.
- **토폴로지는 백엔드가 선언한다.** 워커는 선언하지 않고, 큐가 없으면 만들지 않고 종료한다.

## 파일

| 파일 | 무엇을 보이나 |
|---|---|
| `job-continuous.json` | 연속 운용. 시작 보유가 있고 구간 안에서 일부 매도·매수·분할·분배금이 난다 |
| `job-fresh-cash.json` | 현금 시작. `openingPositions`가 빈 목록이고 기준은 `run.initialCash`다. d0에 전량 매수한다 |
| `job-partial-delisted.json` | 구간 안 상장폐지. `lastAvailable`·`completedTradingDays`가 이미 폐지 전날 기준으로 줄어 있다 |
| `event-started.json` | 계산 시작 |
| `event-finished-completed.json` | 63거래일 완성. `window63.complete=true` |
| `event-finished-incomplete63.json` | 폐지로 63을 못 채운 경우. `partial=true`·`delistedOn`과 `window63.complete=false`가 함께 선다. `window21`은 언제나 완성이다 |
| `event-finished-failed.json` | 계산 실패. `result`가 없고 `error`가 있다 |

## 픽스처가 보장하는 것

작업 픽스처의 수치는 실제 현금 회계로 만들어 다음이 성립한다.

- `portfolioSeries`가 `baselineDate`부터 `lastAvailable`까지 빠짐없이 있고 행 수가
  `completedTradingDays + 1`이다.
- 개별 체결과 `cashFlows`만으로 구간 현금이 되살아난다(`cash[마지막] = cash[baselineDate]` + 매도대금
  − 매수대금 − 직접 차감 비용 + 분배금).
- 비용은 `수량 × referencePrice`에 `KR-ETF-2026-09`(수수료 1.5 · 호가차 3 · 슬리피지 5 · 세금 0bps)를
  매긴 값이고, 가격 충격은 `(호가차 + 슬리피지) ÷ 수량`, `cashCost`는 수수료 + 세금이다.
- `dailyPositions`는 수량이 바뀐 날만 담으며 **기업행위로만 바뀐 날(분할)도 들어 있다.** 스냅샷 한 건은
  그날 보유 전체이고 수량 0인 종목은 빠져 있으므로, 워커의 이월은 덮어쓰기다.

## 픽스처가 보장하지 않는 것

- **결과 이벤트의 `result` 수치는 형식과 작업 메시지와의 정합을 보이는 예시다.** 구간 산식(R3)의
  기대값 대조는 `examples/reconciliation/`(spec 011 T014)이 워커를 import하지 않는 독립 계산으로
  따로 만든다 — 기대값을 워커 출력으로 채우면 대조가 아니다. `lossContribution`·`kospi`·`holdings`는
  모양만 맞춘 값이다.
- 시장 자료(원시 종가)는 메시지에 없다. 워커가 `market_data.py`로 같은 구간을 따로 읽는다.
- 날짜는 주말만 제외한 픽스처 자체 달력이라 실제 KRX 휴장일과 다르다.

## 다시 만드는 법

손으로 고치지 말고 생성기를 고쳐 다시 만든다. 시드가 고정돼 있어 같은 값이 나온다.

```bash
python docs/specs/regime-link-messaging/generate_fixtures.py
```

값이 바뀌면 `inputDigest`도 함께 바뀐다. 이 해시는 `inputs`·`window`·`job.scenario`·`marketData`를
`sort_keys=True`, 구분자 `(",", ":")`로 직렬화한 canonical JSON의 sha256이다.

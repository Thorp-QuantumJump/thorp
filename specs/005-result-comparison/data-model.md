# Phase 1 데이터 모델: 결과 이해·비교

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-16

이 화면은 값을 계산하지 않는다. 004가 저장한 결과를 읽어 그리고, 공개 열람에 필요한 것만 새로 저장한다. 아래 §1이 새로 더하는 저장 항목이고 §2~§4는 화면이 쓰는 표시 모델이다.

## 1. 저장 항목 변경

새 테이블은 만들지 않는다. 004의 `backtest_request` 하나만 손댄다.

| 컬럼 | 형 | 설명 |
|---|---|---|
| requester_id | BIGINT FK → users, **NOT NULL → NULL 허용** | 로그인 사용자의 요청에만 채운다. 비로그인 요청은 비어 있다 ([research.md](research.md) R-02) |
| public_token | CHAR(32) UNIQUE NOT NULL | 결과 화면 주소에 쓰는 무작위 128비트 값의 16진 표기. 접수 시점에 발급한다 (R-01) |
| public_token_expires_at | TIMESTAMP NULL | `requester_id`가 없는 요청에만 채운다. 로그인 사용자의 결과는 만료하지 않는다 (R-03) |

**인덱스**: `public_token`에 유니크 인덱스, `(public_token_expires_at)`에 정리 작업용 인덱스.

**마이그레이션 주의**: `requester_id`를 NOT NULL에서 NULL 허용으로 바꾸는 것은 제약 완화라 기존 행에 영향이 없다. `public_token`은 NOT NULL이므로 기존 행에 값을 채우는 단계가 먼저 와야 한다. 004 data-model이 기존 데모 데이터 삭제를 권장해 두었으므로 그 방침을 따르면 채울 행이 없다. 파일 이름은 저장소의 현재 관례인 `V{yyyyMMddHHmmss}__{설명}.sql`을 따른다(최신은 `V20260916104500__news_grouping_v2.sql`). 서로 다른 작업이 같은 번호를 쓰지 않게 하려는 규칙이므로 constitution "스키마 변경 규칙" 절과 함께 지킨다.

**권한 판정**: 취소·재시도는 다음 둘 중 하나를 만족해야 한다.

- 로그인 사용자: `requester_id`가 현재 사용자와 같다.
- 비로그인: 요청에 담긴 `public_token`이 그 행의 값과 같다.

어느 쪽도 아니면 404로 숨긴다(004 R-12의 기존 방침 유지).

## 2. 결과 주소

| 항목 | 값 | 비고 |
|---|---|---|
| publicToken | 32자 16진 문자열 | 주소에 노출된다. 004의 `backtestId`는 노출하지 않는다 |
| expiresAt | 시각 또는 없음 | 비로그인 결과에만 있다 |
| compareToken | 32자 16진 문자열 또는 없음 | 질의 문자열 `?compare=`로 싣는다 (R-04) |

**상태 전이**

```text
발급 ──> 열람 가능 ──> (비로그인·유효 시간 경과) ──> 만료 ──> 삭제
                   └─> (로그인 사용자) ──────────> 만료 없음
```

- 만료된 토큰 조회는 `410 RESULT_EXPIRED`다. 존재한 적 없는 토큰은 `404 RESOURCE_NOT_FOUND`다. 화면이 둘을 다르게 보여야 하므로 코드를 나눈다.
- 삭제는 정리 작업이 수행하며 만료 시각이 지난 비로그인 요청과 그 결과를 함께 지운다.

## 3. 화면 표시 모델

004 응답을 화면이 쓰는 모양으로 옮긴 것이다. 서버가 새로 계산하지 않고 필드를 골라 담는다.

### 3-1. 결과 요약 카드 (FR-001~FR-003)

| 필드 | 출처 | 비고 |
|---|---|---|
| netTotalReturn | `summary.netTotalReturn` | 비용 차감 후 전체 수익률 |
| maxDrawdown | `summary.maxDrawdown` | 운용 중 고점 대비 최대 하락 (FR-002) |
| totalTradingCost | `summary.totalTradingCost` | 거래비용 |
| conditionLine | `config` + `versions` | 분석 기간·비교 대상·비용 적용 여부 한 줄 |
| interpretation | 문장 틀 선택 결과 | §3-5 |
| liquidation | `summary.liquidation{markToMarket, liquidated}` | 종료 시 시가평가와 청산 비용 포함을 구분 (FR-015) |

숫자 세 개 중 하나라도 `summaryUnavailable`로 비어 있으면 그 자리에 0이나 대체값을 넣지 않는다(FR-013, 원칙 VI).

### 3-2. 비교 뷰 (FR-004, FR-005)

| 필드 | 설명 |
|---|---|
| base | 기준 결과. 사용자 고정 비중 또는 동일 비중 |
| target | 비교 대상 결과 또는 없음 |
| targetState | `NONE` `REQUESTED` `RUNNING` `READY` `FAILED` `EXPIRED` |
| comparisonKind | `BASIC`(기본 설정) 또는 `AUTO_SEARCH`(자동 탐색 후보) — 화면에서 구분 표시 (FR-011) |
| commonConditions | 섹터 후보 집합·평가 날짜·금액·비용·정보 이용 기준 |
| periodMismatch | 두 결과의 평가 기간이 다를 때 공통 구간과 재계산 대상 두 건 |

`targetState`가 `READY`가 아니면 그래프에 기준만 그린다. `FAILED`는 사유 한 문장과 다시 시도 선택을 함께 둔다(Edge Cases).

### 3-3. 그래프 계열 (FR-001, R-05·R-08·R-09)

| 필드 | 출처 | 비고 |
|---|---|---|
| points[].date | `/timeseries[].date` | |
| points[].equity | `/timeseries[].equity` | 세로축. 사용자가 입력한 금액에서 출발하는 비용 차감 후 원화 자산가치 |
| points[].drawdown | `/timeseries[].drawdown` | 음영 구간을 찾는 데만 쓴다 |
| drawdownWindow | `drawdown` 최솟값 지점과 직전 고점 | `summary.maxDrawdown`과 어긋나면 음영을 그리지 않는다 (R-05) |
| lineStyle | `SOLID`(기준) / `DASHED`(비교 대상) | 색에 기대지 않는 구분 (SC-010) |
| endLabel | 곡선 오른쪽 끝에 붙는 이름 | 범례만으로 구분하지 않는다 |

### 3-4. 결과 상태 표시 (FR-012, FR-013)

| 필드 | 출처 | 표시 규칙 |
|---|---|---|
| validationTier | PD-12 확정 전 항상 `UNVALIDATED` | '탐색 결과 / 미사용 검증 결과 / 미검증' |
| partialRange | `partial` + `progress.lastTradingDate` | 완료 범위를 날짜로 밝힌다 |
| summaryUnavailable | `summaryUnavailable` | 숫자가 계산되지 않았다고 쓴다 |
| dataLimitations | `/exceptions`의 `kind`·`rule` 요약 | 결과 옆 한 문장 |
| failure | `failure{code, message}` | 실패 사유. 낮은 수익으로 표현하지 않는다 |

결과 의미를 바꾸지 않는 항목은 여기에 넣지 않는다. 경고를 목록으로 늘리지 않는다(FR-013 후단).

### 3-5. 해석 요약 문장 틀 (FR-006, R-07)

문장 틀은 `frontend/src/features/result/summarySentences.ts`가 소유한다. 선택 기준은 세 가지다.

| 조건 | 틀의 성격 |
|---|---|
| 비교 대상 없음 | 기준 결과의 세 숫자만 값으로 서술한다 |
| 비교 대상 있음 | 같은 조건에서 두 결과의 차이를 값으로 서술한다 |
| 부분 결과 | 완료 범위까지의 값임을 함께 밝힌다 |

각 틀은 값 자리만 비운다. 금지 어휘('추천', '최적', '승자', '우승', '유리', '좋다', 확률·유사도 표현)가 틀 문자열에 없음을 단위 테스트가 확인한다(SC-004).

### 3-6. 상세 패널 (FR-007~FR-009)

| 항목 | 출처 | 계산 제한 표시 |
|---|---|---|
| 회복 기간·회전율 | `summary.analytics{recoveryDays, turnover}` | 값이 없으면 제한 표시 |
| 상관관계·위험 기여·손실 기여 | 워커가 계산해 저장하지만 API로 나오지 않는다 ([research.md](research.md) R-06). 노출 전까지 제공되지 않음 | 추정 기간·산정법을 함께 보인다 |
| 전체 거래 내역 | `/trades` 페이지 조회 | 첫 화면에 나열하지 않는다 |
| 보유 구성 | `/positions?date=` | |
| 목표·실제 비중 | `/weights` | `weightsSource`로 사용자 입력과 규칙 계산을 구분 (FR-014) |
| 비용 가정·비교 규칙 | `config` + `versions` | |
| 구성종목 중복·대표 ETF 구성·출처 | `GET /sectors/{code}/instrument-profile` (R-10) | 001이 소유한 한계 문구를 그대로 쓴다 |

자료가 짧거나 불안정해 계산이 제한된 항목은 0이나 임의 값으로 대체하지 않는다(FR-009, 원칙 VI).

## 4. 검증 규칙

| 규칙 | 근거 |
|---|---|
| 한 그래프의 두 결과는 섹터 후보 집합·평가 기간·금액·비용·정보 이용 기준이 같아야 한다 | FR-005, SC-003 |
| 기간이 다르면 겹쳐 그리지 않는다. 사용자 확인 뒤 공통 구간으로 두 건을 다시 요청한다 | FR-005 |
| 첫 화면이 받아 오는 자료는 `summary`·`config`·`versions`·시계열로 한정한다 | SC-001 |
| 결과 화면 주소에 004의 내부 실행 식별자가 드러나지 않는다 | SC-009 |
| 만료된 주소에 빈 값이나 0으로 채운 결과 화면을 그리지 않는다 | SC-008 |
| 해석 요약 문장에 금지 어휘가 없다 | SC-004 |
| 회색조에서도 두 결과가 구분된다 | SC-010 |

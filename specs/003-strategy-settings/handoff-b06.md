# Handoff: B06 분석 요청과 평가 기간을 연결한다

작성일: 2026-09-17
대상 묶음: B06 · [S15P21E205-238](https://ssafy.atlassian.net/browse/S15P21E205-238) `[FE] 분석 요청과 평가 기간을 연결한다`
포함 태스크: **T098(결정 기록) → T042 → T039**
실행 방법: 이 문서를 함께 넘기고 `speckit-implement` 스킬로 진행한다.

> 이 파일은 커밋하지 않는 인계 메모다. 결정 내용은 첫 커밋에서 tasks.md와 research.md에 옮겨 적는다.

---

## 1. 이 묶음이 끝나면 달라지는 것

화면에서 만든 분석 요청이 실제로 `POST /backtests`로 나가고, 카드의 워밍업·최소 평가 기간 판정이
001의 실제 평가 기간 자료로 이루어진다. 지금은 `frontend/src/components/strategy/analysisSupport.ts`의
`notConnectedSupport`가 세 통로를 모두 막아 둔 상태다.

## 2. 티켓·브랜치 운영 (결정됨)

**T098만을 위한 티켓은 따로 만들지 않는다.** T098 기록은 T042의 구현 방향이므로 B06 티켓(238)의
첫 커밋으로 넣는다. 규칙상 티켓은 곧 만들 브랜치에 맞춰 세우고, 한 티켓의 작업을 여러 브랜치로 나누지 않는다.
T098을 따로 떼면 T042와 같은 목적의 작업이 두 브랜치로 갈라진다.

**이 방식의 한계**: B06은 001과 004를 기다리는 묶음이라 MR이 늦게 머지되고, 그동안 결정 기록도 dev에
들어가지 않는다. 다른 사람이 이 결정을 dev 문서에서 바로 봐야 하는 상황이 생기면, 그때만 문서 티켓을
따로 만든다. 001 담당자 김균호 님과는 이미 메시지로 합의했으므로 지금은 B06에 넣는 방식으로 충분하다.

작업 순서:

1. `git fetch origin`으로 로컬 dev를 맞춘 뒤 **origin/dev 기준 새 워크트리**에서 브랜치를 만든다.
   브랜치 이름: `feat(fe)/238-analysis-request-connect`
2. 티켓 238은 이미 있다. 새로 만들지 않고 상태만 "진행 중"(전이 id 21)으로 바꾼다.
3. **첫 커밋은 T098 기록이다**(§4). 코드 변경을 섞지 않는다.
4. T042, T039 순서로 구현한다.
5. **묶음을 절반만 올리지 않는다.** §6의 차단 조건이 남아 있으면 브랜치에 커밋·push까지만 하고 MR은 열지 않는다.
6. MR은 push한 뒤 GitLab MR 생성 링크와 템플릿 본문을 코드 블록으로 건넨다. 브라우저 자동화로 MR을 만들지 않는다.
   MR 제목 형식은 `feat(fe): 요약`이고, 본문은 핵심부터 짧게 쓴다.

주의: 메인 체크아웃은 오래된 detached HEAD에 머물러 있고 `specs/002-sector-weight-selection/tasks.md`에
커밋하지 않은 수정이 있다. 그 수정은 B06과 무관하므로 새 워크트리로 가져가지 않는다.

## 3. 착수 직전에 확인할 것 (차단 조건 재점검)

구현하기 전에 아래 두 가지를 origin/dev 코드로 다시 확인한다. 결과에 따라 §6의 진행 범위가 달라진다.

| 확인 | 방법 | 2026-09-17 기준 상태 |
|---|---|---|
| 001 `GET /sectors/evaluation-window` 공개와 ①안 필드 반영 | `SectorController`의 매핑, `specs/001-etf-market-data/contracts/sector-api.md`에서 `maxWarmupTradingDays`·`tradingDaysFromBase` 검색 | 없음. 김균호 님이 약 1시간 뒤 작업을 시작한다고 답했다 |
| 004 `rulesVersion` 출처 결정 | 신재은 님의 답변, 004 계약·컨트롤러에 규칙 버전 조회가 생겼는지 | 미결. 질문 초안만 만들었다(§5-2) |

## 4. 첫 커밋: T098 결정 기록

T098 지시대로 결정을 **T042 아래 구현 메모**와 **research.md R7-1**에 적고, tasks.md의 T098을 체크한다.
R7-1에는 "값이 바뀔 때마다 서버를 부르지 않는다"는 원칙이 평가 기간 판정에도 적용된다는 점과,
기각한 갈래 ②·③의 사유를 남긴다.

### 4-1. 합의한 갈래: ① 001 응답이 기준일과 거래일 목록을 함께 내리고 화면이 계산한다

배경: T042 원문은 "선택 전략은 현재 설정으로 조회"라서 값이 바뀔 때마다 서버를 부르게 된다. 그런데 T058과
티켓 241의 완료 기준은 "값을 만지는 동안 서버로 요청이 나가지 않는다"이다. B09가 이 성질을
`StrategySettingsPage.test.tsx`에서 `fetch`·`XMLHttpRequest.open` 호출 0건으로 고정했다.
T042를 원문대로 구현하면 그 검사가 깨진다.

기각한 갈래:

- **② 전략별 워밍업 후보를 한 번에 조회한다**: 001이 003의 파라미터 조합을 알아야 한다. 입력형 축은 후보가 유한하지 않다.
- **③ 매번 서버를 부르고 늦게 온 응답은 버린다**: research R7-1이 "늦게 도착한 응답이 이미 바뀐 값을 판정한다"는 이유로 기각한 방식이다.

### 4-2. 001 담당자(김균호)가 제안한 계약

요청:

```text
GET /sectors/evaluation-window?sectors=SEMICONDUCTOR,BATTERY&strategy=SECTOR_MOMENTUM&maxWarmupTradingDays=200
```

| 인자 | 필수 | 뜻 |
|---|---|---|
| `sectors` | 예 | 후보 섹터 코드. 기존과 같다 |
| `strategy` | 예 | 기존과 같다 |
| `maxWarmupTradingDays` | 아니오 | 새로 더한다. 화면이 훑을 워밍업의 상한. 주지 않으면 등록표 최대값 |

`maxWarmupTradingDays`를 두는 이유는 001이 003의 파라미터 조합을 몰라도 되게 하기 위해서다.
화면이 필요한 만큼을 말하고, 001은 그만큼만 내려 준다.

응답(기존 구조에 필드 셋을 더한다):

```json
{
  "commonStart": "2019-04-18",
  "commonEnd": "2026-09-07",
  "warmupTradingDays": 147,
  "bindingCandidate": "305720.XKRX",
  "excludedRanges": [],
  "evaluable": true,
  "datasetId": "KRX_DAILY_20260915",

  "warmupBaseDate": "2018-09-12",
  "tradingDaysFromBase": ["2018-09-12", "2018-09-13", "2018-09-14", "..."],
  "maxWarmupTradingDays": 200
}
```

| 새 필드 | 뜻 |
|---|---|
| `warmupBaseDate` | 워밍업이 0일 때의 `commonStart`. 기준 후보의 상장일을 달력에 맞춘 날 |
| `tradingDaysFromBase` | `warmupBaseDate`부터 이어지는 거래일 오름차순 목록 |
| `maxWarmupTradingDays` | 화면이 계산할 수 있는 워밍업 상한. `tradingDaysFromBase.length - 1`과 같다 |

### 4-3. 화면 계산 규칙

- **공통 시작일**: `commonStart(N) = tradingDaysFromBase[N]`이다(0-기준). 001 `EvaluationWindowService.firstEvaluable`과
  픽스처의 `$firstEvaluable` 설명이 같은 0-기준을 쓴다. 워밍업이 0이면 `warmupBaseDate`가 된다.
- **기준 후보**: 모든 후보가 같은 달력을 쓰므로 `bindingCandidate`는 워밍업 값과 무관하다. 화면에서 다시 계산하지 않는다.
- **평가 가능 여부**: `commonStart(N) > commonEnd`이면 `evaluable: false`로 판정한다.
- **상한 초과**: `N > maxWarmupTradingDays`이면 계산할 수 없다. 이 경우의 판정은 §4-4 1번 답을 따른다.
  답이 없으면 값을 지어내지 않고 "판정할 수 없음"으로 막는다(원칙 VI).
- **판정 분류**: 결과를 `'blocked'`와 `'reduced'`로 나누는 방식은 T042 원문과 같다. 남는 기간을 `minEvaluationYears`와 비교한다.
- **조회 시점**: 서버 조회는 **섹터 집합이 바뀔 때만** 나간다. 값을 바꾸는 동안에는 받아 둔 응답으로만 계산한다.
- **요청 상한 계산**: `maxWarmupTradingDays`는 등록표에서 계산해 **항상** 보낸다. 각 전략의 워밍업 규칙(`sum`)에
  축별 최대값을 넣는다. 입력형 축은 `range.max`를 쓰고, 목록형 축은 후보 가운데 최대값을 쓴다.

### 4-4. 001에 보낸 보완 질문 네 가지 (답변 대기)

착수할 때 답을 확인하고, 받은 답을 T042 구현 메모에 함께 적는다. 답이 없으면 "미결"로 적는다.

1. **달력이 모자랄 때**: 지금 구현은 상장일 뒤 거래일이 워밍업보다 적으면 `CALENDAR_TOO_SHORT`로 실패한다.
   새 필드는 오류 대신 있는 만큼만 목록을 주고 `maxWarmupTradingDays`를 실제 길이 - 1로 줄여 달라고 요청했다.
   목록 끝은 `commonEnd`를 넘지 않게 해 달라고 했다. 이 방식이면 화면은 상한을 넘는 워밍업을 "분석할 수 없음"으로 판정한다.
2. **`excludedRanges`를 자르는 기준**: 지금은 워밍업을 반영한 `[commonStart, commonEnd]`로 자른다.
   화면이 더 짧은 워밍업으로 다시 계산하면 앞쪽 구간을 알 수 없으므로 `warmupBaseDate`부터 잘라 달라고 요청했다.
   별도 필드로 주어도 된다고 했다.
3. **`strategy` 인자**: 새 필드는 섹터 집합만으로 정해진다. 상한을 줄 때 `strategy`를 생략할 수 있으면
   카드 다섯 장을 요청 한 번으로 판정할 수 있다고 제안했다.
4. **`maxWarmupTradingDays` 기본값**: 화면이 항상 보내므로, 001이 003 등록표를 읽지 않도록 값이 없을 때 400으로 두어도 된다고 했다.

## 5. 구현 태스크 요약과 남은 결정

### 5-1. T042 워밍업·최소 평가 기간 판정 연결

- **API 함수**: `frontend/src/api/sectors.ts`에 `fetchEvaluationWindow({ sectors, strategy?, maxWarmupTradingDays })`를 둔다.
- **훅**: `frontend/src/hooks/useEvaluationWindow.ts`가 응답을 받아 두고 §4-3 규칙으로 판정한다.
  원문의 "선택 전략은 현재 설정으로 조회"는 T098 결정으로 **대체한다**.
- **실패 처리**: 404·미구현·실패면 카드에 `EVALUATION_WINDOW_UNAVAILABLE` 문장을 보이고 '분석하기'를 막는다.
- **연결 위치**: `analysisSupport.ts`의 `judgeWarmup` 통로에 연결한다.
- **지켜야 할 기존 검사**: B09의 "값을 만지는 동안 요청 0건"과 T100의 "워밍업을 계산할 수 없으면 `unknown`"이 계속 통과해야 한다.

### 5-2. T039 분석 요청 전송

- **전송 함수**: `frontend/src/api/backtest.ts`에 `createBacktest(request)`를 두고 `POST /backtests`로 보낸다.
  필드 구성은 tasks.md T039 원문과 `docs/specs/backtest-api/README.md` §1을 따른다.
- **버전 출처 격리**: `frontend/src/api/analysisVersions.ts`의 `fetchAnalysisVersions()` 한 곳에서만 버전을 읽는다.
  결정 전에는 `null`을 돌려주고 화면은 `ANALYSIS_VERSIONS_UNAVAILABLE`로 막는다. 값을 지어내지 않는다.
- **오류 형태**: `BacktestRequestError`는 `code`와 함께 응답 `details`도 실어야 한다. 공용 `ApiClientError`는
  `details`를 싣지 않는다. B09의 T099가 `readBacktestRejection`에서 `details.axis`를 읽는다.
- **최소 평가 기간**: `minEvaluationYears`는 004 계약에 자리가 없어 요청에 싣지 않는다("착수 전에 알아둘 것" ⑧).

**004에서 확인한 사실 (2026-09-17 origin/dev 기준)**:

- **비용 버전**: `costProfileVersion`은 선택 필드다. `null`이면 서버가 `PROVISIONAL-DEMO-1`을 쓴다
  (`application.yml`의 `default-cost-profile`). 화면에는 기본 버전 이름과 임시값 여부를 읽을 수단이 필요하다
  (`AnalysisVersions.costProfileProvisional`).
- **규칙 버전**: `rulesVersion`은 필수다. `strategy_rules` 표에 없으면 422 `RULES_NOT_REGISTERED`로 거절한다.
  `StrategyRulesService.findAll()`은 있지만 HTTP 엔드포인트는 없다.

**004 담당자(신재은)에게 물을 갈래** (질문 초안만 만들었으므로 실제로 보냈는지 확인해야 한다):

1. **004가 조회 API를 연다.** 예: `GET /backtests/strategy-rules`가 `[{ strategyId, rulesVersion, registered }]`를 돌려주고,
   같은 응답에 `{ defaultCostProfileVersion, provisional }`를 함께 준다.
2. **003 등록표 응답에 전략별 `rulesVersion`을 싣는다.** 이 경우 003 `strategy` 패키지가 004 `backtest` 패키지를 읽는 새 의존 방향이 생긴다.
3. **`rulesVersion`을 선택 필드로 바꾼다.** 비우면 서버가 현재 등록 버전을 채운다. 재현성에 문제가 없는지는 004가 판단한다.

위 경로와 필드 이름은 003이 만든 예시이며 004 계약이 아니다. 답을 받으면 그 결정을 T039 구현 메모와
data-model §7 "확인된 빈 구멍"에 적는다.

## 6. 차단 조건별 진행 범위

| 상황 | 할 일 |
|---|---|
| 001·004 모두 미결 | T098 기록만 커밋한다. T042·T039는 대역(mock) 뒤에서 단위 검사까지 진행할 수 있지만 MR은 열지 않는다 |
| 001만 풀림 | T042를 실제 API로 연결해 검사한다. T039의 전송부는 만들되 버전은 `null`로 막힌 상태를 유지한다 |
| 004만 풀림 | T039를 완성한다. T042는 대역 뒤에서 멈춘다 |
| 둘 다 풀림 | 묶음을 끝내고 quickstart 7·7-1·7-2와 US1 종단 데모를 확인한 뒤 MR을 연다 |

## 7. 검증

```bash
cd frontend && npm run lint && npm run build && npm run test
```

MR을 올리기 전에 AI 코드리뷰를 하고, 찾은 문제를 고친 뒤 올린다. converge 결과가 생기면 tasks.md에 새 Phase로 남긴다.

## 8. 참고 위치

- `specs/003-strategy-settings/tasks.md`: T039·T042·T098 원문, "착수 전에 알아둘 것" ①②⑧, Phase 15의 T099 메모
- `specs/003-strategy-settings/research.md`: R7-1
- `specs/003-strategy-settings/data-model.md`: §7 확인된 빈 구멍
- `specs/001-etf-market-data/contracts/evaluation-window.md`, `sector-api.md`, `fixtures/evaluation-window-cases.json`
- `backend/src/main/java/com/ssafy/thorp/sector/application/service/EvaluationWindowService.java`
- `docs/specs/backtest-api/README.md`, `specs/004-backtest-execution/contracts/rest-api.md`
- `frontend/src/components/strategy/analysisSupport.ts`

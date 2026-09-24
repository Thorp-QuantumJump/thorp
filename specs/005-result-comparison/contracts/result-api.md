# 계약: 공개 결과 열람 API

**Spec**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Date**: 2026-09-16

004의 `/backtests/**`는 소유자 인증 경로로 그대로 둔다. 이 문서는 그 앞에 놓이는 공개 열람 경로만 정한다. 응답 봉투는 저장소의 기존 `ApiResponse` 규약을 따르고 JSON 필드는 camelCase다.

## 경로 대응

| 공개 경로 | 감싸는 004 경로 | 비고 |
|---|---|---|
| `GET /results/{publicToken}` | `GET /backtests/{backtestId}` | 상태·요약·조건 |
| `GET /results/{publicToken}/timeseries` | `GET /backtests/{backtestId}/timeseries` | 그래프 |
| `GET /results/{publicToken}/trades` | `GET /backtests/{backtestId}/trades` | 질의(`page`·`size`·`instrumentId`·`status`·`from`·`to`)를 그대로 전달하되 `size`는 공개 상한으로 자른다 |
| `GET /results/{publicToken}/positions` | `GET /backtests/{backtestId}/positions` | `date` 필수 |
| `GET /results/{publicToken}/weights` | `GET /backtests/{backtestId}/weights` | |
| `GET /results/{publicToken}/exceptions` | `GET /backtests/{backtestId}/exceptions` | 결과 옆 한 문장의 원천 |
| `GET /results/{publicToken}/index-comparison` | 001 기간 KOSPI 조회 포트 | 같은 기간·시작 금액으로 환산한 보조 계열. 포트가 없으면 비활성 |
| `POST /results/{publicToken}/retry` | `POST /backtests/{backtestId}/retry` | 실패한 비교 대상의 다시 시도 |

`publicToken`은 32자 소문자 16진 문자열이다. 형식이 맞지 않으면 조회하지 않고 `400 INVALID_INPUT`이다. 로그인하지 않아도 모든 경로를 쓸 수 있고, `POST`에는 저장소의 CSRF 규약이 그대로 적용된다.

## GET /results/{publicToken}

`200`. 004의 상세 응답에서 공개해도 되는 필드만 골라 담는다. `summary`·`progress`·`failure`의 모양은 004 상세(`docs/specs/backtest-api/README.md` §2.1)와 같다. 전체 예시는 `docs/specs/result-api/examples/result-detail-completed.json`이고 아래는 발췌다.

```json
{
  "publicToken": "9f2c1a7b4e0d83526af1c9d0b7e34512",
  "status": "COMPLETED",
  "resultSource": "NEW",
  "partial": false,
  "cancelRequested": false,
  "expiresAt": "2026-09-23T04:11:00Z",
  "progress": null,
  "queuedAt": "2026-09-16T04:11:00Z",
  "startedAt": "2026-09-16T04:11:03Z",
  "finishedAt": "2026-09-16T04:12:41Z",
  "queueWaitSeconds": null,
  "config": { "…": "요청 사본(items·strategyId·parameters·start·end·initialCash·warmupTradingDays 등) + currency·effectiveStart·effectiveEnd" },
  "versions": { "…": "mapping·rules·costProfile·data·engine (worker 제외)" },
  "summary": {
    "netTotalReturn": "0.166652",
    "maxDrawdown": "-0.041757",
    "totalTradingCost": "17078.49",
    "…": "004 상세 summary 와 같은 필드"
  },
  "summaryUnavailable": null,
  "failure": null,
  "validationTier": "UNVALIDATED",
  "strategyName": "내가 정한 비중",
  "weightsSource": "COMPUTED",
  "exceptionKinds": ["HALT_DEFERRED"]
}
```

- **`backtestId`를 담지 않는다.** 004의 내부 실행 식별자가 화면에 드러나면 SC-009를 어긴다. 재시도도 공개 토큰으로 받는다. 공개 취소 경로는 두지 않는다.
- `expiresAt`은 비로그인 결과에만 있다. 로그인 사용자의 결과에서는 `null`이다.
- `validationTier`는 PD-12 확정 전까지 항상 `UNVALIDATED`다. '검증 통과'가 아니라 '말할 수 없음'이라는 뜻이다. 모든 결과가 같은 값이라 결과 사이를 가르지 못하므로 **화면은 이 값을 읽지 않는다**(FR-012). 평가 분할이 등록되면 그때 화면 표시를 만든다.
- `progress`·`queueWaitSeconds`는 `status`가 `QUEUED`·`RUNNING`일 때만 담는다. 비교 대상 자리의 진행 상태 표시에 쓴다.
- `strategyName`은 서버가 003 등록표에서 `config.strategyId`로 찾아 채운다. 첫 화면이 등록표를 따로 부르면 SC-001의 요청 수를 넘기기 때문이다. 등록표에 없거나 읽지 못하면 `null`이고, 화면은 이름을 지어내지 않는다.
- `weightsSource`(`USER`|`COMPUTED`)는 FR-014를 첫 화면에서 보이려고 `/weights` 없이 담는다. `exceptionKinds`는 예외 기록 `kind`의 중복 없는 목록(처음 나온 순서)이며 결과 옆 한 문장을 `/exceptions` 없이 만들기 위해 담는다(R-12). 둘 다 `status`가 `COMPLETED`·`CANCELLED`이고 볼 결과가 있을 때만 값이 있다. 결과를 볼 수 없으면(계산 중, 실패, 시작 전 취소) `null`이고, `exceptionKinds`의 빈 목록은 "예외 없음"이다.

## 비교 주소

`config.executionType`에는 원 요청의 실행 종류(`SINGLE`·`SEARCH_BUNDLE` 등)를 담는다. 실행을 공유하더라도 요청 종류는 유지되며, `search` 질의가 없는 공개 주소에서도 자동 탐색 후보임을 구분한다. 탐색 식별자가 없으면 묶음 주소를 만들어 내지 않는다.

`/results/{baseToken}?compare={token1},{token2},{token3}&search={searchId}`. `compare`는 비교 대상 결과의 공개 토큰을 쉼표로 이은 목록(최대 셋, `S15P21E205-419`)이고, `search`는 비교 대상이 자동 탐색(007) 후보일 때 그 탐색의 식별자다. `search`가 있으면 화면은 '자동 탐색 후보 비교'로 표시하고 007 상세로 돌아가는 진입점을 둔다(FR-011, SC-011). 서버는 이 인자를 읽지 않는다 — 두 결과를 각각 `GET /results/{token}`으로 읽는다.

## POST /results/{publicToken}/retry

`202`. 실패·취소한 결과를 같은 조건으로 다시 접수한다. 004 재시도를 그대로 타므로 동일 버전 합류·재사용과 `429 EXECUTION_LIMIT`도 같다.

```json
{
  "publicToken": "b41e0c9a7d2f35618ce4a09b7f1d2c63",
  "status": "QUEUED",
  "resultSource": "NEW",
  "retryOfToken": "9f2c1a7b4e0d83526af1c9d0b7e34512"
}
```

- `publicToken`은 새 요청의 토큰이고 `retryOfToken`은 원 요청의 토큰이다. 004 응답의 `backtestId`·`retryOf`를 이 둘로 바꾼다.
- 새 요청의 요청자는 **지금 다시 시도한 사람**이다. 비로그인이면 요청자가 없고 만료 시각이 붙는다. 공개 주소는 남에게 건네질 수 있으므로 원 요청의 요청자를 물려받지 않는다.
- 실패·취소가 아니면 `409 NOT_RETRYABLE`이다.

## GET /results/{publicToken}/index-comparison

`200`. 공개 토큰의 형식·존재·만료 판정을 먼저 거친다. 결과가 계산에 쓴 자료 버전(`versions.data`)의
KOSPI 종가를 `effectiveStart`~`effectiveEnd`(양끝 포함) 범위에서 읽어, 시작 금액으로 첫 거래일에 KOSPI를
사서 끝까지 들고 있었을 때의 자산가치로 환산한다 — `value = initialCash × 종가 / 첫 종가`. 첫 종가는 그
범위에서 처음 관측된 값이고, 관측치가 없는 날은 채우지 않는다. 응답은 `{available: true, reason: null,
series: [{date, value}]}`이며 `value`는 다른 금액처럼 소수 둘째 자리 decimal 문자열이다. 화면은 다시
계산하지 않는다. 가격지수라 배당과 거래 비용은 들어 있지 않다.

기간·시작 금액·자료 버전 중 하나라도 없거나 그 범위에 KOSPI 관측치가 없으면 `{available: false,
reason: "INDEX_SERIES_UNAVAILABLE", series: []}`다. 다른 자료 버전이나 최신 관측치 한 점으로 값을
만들지 않는다. 어느 응답에도 `backtestId`는 없다.

## 오류

| code | HTTP | 뜻 | 화면 처리 |
|---|---|---|---|
| RESULT_EXPIRED | 410 | 비로그인 결과의 유효 시간이 지났다. 정리 작업이 요청 행을 지운 뒤에도 묘비 보관 기한 동안은 410이다 | 남아 있지 않다는 안내와 같은 조건으로 다시 분석할 진입점 (SC-008) |
| RESOURCE_NOT_FOUND | 404 | 그런 토큰이 없다(발급된 적 없거나 묘비 보관 기한이 지났다) | 잘못된 주소 안내. 만료와 다르게 보인다 |
| INVALID_INPUT | 400 | 토큰 형식 오류 | 위와 같다 |
| RESULT_NOT_READY | 409 | 아직 계산 중이라 하위 자원이 없다 | 진행 상태를 보인다 |
| BACKTEST_FAILED | 409 | 실패해 부분 결과도 없다 | 사유와 다시 시도 선택 |
| NO_PARTIAL_RESULT | 409 | 취소 요청에 완료한 거래일이 없다 | 숫자 자리를 채우지 않는다 |

004의 코드를 그대로 통과시키고 `RESULT_EXPIRED`만 새로 더한다. 그밖에 `/trades`의 뒤집힌 기간·잘못된 종목 형식, `/positions`의 `date` 누락은 `400 INVALID_INPUT`, 재시도의 `409 NOT_RETRYABLE`·`429 EXECUTION_LIMIT`도 004와 같다.

**004에서 온 오류의 문구는 코드의 기본 문구다.** 입력 검증의 400(거래 질의·`date` 누락)은 원인 문구를 그대로 싣는다. 004 예외의 문구에는 `backtestId`가 들어 있어 공개 경로는 문구를 바꿔 내보낸다. 코드·HTTP 상태·`details`(예: `retryAfterSeconds`)는 그대로다. 만료 응답 예시는 `docs/specs/result-api/examples/result-expired.json`이다.

**정리 뒤에도 410을 유지하는 방법**: 정리 작업은 요청 행과 토큰을 지우면서(FR-021) 토큰의 SHA-256 해시와 만료 시각만 묘비(`expired_public_token`)로 남긴다. 조회는 토큰이 없을 때 묘비를 찾아 있으면 410이다. 묘비는 보관 기한(`thorp.result.tombstone-retention`, PD-23 확정 전 임시값)이 지나면 지운다. 해시로는 결과를 열 수 없고 128비트 난수라 원문을 되찾을 수도 없다.

**410과 404를 나누는 근거**: 만료(410)와 없음(404)을 다르게 답하면 "이 토큰이 한 번 존재했다"는 사실이 밖으로 드러난다. 그러나 토큰이 안전한 난수 최소 128비트라 이웃 값을 맞출 가능성이 사실상 0이어서 존재 여부를 훑어 알아내는 것 자체가 불가능하고, 알아낸다 해도 내용은 얻을 수 없다. 반면 사용자에게는 "사라졌다"와 "잘못된 주소"가 다른 안내여야 한다(SC-008, Edge Cases). 그래서 구분을 유지한다.

## 공개 응답에서 버리는 필드

공개 경로는 004 응답을 통과시키되 다음 필드는 버린다. 이 목록에 없는 필드는 통과시키고, 004가 필드를 더할 때 버릴 것만 여기에 추가한다.

| 버리는 필드 | 이유 |
|---|---|
| 요청자 식별자(`requesterId` 등) | 개인 식별 정보 (FR-019) |
| `backtestId` | 004의 순번 식별자. 노출되면 SC-009 위반 |
| run 식별자(`runId`, `RUN-…`) | 내부 계산 단위. 여러 요청이 공유한다 |
| `worker`(워커 자기 보고) | 운영 정보. 사용자 화면에 노출하지 않는다 |
| `retryOf`의 내부 식별자 | 원 요청의 `backtestId`. 재시도 응답은 원 요청의 공개 토큰(`retryOfToken`)으로 바꿔 담는다 |
| `versions.worker` | 결과를 낸 워커의 자기 보고. 위 `worker`와 같은 이유다 |
| `versions.dependencies` | 결과를 계산한 워커 환경의 라이브러리 버전(004 T028). 위 `worker`와 같은 이유다 |
| 오류 문구 속 식별자 | 004 예외 문구에 `backtestId`가 들어 있다. 공개 경로는 코드의 기본 문구로 바꾼다 |

하위 자원(`/timeseries`·`/trades`·`/positions`·`/weights`·`/exceptions`)은 004 응답에서 `backtestId`만 버린 모양이다. `/index-comparison`도 내부 식별자를 담지 않는다.

## 비교 요청

실제 비용 기준은 요청 사본 `config`가 아니라 `versions.costProfile.version`에서 가져온다. 새 전략은 003 등록표의 기본 설정과 004 규칙 등록표의 해당 전략 버전을 쓴다. 원본 두 건을 다시 계산할 때는 각 원본의 설정·비중·평가 분할·시나리오를 보존한다.

준비 기간 부족의 구조화된 필요 값은 `error.details.requiredWarmupTradingDays`다. 요청값이 요구값보다 작은 경우 `requestedWarmupTradingDays`도 담고, 준비 기간을 채운 첫날이 요청 종료일 뒤인 경우 `requiredStartDate`를 담는다. 필요 값이나 공통 기간을 확인할 수 없으면 확인창에 사유만 보이고 재계산을 막는다.

비교 대상 결과가 없을 때 화면이 새 실행을 요청하는 경로는 004의 `POST /backtests`를 그대로 쓴다. 005가 새 접수 경로를 만들지 않는다. 요청 본문은 기준 결과의 `config`에서 전략만 바꾸고 나머지(섹터 후보·기간·금액·비용·`warmupTradingDays`)를 그대로 옮긴다.

- 접수 응답의 `backtestId`는 화면이 보관하지 않는다. 접수 응답에 `publicToken`을 함께 실어 화면이 그것만 들고 다닌다. **004의 접수 응답에 `publicToken`을 더하는 변경이 필요하다.**
- `429 EXECUTION_LIMIT`을 받으면 한도에 걸렸다는 사실과 `retryAfterSeconds`를 보인다. 조용히 재시도하지 않는다(원칙 IX, plan "원칙 IX에 관한 주의").
- 비교 대상을 고르기 전에 화면은 003 등록표의 전략별 준비 기간을 읽어 기준 결과의 `warmupTradingDays`와 비교하고, 더 길면 목록에서 "비교 기간이 짧아진다"를 표시한다([research.md](../research.md) R-13). 고르면 FR-005의 기간 불일치 확인을 거친 뒤 두 건을 공통 준비 기간으로 요청한다.
- `422 INSUFFICIENT_HISTORY`는 화면의 사전 판단이 어긋났을 때의 안전장치다. 본문의 필요 값을 공통 준비 기간으로 삼아 같은 기간 불일치 확인으로 이어 붙인다. 조용히 값을 올려 재요청하지 않는다.

## 004에 요청하는 변경

이 계약이 성립하려면 004 쪽에 세 가지가 필요하다. 모두 004 소유이므로 이 spec에서 고치지 않고 tasks에서 협의 항목으로 올린다.

| 항목 | 이유 |
|---|---|
| `backtest_request.requester_id` nullable 완화 | 비로그인 실행·열람 ([research.md](../research.md) R-02). 004 research R-12가 이 경우를 예상해 두었다 |
| 접수 응답에 `publicToken` 추가 | 화면이 내부 식별자를 들고 다니지 않게 한다 (SC-009) |
| `summary.maxDrawdownWindow{peakDate, troughDate, recoveredDate}` 추가 | 최대 하락 구간 음영 (FR-001). 구간을 화면이 계산하면 "005는 계산하지 않는다"가 깨진다 |
| `analytics`의 `correlationMatrix`·`riskContribution`·`lossContribution` 노출 | FR-007이 상세 패널에 요구하는 항목인데 계산·저장만 되고 API로 나오지 않는다. `BacktestDetailResponse.Analytics`가 `recoveryDays`·`turnover` 둘만 담는다 |
| 참조하는 요청이 없는 `backtest_run`·결과의 정리 | FR-021이 만료된 비로그인 요청을 지울 때, 그 요청이 가리키던 계산은 다른 요청이 참조하지 않을 때만 지워야 한다(004 FR-003 공유). 계산 기록의 참조 판정과 삭제는 004 소유다. 005는 요청 행과 토큰만 지운다 |

세 번째가 들어오기 전까지는 `/timeseries`의 `drawdown` 계열에서 구간을 찾아 쓰되 `summary.maxDrawdown`과 어긋나면 음영을 생략한다. 네 번째가 들어오기 전까지는 해당 세 항목을 제공되지 않음으로 두고 값을 지어내지 않는다(원칙 VI).

## 예시 파일

`docs/specs/result-api/examples/`에 다음을 둔다. 003·004가 쓰는 방식대로 계약 예시를 픽스처로 삼아 실제 응답과 대조한다(`ResultApiIT`). 모두 `ApiResponse` 봉투 전체이고, 어디에도 `BT-` 문자열이 없다.

- `result-detail-completed.json`
- `result-detail-running.json`
- `result-detail-cancelled-no-summary.json`
- `result-expired.json`
- `result-timeseries.json` — 129점 전체. 최대 하락 구간(고점 뒤 2025-03-19 저점, `-0.041757`)이 있어 음영 테스트에 쓴다

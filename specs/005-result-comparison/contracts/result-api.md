# 계약: 공개 결과 열람 API

**Spec**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Date**: 2026-09-16

004의 `/backtests/**`는 소유자 인증 경로로 그대로 둔다. 이 문서는 그 앞에 놓이는 공개 열람 경로만 정한다. 응답 봉투는 저장소의 기존 `ApiResponse` 규약을 따르고 JSON 필드는 camelCase다.

## 경로 대응

| 공개 경로 | 감싸는 004 경로 | 비고 |
|---|---|---|
| `GET /results/{publicToken}` | `GET /backtests/{backtestId}` | 상태·요약·조건 |
| `GET /results/{publicToken}/timeseries` | `GET /backtests/{backtestId}/timeseries` | 그래프 |
| `GET /results/{publicToken}/trades` | `GET /backtests/{backtestId}/trades` | 페이지 질의 그대로 전달 |
| `GET /results/{publicToken}/positions` | `GET /backtests/{backtestId}/positions` | `date` 필수 |
| `GET /results/{publicToken}/weights` | `GET /backtests/{backtestId}/weights` | |
| `GET /results/{publicToken}/exceptions` | `GET /backtests/{backtestId}/exceptions` | 결과 옆 한 문장의 원천 |
| `POST /results/{publicToken}/retry` | `POST /backtests/{backtestId}/retry` | 실패한 비교 대상의 다시 시도 |

`publicToken`은 32자 16진 문자열이다. 형식이 맞지 않으면 조회하지 않고 `400 INVALID_INPUT`이다.

## GET /results/{publicToken}

`200`. 004의 상세 응답에서 공개해도 되는 필드만 골라 담는다.

```json
{
  "publicToken": "9f2c1a7b4e0d83526af1c9d0b7e34512",
  "status": "COMPLETED",
  "partial": false,
  "expiresAt": "2026-09-23T04:11:00Z",
  "config": { "…": "요청 원문 + effectiveStart/End" },
  "versions": { "…": "mapping·rules·costProfile·data·engine" },
  "summary": {
    "netTotalReturn": "0.2143",
    "maxDrawdown": "-0.1872",
    "totalTradingCost": "184300",
    "liquidation": { "markToMarket": "12143000", "liquidated": "12128700" },
    "analytics": { "recoveryDays": 143, "turnover": "1.84" }
  },
  "summaryUnavailable": null,
  "failure": null,
  "validationTier": "UNVALIDATED"
}
```

- **`backtestId`를 담지 않는다.** 004의 내부 실행 식별자가 화면에 드러나면 SC-009를 어긴다. 취소·재시도도 공개 토큰으로 받는다.
- `expiresAt`은 비로그인 결과에만 있다. 로그인 사용자의 결과에서는 `null`이다.
- `validationTier`는 PD-12 확정 전까지 항상 `UNVALIDATED`다. '검증 통과'가 아니라 '말할 수 없음'이라는 뜻이며 화면도 그렇게 쓴다.
- `progress`·`queueWaitSeconds`는 `status`가 `QUEUED`·`RUNNING`일 때만 담는다. 비교 대상 자리의 진행 상태 표시에 쓴다.

## 오류

| code | HTTP | 뜻 | 화면 처리 |
|---|---|---|---|
| RESULT_EXPIRED | 410 | 비로그인 결과의 유효 시간이 지나 삭제되었다 | 남아 있지 않다는 안내와 같은 조건으로 다시 분석할 진입점 (SC-008) |
| RESOURCE_NOT_FOUND | 404 | 그런 토큰이 없다 | 잘못된 주소 안내. 만료와 다르게 보인다 |
| INVALID_INPUT | 400 | 토큰 형식 오류 | 위와 같다 |
| RESULT_NOT_READY | 409 | 아직 계산 중이라 하위 자원이 없다 | 진행 상태를 보인다 |
| BACKTEST_FAILED | 409 | 실패해 부분 결과도 없다 | 사유와 다시 시도 선택 |
| NO_PARTIAL_RESULT | 409 | 취소 요청에 완료한 거래일이 없다 | 숫자 자리를 채우지 않는다 |

004의 코드를 그대로 통과시키고 `RESULT_EXPIRED`만 새로 더한다.

**410과 404를 나누는 근거**: 만료(410)와 없음(404)을 다르게 답하면 "이 토큰이 한 번 존재했다"는 사실이 밖으로 드러난다. 그러나 토큰이 안전한 난수 최소 128비트라 이웃 값을 맞출 가능성이 사실상 0이어서 존재 여부를 훑어 알아내는 것 자체가 불가능하고, 알아낸다 해도 내용은 얻을 수 없다. 반면 사용자에게는 "사라졌다"와 "잘못된 주소"가 다른 안내여야 한다(SC-008, Edge Cases). 그래서 구분을 유지한다.

## 공개 응답에서 버리는 필드

공개 경로는 004 응답을 통과시키되 다음 필드는 버린다. 이 목록에 없는 필드는 통과시키고, 004가 필드를 더할 때 버릴 것만 여기에 추가한다.

| 버리는 필드 | 이유 |
|---|---|
| 요청자 식별자(`requesterId` 등) | 개인 식별 정보 (FR-019) |
| `backtestId` | 004의 순번 식별자. 노출되면 SC-009 위반 |
| run 식별자(`runId`, `RUN-…`) | 내부 계산 단위. 여러 요청이 공유한다 |
| `worker`(워커 자기 보고) | 운영 정보. 사용자 화면에 노출하지 않는다 |
| `retryOf`의 내부 식별자 | 원 요청의 `backtestId`. 필요하면 원 요청의 공개 토큰으로 바꿔 담는다 |

## 비교 요청

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

`docs/specs/result-api/examples/`에 다음을 둔다. 003·004가 쓰는 방식대로 계약 예시를 픽스처로 삼아 실제 응답과 대조한다.

- `result-detail-completed.json`
- `result-detail-running.json`
- `result-detail-cancelled-no-summary.json`
- `result-expired.json`
- `result-timeseries.json`

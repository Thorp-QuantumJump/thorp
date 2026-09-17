# Contract: 공통 평가 가능 기간 산식

**근거**: [spec.md](../spec.md) FR-007·FR-008, [research.md](../research.md) 결정 3

Worker와 Backend가 각자 구현하고 같은 픽스처로 검증하는 산식이다. 001은 후보 집합별 결과를 미리 만들지 않고 재료와 산식만 제공한다.

## 입력

| 이름 | 타입 | 출처 |
|---|---|---|
| `candidates` | 대표 ETF 목록 | 요청한 후보 집합 |
| `candidates[].instrumentId` | `{6글자}.XKRX` (영숫자 대문자) | `representative_etf` |
| `candidates[].listedOn` | date | `representative_etf` |
| `candidates[].delistedOn` | date \| null | `representative_etf` |
| `candidates[].dataGaps` | 구간 목록 | `data_gap` 표 |
| `strategies[].warmupTradingDays` | 정수 | 전략 등록표(003 소유) |
| `dataDeadline` | date | `dataset_version.data_deadline` |
| `tradingCalendar` | 거래일 목록 | 확보한 지수 자료의 날짜 집합 |

`warmupTradingDays`는 전략의 관측 기간에 최근 제외 구간을 더한 값이다(FR-007). 001은 이 값을 정하지 않고 받는다.

## 산식

```
warmup = max(strategies[].warmupTradingDays)

각 후보 c에 대해
  firstEvaluable(c) = tradingCalendar에서 c.listedOn 이후 warmup 번째 거래일
  lastEvaluable(c)  = c.delistedOn ?? dataDeadline

commonStart = max over c of firstEvaluable(c)
commonEnd   = min(dataDeadline, min over c of lastEvaluable(c))

excludedRanges = 모든 후보의 dataGaps를 합집합한 뒤 [commonStart, commonEnd]로 자른 것
```

**검증용 잠금 구간은 이 산식에 넣지 않는다** (FR-008). 반환값은 자료로 평가할 수 있는 전체 범위이며, 탐색 구간과 잠금 구간으로 나누는 일은 007이 맡는다.

## 출력

```json
{
  "commonStart": "2018-07-05",
  "commonEnd": "2026-09-07",
  "warmupTradingDays": 147,
  "bindingCandidate": "091160.XKRX",
  "excludedRanges": [
    { "start": "2021-03-02", "end": "2021-03-08", "reason": "SUSPENDED_TOO_LONG" }
  ],
  "evaluable": true
}
```

| 필드 | 뜻 |
|---|---|
| `bindingCandidate` | `commonStart`를 결정한 후보. 003이 "이 섹터를 빼면 기간이 늘어납니다"를 안내하는 근거 |
| `excludedRanges` | 구간 안의 자료 부족 구간. 사유는 `data_gaps.reason` 값 |
| `evaluable` | `commonStart > commonEnd`이면 false |

## 경계 동작

| 상황 | 결과 |
|---|---|
| `commonStart > commonEnd` | `evaluable: false`. 오류가 아니라 정상 응답이다. 화면은 그 전략을 분석할 수 없다는 한 문장을 보인다(원칙 VI, `003:95`) |
| 후보가 하나 | 그 후보의 값이 그대로 나온다 |
| 후보 목록이 빈 배열 | 오류. 후보 없이 기간을 산출하지 않는다 |
| `warmupTradingDays`가 0 | 허용한다. `firstEvaluable = listedOn` |
| 상장폐지된 후보가 섞임 | `commonEnd`가 그 폐지일로 당겨진다 |
| 자료 부족 구간이 `commonStart`를 포함 | 구간을 잘라내지 않고 `excludedRanges`로 알린다. 시작일을 조용히 밀지 않는다(원칙 VI) |

## HTTP 표면

이 산식을 밖으로 내보내는 것은 `GET /sectors/evaluation-window`다([sector-api.md](./sector-api.md)). 그 엔드포인트는 여기 출력에 **화면이 다른 워밍업으로 스스로 다시 판정할 재료**를 더해 내려 준다. **이 문서의 산식과 출력 여섯 칸은 그 때문에 바뀌지 않았다** — 그 여섯은 아래 픽스처가 고정하고 워커와 백엔드가 함께 읽으므로, 조회 경로의 사정으로 고치지 않는다.

다만 그 엔드포인트는 경계 동작 하나를 다르게 다룬다. **달력이 워밍업에 모자라 `CALENDAR_TOO_SHORT`가 나는 경우를 오류가 아니라 `evaluable: false`로 옮긴다.** 화면이 여러 워밍업을 훑는 용도로 부르는 조회라, 훑는 도중 값 하나가 달력을 넘었다고 조회 전체가 실패하면 나머지 판정까지 잃기 때문이다. 산식 자체는 그대로 오류를 낸다.

## 픽스처

`fixtures/evaluation-window-cases.json`에 위 경계 동작마다 입력과 기대 출력을 둔다. Worker의 pytest와 Backend의 JUnit이 같은 파일을 읽는다.

SC-007이 이 픽스처를 검증 근거로 삼는다. "수기 계산과 일치한다"의 수기 계산이 픽스처의 기대 출력이다.

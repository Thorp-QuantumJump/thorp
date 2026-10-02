# Contract: 섹터 목록·대표 상품 정보 조회

**근거**: [spec.md](../spec.md) FR-001·FR-002·FR-011·FR-015, User Story 1·3

FRONT가 섹터 선택 화면(002)과 결과의 근거 패널(005)에서 쓰는 조회 계약이다. 가격은 대표 ETF의 가격 흐름(`price-series`)과 최근 가격(`latest-prices`) 두 조회로만 내보낸다.

## 공통 규칙

`backend/CLAUDE.md`를 따른다.

- JSON 필드는 camelCase
- 응답 포맷은 `common/web`의 `ApiResponse`
- 금액과 비율은 지수 표기 없는 문자열로 내보낸다. `avgDailyValue3m`과 구성종목 `weight`에 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`를 필드마다 붙인다. 웹 ObjectMapper에 전역 등록하지 않는다 — 기존 `MarketResponse.value`의 계약이 깨진다
- **저장 타입과 응답 타입이 다르다.** `avg_daily_value_3m`은 저장소에서 `text` 열이고, 백엔드는 읽은 문자열을 `BigDecimal`로 옮겨 도메인에 담은 뒤 응답에서 다시 지수 표기 없는 문자열로 쓴다. 문자열 → `BigDecimal` → 문자열을 거치면서 표기가 바뀌지 않는 근거는 저장 CHECK이 plain 문자열만 받는다는 데 있다. 네 단계 전체는 [data-model.md 「instrument_profile」](../data-model.md)의 변환 경로 표가 기준이며, 이 계약은 그중 마지막 단계만 규정한다
- 대응표는 읽기 전용이다. 수정·생성 엔드포인트를 두지 않는다(research.md 결정 7)

## GET /sectors — 섹터 목록

User Story 1이 요구하는 최소 정보다. 사용자는 이 목록만 보고 분석을 시작할 수 있어야 한다.

```json
{
  "mapVersion": "SEM-2026-09-10-001",
  "isSample": true,
  "sectors": [
    {
      "code": "SEMICONDUCTOR",
      "name": "반도체",
      "classification": "INDUSTRY",
      "representativeEtf": {
        "instrumentId": "091160.XKRX",
        "name": "KODEX 반도체",
        "listedOn": "2006-06-27",
        "benchmarkIndex": "KRX 반도체"
      }
    }
  ]
}
```

| 필드 | 근거 |
|---|---|
| `mapVersion` | FR-003. 이 응답이 어느 대응표 버전인지 |
| `isSample` | FR-002. **대응표 버전**(`sector_etf_map_version.is_sample`)의 값이다. true면 섹터에 붙은 대표 ETF가 아직 예시이므로 화면이 그 사실을 표시한다 |
| `classification` | FR-001. `INDUSTRY` \| `THEME` |

**불변식**: `sectors` 배열의 모든 항목에 `representativeEtf`가 있다. 비어 있는 항목을 내보내지 않는다(SC-001). 대응표가 준비되지 않았으면 빈 배열이 아니라 오류를 반환한다 — 원칙 VI가 "없는 결과를 만들어내지 않는다"를 요구하므로, 준비 안 됨과 섹터 0개를 구분한다.

## GET /sectors/{code}/instrument-profile — 대표 상품 정보

User Story 3의 펼쳐 보는 층이다.

```json
{
  "instrumentId": "091160.XKRX",
  "name": "KODEX 반도체",
  "benchmarkIndex": "KRX 반도체",
  "indexMethodologySummary": "KRX 반도체 지수를 추종하며 ...",
  "listedOn": "2006-06-27",
  "manager": "삼성자산운용",
  "holdingsOverlap": {
    "asOfCurrentComposition": true,
    "topHoldings": [
      { "name": "삼성전자", "weight": "0.2431" },
      { "name": "SK하이닉스", "weight": "0.2107" }
    ]
  },
  "avgDailyValue3m": "1240000000",
  "sourceAttribution": "한국거래소 통계정보",
  "survivorshipNote": "현재 상장되어 있는 상품만 다루므로 중간에 상장폐지된 상품은 포함되지 않습니다.",
  "datasetId": "KRX_DAILY_20260907",
  "dataDeadline": "2026-09-07T09:00:00Z",
  "isSample": true
}
```

| 필드 | 근거 |
|---|---|
| `holdingsOverlap.asOfCurrentComposition` | FR-015. true면 화면은 당시 구성을 재현했다고 표현하지 않는다 |
| `avgDailyValue3m` | FR-015, clarify 4. 최근 3개월 일평균 거래대금(원) |
| `sourceAttribution` | FR-011, SC-005. 한국거래소 자료를 쓰는 화면에 표시한다 |
| `survivorshipNote` | FR-015. 한 문장 |
| `datasetId`·`dataDeadline` | FR-013·FR-014. 화면이 어느 시점 자료인지 판단하는 근거 |
| `isSample` | FR-002, research.md 결정 5. **자료 버전**(`dataset_version.is_sample`)의 값이다. true면 이 상품 정보를 만든 자료가 예시다 |

**두 엔드포인트의 `isSample`은 서로 다른 것을 가리킨다.** `GET /sectors`의 것은 **대응표 버전**이 예시인지(섹터에 붙인 대표 ETF가 아직 잠정 값인지)이고, 여기 것은 그 상품 정보를 만든 **자료 버전**이 예시인지다. 둘은 각자 자기 근거를 쓰므로 값이 같을 수도 다를 수도 있다 — 이를테면 대표 ETF는 확정됐지만(대응표 `false`) 아직 고정 테스트 자료로만 채워 둔 상태(자료 버전 `true`)가 있다. 화면은 어느 쪽 안내를 낼지 정할 때 이 구분을 따라야 하고, 한쪽 값으로 다른 쪽을 짐작하지 않는다.

**추적 차이 필드를 두지 않는다.** 이번 범위에서 제외했다(Out of Scope). 나중에 정의가 확정되면 필드를 추가하는 방향으로 확장한다.

## GET /sectors/{code}/price-series — 대표 ETF의 가격 흐름

투자 대상을 고르는 화면이 카드에 그리는 선이다(`S15P21E205-423`).

```json
{
  "instrumentId": "117700.XKRX",
  "datasetId": "KRX_DAILY_20260922",
  "dataDeadline": "2026-09-22T06:30:00Z",
  "isSample": true,
  "points": [
    { "date": "2026-09-16", "adjustedClose": "10000.00000000", "tradingSuspended": false, "priceCarriedForward": false },
    { "date": "2026-09-17", "adjustedClose": "10250.50000000", "tradingSuspended": true, "priceCarriedForward": true }
  ]
}
```

| 필드 | 근거 |
|---|---|
| `points[].adjustedClose` | 기업행위가 반영된 수정종가다. **정규화하거나 수익률로 바꾸지 않는다** — 무엇을 기준으로 볼지는 그리는 쪽이 정한다 |
| `points[].tradingSuspended` | 그 날 거래가 정지되어 있었는가. 숨기면 "그 값에 거래할 수 있었다"로 읽힌다(원칙 V) |
| `points[].priceCarriedForward` | 그 날 가격이 직전 거래일에서 이어 붙인 값인가. 같은 까닭으로 함께 싣는다 |
| `datasetId`·`dataDeadline`·`isSample` | 대표 상품 정보와 같은 뜻이며 값을 고르는 규칙도 같다 |

**점을 솎아 내지 않는다.** 화면이 작은 그림을 그린다고 서버가 미리 줄이면 어느 날을 버렸는지 보는 쪽이 알 수 없고, 다른 굵기로 보려는 화면이 원본을 되찾을 수 없다. 상장 이후 모든 거래일을 날짜 오름차순으로 준다.

**행이 하나도 없으면 빈 배열이 아니라 409다.** 점 없는 그래프는 "값이 없다"가 아니라 "움직임이 없었다"로 그려진다(원칙 VI).

**Backend가 시계열을 읽는 유일한 자리다.** plan.md는 "시계열을 읽는 것은 워커이고 Backend는 메타데이터만 본다"고 정했는데, 그 전제는 시계열을 쓰는 곳이 계산뿐이었다는 것이다. 고르기 전에 흐름을 보여 달라는 요구는 조회이고 워커는 메시지를 받아 도는 쪽이라 화면의 물음에 답할 수 없다. 계산은 여전히 워커가 한다.

## GET /sectors/latest-prices — 섹터 대표 ETF의 최근 가격

투자 대상 고르기의 '지금 국내 시장' 띠가 쓴다([002](../../002-sector-weight-selection/spec.md) FR-018). 섹터마다 가격 흐름 전체를 받아 마지막 두 점만 쓰던 자리를 요청 한 번으로 줄인다.

```json
{
  "mapVersion": "SEM-2026-09-10-001",
  "datasetId": "KRX_DAILY_20260922",
  "dataDeadline": "2026-09-22T06:30:00Z",
  "isSample": true,
  "prices": [
    {
      "sectorCode": "CONSTRUCTION",
      "instrumentId": "117700.XKRX",
      "name": "KODEX 건설",
      "date": "2026-09-18",
      "adjustedClose": "10500.00000000",
      "tradingSuspended": false,
      "priceCarriedForward": false,
      "previousDate": "2026-09-17",
      "previousAdjustedClose": "10250.50000000"
    }
  ],
  "missingSectorCodes": ["BANK"]
}
```

| 필드 | 근거 |
|---|---|
| `prices[]` | 열린 대응표의 섹터 순서를 따른다. 가격이 있는 섹터만 담는다 |
| `adjustedClose`·`date` | 그 자료 버전에서 가장 늦은 거래일의 수정종가다. 같은 섹터의 `price-series` 마지막 점과 늘 같다 |
| `previousDate`·`previousAdjustedClose` | 그 직전 거래일 값이다. 거래일이 하루뿐이면 **둘 다 `null`이다** — 0이나 같은 값으로 채우면 "변화 없음"으로 읽힌다 |
| `tradingSuspended`·`priceCarriedForward` | 마지막 날의 표시다. 그 날 값이 직전 값을 옮겨 적은 것일 수 있다(원칙 V) |
| `missingSectorCodes` | 그 자료 버전에 가격이 한 행도 없는 섹터다. 조용히 빼지 않는다 |
| `datasetId`·`dataDeadline`·`isSample` | `price-series`와 같은 뜻이며 값을 고르는 규칙도 같다 |

**등락률을 계산하지 않는다.** 직전 값을 그대로 싣고 무엇과 견줄지는 보는 쪽이 정한다. `price-series`가 수익률로 바꾸지 않는 것과 같은 판단이다.

**일부 섹터에 가격이 없으면 200이고, 어느 섹터에도 없으면 409 `PRICE_SERIES_NOT_READY`다.** 띠는 받은 것만 보이면 되므로 한 섹터의 적재 지연이 나머지를 막지 않는다. 고를 자료 버전이 없을 때도 같은 409다.

## GET /sectors/evaluation-window — 공통 평가 가능 기간

003의 "이 조합은 언제부터 비교 가능합니다" 안내가 쓴다. 산식과 경계 동작은 [evaluation-window.md](./evaluation-window.md)가 기준이며, 이 엔드포인트는 그 산식의 HTTP 표면이다.

**요청**: `?sectors=SEMICONDUCTOR,BATTERY&strategy=SECTOR_MOMENTUM&maxWarmupTradingDays=200`

| 인자 | 필수 | 뜻 |
|---|---|---|
| `sectors` | 예 | 후보 섹터 코드. 쉼표로 잇는다. 중복은 앞선 것만 남기고 **순서를 보존한다**(산식의 `bindingCandidate` 동점 규칙이 입력 순서를 쓴다) |
| `strategy` | 아니오 | 워밍업을 정할 전략. 001은 이 값을 정하지 않고 전략 등록표(003 소유)에서 **기본 설정** 워밍업을 읽는다 |
| `maxWarmupTradingDays` | 아니오 | 화면이 훑을 워밍업의 상한. 주면 아래 네 필드를 함께 내려 준다 |

**둘 다 생략할 수는 없다.** `strategy`도 `maxWarmupTradingDays`도 없으면 워밍업을 적용할 수도, 화면이 스스로 계산할 재료를 내릴 수도 없어 400이다. `strategy`만 생략하면 워밍업 0으로 판정하며, 이것이 **전략 카드 여러 장을 요청 한 번으로 판정하는 경로**다.

`maxWarmupTradingDays`를 두는 까닭은 **001이 003의 파라미터 조합을 몰라도 되게 하기 위함이다.** 화면이 자기가 필요한 만큼(등록표 축 상한으로 계산한 값)을 말하고 001은 그만큼만 내려 준다. 생략했을 때 등록표의 최대값으로 대신 채우지 않는다 — 그러면 001이 003 등록표의 축 구성을 읽어야 한다. 값의 허용 범위는 0 이상 6000 이하이며 넘으면 400이다.

### 응답

```json
{
  "commonStart": "2019-04-18",
  "commonEnd": "2026-09-07",
  "warmupTradingDays": 147,
  "bindingCandidate": "305720.XKRX",
  "bindingSector": "BATTERY",
  "excludedRanges": [],
  "evaluable": true,
  "datasetId": "KRX_DAILY_20260915",

  "warmupBaseDate": "2018-09-12",
  "tradingDaysFromBase": ["2018-09-12", "2018-09-13", "2018-09-14", "..."],
  "maxWarmupTradingDays": 200,
  "excludedRangesFromBase": []
}
```

앞의 여섯 칸은 `evaluation-window.md`의 출력이고, 나머지는 이 엔드포인트가 더한 것이다.

| 필드 | 뜻 |
|---|---|
| `bindingSector` | `bindingCandidate`가 대표하는 섹터 코드. 화면이 종목 코드를 섹터 이름으로 되짚지 않게 하는 자리다 |
| `datasetId` | 이 응답을 만든 자료 버전(FR-013) |
| `warmupBaseDate` | 워밍업이 0일 때의 `commonStart`. 기준 후보의 상장일을 거래일 달력에 맞춘 날이다 |
| `tradingDaysFromBase` | `warmupBaseDate`부터 `commonEnd`까지의 거래일 오름차순 목록. 길이는 `maxWarmupTradingDays + 1`을 넘지 않는다 |
| `maxWarmupTradingDays` | 화면이 계산할 수 있는 워밍업의 상한. `tradingDaysFromBase.length - 1`과 같다 |
| `excludedRangesFromBase` | `[warmupBaseDate, commonEnd]` 안의 자료 부족 구간 |

**왜 재료를 함께 내리는가.** 003 화면은 사용자가 관측 기간 같은 값을 바꿀 때마다 워밍업이 달라지고 그 자리에서 "기간이 줄어듭니다 / 분석할 수 없습니다"를 다시 판정한다. 그런데 그 화면의 완료 기준이 "값을 만지는 동안 서버로 요청이 나가지 않는다"여서 값이 바뀔 때마다 이 엔드포인트를 다시 부르는 방식은 쓸 수 없다. 워밍업을 뺀 시작일은 거래일 달력이 있어야 구해지므로, 한 번의 응답에 달력 조각을 실어 보내고 **화면이 `commonStart(N) = tradingDaysFromBase[N]`으로 계산한다**(0-기준, 003 T098의 2026-09-17 합의).

`excludedRangesFromBase`를 따로 두는 까닭은 `excludedRanges`가 워밍업을 반영한 `[commonStart, commonEnd]`로 잘려 있어 **그 앞의 자료 부족 구간을 알 수 없기** 때문이다. 화면은 더 짧은 워밍업으로 다시 계산할 때 이 목록을 자기 시작일에 맞춰 다시 자른다. 기존 필드의 뜻은 바꾸지 않았다 — 그 뜻은 워커와 공유하는 픽스처 `fixtures/evaluation-window-cases.json`이 고정하고 있다.

### 경계 동작

`evaluation-window.md`의 경계 동작에 더해 이 엔드포인트가 정하는 것들이다.

| 상황 | 결과 |
|---|---|
| `maxWarmupTradingDays`가 남은 거래일보다 큼 | **오류가 아니다.** 있는 만큼만 내려 주고 `maxWarmupTradingDays`를 실제 길이 − 1로 줄인다 |
| 워밍업을 채울 거래일이 모자람 | **오류가 아니다.** `evaluable: false`이고 `commonStart`는 `null`이다(키는 남는다). 재판정 재료 네 칸은 그대로 내려 준다 |
| `warmupBaseDate`가 이미 `commonEnd`를 넘음 | `tradingDaysFromBase`가 빈 배열이고 `maxWarmupTradingDays`는 없다. 어떤 워밍업으로도 평가할 수 없다는 뜻이다 |
| `maxWarmupTradingDays`를 주지 않음 | 재판정 재료 네 칸 가운데 `warmupBaseDate`만 내려 주고 나머지 셋은 **키 자체가 없다** |

산식이 `CALENDAR_TOO_SHORT`로 실패하는 경우를 여기서 `evaluable: false`로 옮기는 이유는, 화면이 여러 워밍업을 훑는 용도로 부르는 조회라 값 하나가 달력을 넘었다고 조회 전체가 실패하면 나머지 판정까지 잃기 때문이다. **없는 날짜를 지어내는 것과는 다르다** — 날짜를 만들지 않고 "그 워밍업으로는 평가할 수 없다"고 말한다.

### 자료 버전을 고르는 규칙

지금 열린 대응표를 대상으로 수집됐고, 완전하며(`complete = true`), 그중 `data_deadline`이 가장 늦은 것이다. 대표 상품 정보와 같은 세 조건이라 같은 화면의 두 응답이 같은 판을 가리킨다. 접수 경로(`MarketMetadataPort`의 DB 구현)는 대응표를 조건에 넣지 않는데, 파이프라인이 자료를 현재 열린 대응표에 붙여 발행하는 한 두 규칙은 같은 행을 고른다. 갈리는 때는 대응표만 새로 열고 자료는 아직 옛 대응표를 가리키는 과도기이며, 그때 이 조회는 `EVALUATION_WINDOW_NOT_READY`가 되고 접수는 옛 자료로 계속 받는다. **막는 쪽으로 갈리는 것이 안전하다** — 반대로 갈리면 화면이 안내한 첫날이 접수에서 거절된다.

거래일 달력은 `daily_index`의 `KOSPI` 행이다. `data_pipeline.index.CALENDAR_INDEX_CODE`·접수 경로와 같은 값이어야 하며, 세 곳이 갈라지면 같은 날을 두 달력으로 세게 된다.

## 오류

`common/web`의 `ErrorResponse`와 `ErrorCode`를 쓴다.

| 상황 | 상태 | 코드 |
|---|---|---|
| 없는 섹터 코드 | 404 | `SECTOR_NOT_FOUND` |
| 대응표가 발행되지 않음 | 409 | `SECTOR_MAP_NOT_READY` |
| 대표 상품 정보가 아직 만들어지지 않음 | 409 | `INSTRUMENT_PROFILE_NOT_READY` |
| 가격 자료가 아직 적재되지 않음(최근 가격은 어느 섹터에도 없을 때) | 409 | `PRICE_SERIES_NOT_READY` |
| 후보 목록이 빈 요청 | 400 | 검증 실패 |
| 평가 기간 산출 자료가 아직 없음 | 409 | `EVALUATION_WINDOW_NOT_READY` |
| 등록되지 않은 전략 | 422 | `STRATEGY_NOT_REGISTERED` |

409를 쓰는 이유는 `backend/CLAUDE.md`가 결과 조회에 적용한 판단과 같다. 자료가 없는 상태를 빈 배열이나 0으로 내보내지 않고 재시도 가능한 상태로 구분한다.

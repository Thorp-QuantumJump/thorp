# Contract: 섹터 목록·대표 상품 정보 조회

**근거**: [spec.md](../spec.md) FR-001·FR-002·FR-011·FR-015, User Story 1·3

FRONT가 섹터 선택 화면(002)과 결과의 근거 패널(005)에서 쓰는 조회 계약이다. 가격 시계열은 이 계약으로 내보내지 않는다.

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

## GET /sectors/evaluation-window — 공통 평가 가능 기간

003의 "이 조합은 언제부터 비교 가능합니다" 안내가 쓴다. 산식과 경계 동작은 [evaluation-window.md](./evaluation-window.md)가 기준이며, 이 엔드포인트는 그 산식의 HTTP 표면이다.

**요청**: `?sectors=SEMICONDUCTOR,BATTERY&strategy=SECTOR_MOMENTUM`

**응답**: `evaluation-window.md`의 출력 구조에 `datasetId`를 더한 것

## 오류

`common/web`의 `ErrorResponse`와 `ErrorCode`를 쓴다.

| 상황 | 상태 | 코드 |
|---|---|---|
| 없는 섹터 코드 | 404 | `SECTOR_NOT_FOUND` |
| 대응표가 발행되지 않음 | 409 | `SECTOR_MAP_NOT_READY` |
| 대표 상품 정보가 아직 만들어지지 않음 | 409 | `INSTRUMENT_PROFILE_NOT_READY` |
| 후보 목록이 빈 요청 | 400 | 검증 실패 |

409를 쓰는 이유는 `backend/CLAUDE.md`가 결과 조회에 적용한 판단과 같다. 자료가 없는 상태를 빈 배열이나 0으로 내보내지 않고 재시도 가능한 상태로 구분한다.

# 시장 흐름 조회 API 계약

009 시장 흐름 개요가 제공하는 외부 API의 기준 문서다. 기계가 읽는 계약은
[`specs/009-market-flow-overview/contracts/openapi.yaml`](../../specs/009-market-flow-overview/contracts/openapi.yaml)이고
응답 예시는 같은 폴더의 `examples/*.json`이다. 이 문서와 계약이 어긋나면 계약을 기준으로 하고 이 문서를 고친다.

근거: [spec.md](../../specs/009-market-flow-overview/spec.md), [plan.md](../../specs/009-market-flow-overview/plan.md),
[research.md](../../specs/009-market-flow-overview/research.md).

## 경로와 봉투

외부 경로의 `/api`는 프록시가 제거하므로 Spring controller 매핑은 `/market-flow/...`다.

| 외부 경로 | controller 매핑 | 용도 |
|---|---|---|
| `GET /api/market-flow/fields` | `/market-flow/fields` | 일곱 분야 요약 조회 |
| `GET /api/market-flow/fields/{fieldId}` | `/market-flow/fields/{fieldId}` | 한 분야의 지표 실제 값과 3개월 경로 |

응답은 기존 `HttpApiResponse<T>` 봉투를 그대로 쓰고 JSON 필드는 camelCase다.

## 분야 요약은 쿼리 파라미터를 하나도 받지 않는다

**이름과 무관하게 하나라도 붙으면 400이다.** 012의 `/market-news/digests`가 `sectorId`를 받아
필터하는 것과 나란히 읽히므로, 왜 다른지를 여기 적어 둔다.

섹터 선택은 연결할 전략·대표 상품·뉴스의 범위만 좁히고 **경제환경 표시·지표 값·기준월은 바꾸지
않는다**(FR-016, 원칙 IV). 받을 자리를 만들어 두면 언젠가 채우게 되고, 그때 같은 시점의 경제가
보는 사람마다 달라진다. 받는 자리가 없어야 그 일이 일어나지 않는다.

분야를 고르는 것은 경로 변수(`/market-flow/fields/{fieldId}`)이므로 이 규칙과 무관하다.
캐시 무력화 파라미터가 필요해지면 그 이름만 허용 목록에 더한다.

7개 고정 분야에 없는 `fieldId`는 400이다.

## 상태 네 가지 (spec FR-014, research R6)

`READY`, `EXAMPLE`, `EMPTY`, `COLLECTION_FAILED`는 **모두 HTTP 200**이다. 자료가 없거나 수집이
실패한 것을 오류 응답으로 바꾸지 않는다.

| 상태 | 뜻 |
|---|---|
| `READY` | 실제 수집 자료로 채워졌다 |
| `EXAMPLE` | 예시 자료다. **`READY`로 표현하지 않는다**(FR-014, 008 FR-022) |
| `EMPTY` | 표시할 자료가 없다 |
| `COLLECTION_FAILED` | 수집이 실패했다. 지표 단위로 구분한다 |

### `STALE`이 없는 이유

012의 뉴스 묶음에는 `STALE`이 있고 여기에는 **의도적으로 없다**. 두 문서가 나란히 읽힐 때
빠뜨린 것으로 보이지 않도록 밝혀 둔다.

뉴스는 30분이라는 갱신 주기가 있어 "최근 갱신분이 아니다"를 판정할 수 있다. 경제환경 지표는
분야마다 발표 주기와 기준월이 다르고, 통계 자체가 몇 달 뒤에 나온다. 여기에 같은 잣대를 들이대면
**정상적으로 늦은 자료가 늦은 자료로 판정된다.** 그래서 009는 '지연'을 판정하지 않고 대상월과
마지막 갱신 시각을 함께 보여 사용자가 직접 읽게 한다(FR-011). 같은 이유로 '실시간'·'현재'라는
표현도 쓰지 않는다.

## 기준월은 분야마다 다르다

분야 g의 기준월 `T_g`는 그 분야에 속한 지표들이 **모두 확보된** 가장 최근 월이다. 분야마다 다를
수 있고, 응답은 그 사실을 `multipleReferenceMonthNotice`로 함께 보낸다.

`T_g` 이후에 발표된 값이 있으면 `afterReferenceMonthRelease`로 따로 보낸다. **3개월 경로
(`path`) 3칸 안에 섞지 않는다**(FR-008). 섞으면 분야의 기준월이 무엇인지가 화면에서 사라진다.

`path`는 언제나 3칸이다. 값이 없는 달은 칸을 빼지 않고 `value: null`로 보낸다.

## 역사 비교는 최신 개정자료 기준이다

`historicalComparisonNotice`는 고정 문구이며 **항상 보낸다**(FR-012, D12). 계약의 `required`에
들어 있고 백엔드도 상수로 채운다. 과거 비교가 '당시 발표값을 재현한 값'이 아니라는 사실을
화면에서 끊기지 않게 하기 위해서다.

## 응답에 없는 것

- **경제 전체를 요약하는 점수·등급·신호등**: 어떤 schema에도 필드를 두지 않는다(FR-003, D08,
  원칙 II). 분야별 서술 문장은 있지만 분야를 좋고 나쁨으로 묶는 값은 없다.
- **자료 공급원·API 이름을 고르는 입력**: 사용자가 고를 것이 아니다(FR-006).
- 전망·투자 판단·추천 표현.

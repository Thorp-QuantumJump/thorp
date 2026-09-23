/**
 * `GET /sectors` 응답을 camelCase로 1:1 옮긴 타입이다.
 *
 * 필드 정의의 원본은 001의 섹터 조회 계약(`specs/001-etf-market-data/contracts/sector-api.md`)이며
 * 002는 그 정의를 다시 쓰지 않는다.
 */

/** 업종과 테마 두 가지다. 목록의 1차 표시 순서 키이기도 하다. */
export type SectorClassification = 'INDUSTRY' | 'THEME'

/**
 * 섹터에 연결된 대표 ETF.
 *
 * 계약의 불변식이 "모든 항목에 `representativeEtf`가 있다"를 보장하므로 선택 값으로 풀지 않는다.
 * 없는 항목이 오면 그것은 계약 위반이며, 화면이 숨겨서 넘기지 않는다.
 */
export type RepresentativeEtf = {
  instrumentId: string
  name: string
  /** ISO 날짜 문자열(`YYYY-MM-DD`). 공통 비교 시작일 산출에 쓴다. */
  listedOn: string
  benchmarkIndex: string
}

/** 섹터 목록의 한 항목. */
export type SectorSummary = {
  code: string
  name: string
  classification: SectorClassification
  representativeEtf: RepresentativeEtf
}

/** 섹터 목록 한 벌. `isSample`은 **대응표 버전**이 예시인지를 가리킨다. */
export type SectorCatalog = {
  mapVersion: string
  isSample: boolean
  sectors: SectorSummary[]
}

/**
 * 대표 ETF가 담고 있는 종목 하나다.
 *
 * `weight`가 숫자가 아니라 문자열인 까닭은 계약이 그렇게 주기 때문이다. `0.2431`처럼 소수로
 * 받아 두면 표시 단계에서 부동소수 오차가 섞인다. 화면이 보일 때만 옮긴다.
 */
export type InstrumentHolding = {
  name: string
  /** `"0.2431"` 같은 소수 문자열이다. 1이 100%다. */
  weight: string
}

/** 대표 ETF의 보유 종목 구성이다. */
export type HoldingsOverlap = {
  /**
   * 이 구성이 **지금 시점**의 것인가.
   *
   * true면 화면은 당시 구성을 재현했다고 표현하지 않는다. 과거 어느 날의 구성이 아니라 오늘의
   * 구성을 보고 있는 것이므로, 과거 성과를 이 구성으로 설명하면 사실과 다른 말이 된다
   * (원칙 V, 001 계약 FR-015).
   */
  asOfCurrentComposition: boolean
  topHoldings: InstrumentHolding[]
}

/**
 * `GET /sectors/{code}/instrument-profile` 응답을 camelCase로 1:1 옮긴 타입이다.
 *
 * **`isSample`은 자료 버전 기준이다.** `GET /sectors`의 `isSample`(대응표 기준)과 다른 것을
 * 가리킨다. 대표 ETF는 확정됐지만 상품 정보를 만든 자료가 아직 예시인 상태가 있을 수 있으므로,
 * 한쪽 값으로 다른 쪽을 짐작하지 않는다(001 계약).
 */
export type InstrumentProfile = {
  instrumentId: string
  name: string
  benchmarkIndex: string
  /** 이 상품이 무엇을 어떻게 담는지 한 문단으로 적은 설명이다. */
  indexMethodologySummary: string
  /** ISO 날짜 문자열(`YYYY-MM-DD`). */
  listedOn: string
  manager: string
  holdingsOverlap: HoldingsOverlap
  /** 최근 3개월 일평균 거래대금(원). 자릿수가 커 문자열로 온다. */
  avgDailyValue3m: string
  /** 한국거래소 자료를 쓴다는 표시다. 목록 화면의 상수와 달리 여기서는 계약이 값을 준다. */
  sourceAttribution: string
  /** 상장폐지된 상품이 빠져 있다는 사실을 알리는 한 문장이다. */
  survivorshipNote: string
  /** 자료 버전 식별자다. 화면에 날것으로 보이지 않는다(FR-011). */
  datasetId: string
  /** ISO 시각 문자열. 이 자료가 어느 시점까지를 담는지다. */
  dataDeadline: string
  isSample: boolean
}

/**
 * 대표 ETF의 하루치 가격이다.
 *
 * `adjustedClose`가 숫자가 아니라 문자열인 까닭은 계약이 그렇게 주기 때문이다. 소수 여덟 자리까지
 * 오는 값을 숫자로 받아 두면 표시 단계에서 부동소수 오차가 섞인다. 그릴 때만 옮긴다.
 *
 * **거래정지일과 이어 붙인 가격을 숨기지 않는다.** 그 날 값은 직전 거래일에서 옮겨 적은 것이라
 * "그 값에 거래할 수 있었다"는 뜻이 아니다(원칙 V).
 */
export type PricePoint = {
  /** ISO 날짜 문자열(`YYYY-MM-DD`). */
  date: string
  /** `"10250.50000000"` 같은 수정종가 문자열이다. */
  adjustedClose: string
  tradingSuspended: boolean
  priceCarriedForward: boolean
}

/**
 * `GET /sectors/{code}/price-series` 응답을 camelCase로 1:1 옮긴 타입이다.
 *
 * **`isSample`은 자료 버전 기준이다.** 대표 상품 정보의 것과 같은 뜻이며 대응표의 값과는 다른 것을
 * 가리킨다.
 */
export type PriceSeries = {
  instrumentId: string
  datasetId: string
  /** ISO 시각 문자열. 이 자료가 어느 시점까지를 담는지다. */
  dataDeadline: string
  isSample: boolean
  /** 날짜 오름차순이다. 서버가 정렬해 주므로 화면이 다시 정렬하지 않는다. */
  points: PricePoint[]
}

/**
 * 섹터 도메인이 내보내는 서버 오류 신호 이름이다.
 *
 * 값의 원본은 백엔드의 `SectorExceptionHandler`이며 그 파일에 있는 넷을 그대로 옮겼다.
 * 계약 문서(`contracts/sector-api.md`)의 오류 표에는 `SECTOR_MAP_UNUSABLE`이 빠져 있다.
 *
 * 이 목록을 공용 `ServiceApiErrorCode`에 잇는 쪽은 `./api`다. **이 파일은 `./api`를 가져오지
 * 않는다** — 공용이 도메인을 알고 도메인은 공용을 모르는 한 방향이라 순환 참조가 생기지 않는다.
 */
export const sectorApiErrorCodes = [
  'SECTOR_MAP_NOT_READY',
  'SECTOR_MAP_UNUSABLE',
  'SECTOR_NOT_FOUND',
  'INSTRUMENT_PROFILE_NOT_READY',
  'PRICE_SERIES_NOT_READY',
] as const

export type SectorApiErrorCode = (typeof sectorApiErrorCodes)[number]

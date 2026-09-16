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
 * 섹터 도메인이 내보내는 서버 오류 신호 이름이다.
 *
 * 값의 원본은 백엔드의 `SectorExceptionHandler`이며 그 파일에 있는 넷을 그대로 옮겼다.
 * 계약 문서(`contracts/sector-api.md`)의 오류 표에는 `SECTOR_MAP_UNUSABLE`이 빠져 있다.
 */
export const sectorApiErrorCodes = [
  'SECTOR_MAP_NOT_READY',
  'SECTOR_MAP_UNUSABLE',
  'SECTOR_NOT_FOUND',
  'INSTRUMENT_PROFILE_NOT_READY',
] as const

export type SectorApiErrorCode = (typeof sectorApiErrorCodes)[number]

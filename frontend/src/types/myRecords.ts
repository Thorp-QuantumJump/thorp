import type { ResultConfig, VersionMark } from './result'

/**
 * spec 006 내 기록 API 타입. 계약은 `specs/006-my-records/contracts/my-records-api.md`다.
 *
 * <p>목록 항목에 <b>수익률·하락 폭·비용이 없다.</b> 값이 있으면 정렬이 없어도 나란히 보이는 순위 화면이
 * 된다(원칙 III, research R7). 서버가 그 필드를 보내지 않으므로 타입에도 자리가 없다.
 */

export type MyRecordKind = 'ANALYSIS' | 'SEARCH_BUNDLE' | 'REGIME_SEARCH'

/**
 * 뒤의 넷은 010이 정한 값 그대로다. 경제환경 검색의 '계산 불가'는 실패가 아니라 견줄 사례가 없었다는
 * 사실이라 완료/실패로 바꿔 부르지 않는다(FR-004).
 */
export type MyRecordStatus =
  | 'COMPLETED'
  | 'RUNNING'
  | 'FAILED'
  | 'CANCELLED'
  | 'PARTIAL'
  | 'NOT_COMPUTABLE'
  | 'NEAREST_ONLY'
  | 'THRESHOLD_APPLIED'
  | 'WITHDRAWN'

/** 정렬. 성과 축은 없다(research R7). */
export type MyRecordSort = 'SAVED_AT_DESC' | 'SAVED_AT_ASC' | 'NAME_ASC'

export type MyRecordPeriod = { start: string; end: string }

/**
 * 경제환경 항목의 검색 정보. '다시 보기'는 `/regime-search?searchId={snapshotKey}`로 저장 revision 을 고정
 * 조회한다(research R17). `withdrawn`이면 스냅샷 참조가 끊겨 열리지 않는다(010 FR-035).
 */
export type MyRecordRegime = {
  snapshotKey: string
  mode: 'FIELD' | 'COMPOSITE' | (string & Record<never, never>)
  fieldId: string | null
  baselineMonth: string
  baselineOrigin: 'T_G' | 'T_ALL' | 'USER_SPECIFIED' | (string & Record<never, never>)
  entryPath: 'MARKET_FLOW' | 'STRATEGY_RESULT' | (string & Record<never, never>)
  withdrawn: boolean
}

type MyRecordItemBase = {
  recordId: string
  name: string
  sectorCodes: string[]
  strategyIds: string[]
  /** 경제환경 항목은 없다(기준월이 그 자리를 대신한다) */
  period: MyRecordPeriod | null
  savedAt: string
  status: MyRecordStatus
  /** 실패·취소·부분 완료·계산 불가의 사유 코드. 낮은 수익과 섞지 않는다(FR-014) */
  statusReason: string | null
  isCopy: boolean
}

/** 종류별 열쇠는 그 종류에만 있다. 서버도 다른 종류에는 키를 만들지 않는다. */
export type MyRecordListItem =
  | (MyRecordItemBase & { kind: 'ANALYSIS'; resultToken: string })
  | (MyRecordItemBase & { kind: 'SEARCH_BUNDLE'; searchId: string })
  | (MyRecordItemBase & { kind: 'REGIME_SEARCH'; regime: MyRecordRegime })

/**
 * 저장 당시 참조 버전. 서버가 원본에 없는 키는 만들지 않는다 — 없으면 화면이 '기록되지 않음'을 보인다(원칙 VI).
 * `worker`·`dependencies`는 운영 정보라 오지 않는다.
 */
export type MyRecordReferenceVersions = {
  datasetVersion?: string
  sectorEtfMapVersion?: string
  rulesVersion?: VersionMark
  costProfileVersion?: VersionMark
  engineVersion?: string
  registryVersion?: string
  /** 경제환경 항목만 */
  modelVersion?: string
}

/** UI-01 판정(research R8). 버전 문자열이 달라도 대응 항목이 같으면 `changed: false`다. */
export type MappingDrift = {
  changed: boolean
  kind: 'INSTRUMENT_REPLACED' | 'SECTOR_REMOVED' | null
  sectorCodes: string[]
  message: string | null
}

export type RegistryDriftField = {
  axis: string
  savedValue: unknown
  currentRange: unknown
}

export type RegistryDrift = { outOfRange: boolean; fields: RegistryDriftField[] }

/**
 * 상세. 목록 항목에 펼쳐 보는 층을 더한다.
 *
 * <p>`mappingDrift`는 판정할 근거가 없으면 <b>키가 없다</b> — `changed: false`가 아니다. `registryDrift`·
 * `reopenSettings`·`reopenPayload`는 US2(설정 바꿔 비교)와 함께 온다. `reopenPayload`는 005 결과의 `config`와
 * 같은 모양이라 `prefillStrategySettings`에 그대로 넘긴다(research R4).
 */
export type MyRecordDetail = MyRecordListItem & {
  lastViewedAt: string | null
  referenceVersions: MyRecordReferenceVersions
  mappingDrift?: MappingDrift
  registryDrift?: RegistryDrift
  reopenSettings?: { available: boolean }
  reopenPayload?: ResultConfig
}

/** `GET /my-records` 질의. 배열 항목은 같은 이름을 여러 번 보낸다. */
export type MyRecordListQuery = {
  cursor?: string
  size?: number
  sort?: MyRecordSort
  kind?: MyRecordKind[]
  status?: MyRecordStatus[]
  sector?: string[]
  strategy?: string[]
  periodFrom?: string
  periodTo?: string
}

/** 전체 개수는 오지 않는다(research R14). */
export type MyRecordListPage = {
  items: MyRecordListItem[]
  nextCursor: string | null
}

/** `409 RECORD_LIMIT_REACHED`의 `error.details`(FR-018). */
export type RecordLimitError = { current: number; limit: number }

export type MyRecordExportFormat = 'json' | 'csv'

export type MyRecordApiErrorCode =
  | 'RECORD_LIMIT_REACHED'
  | 'RECORD_WITHDRAWN'
  | 'RESULT_NOT_READY'
  | 'RESOURCE_NOT_FOUND'
  | 'INVALID_INPUT'

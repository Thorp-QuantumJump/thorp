import { ApiClientError, apiClient, normalizeApiError } from './client'
import type { ApiEnvelope, ApiErrorCode, ServiceApiErrorCode } from '@/types/api'
import type {
  HoldingsOverlap,
  InstrumentHolding,
  InstrumentProfile,
  SectorCatalog,
  SectorClassification,
  SectorSummary,
} from '@/types/sector'

/**
 * 섹터 목록 조회의 결과다.
 *
 * "아직 줄 것이 없다"에 해당하는 상태를 **모두 이 층에서 갈라 놓는다.** 화면을 그리는 분기는
 * 예외가 아니라 상태이며(research.md 결정 6), 그래야 빈 목록이나 처리되지 않은 오류 화면 대신
 * 한 문장 안내를 낼 수 있다.
 *
 * `emptyCatalog`를 화면의 `sectors.length` 판정이 아니라 여기 상태 값으로 둔 까닭은 셋이다.
 * 첫째, 백엔드가 "대응표 미발행"(409)과 "섹터 0개"(200 + 빈 배열)를 **일부러 구분해** 내보내므로
 * (`SectorRepositoryImpl.findOpenSectorMap`) 그 구분을 받는 자리도 한 곳이어야 한다. 둘째,
 * 화면이 한쪽은 상태 값으로 다른 쪽은 배열 길이로 분기하면 같은 성격의 판정이 두 층으로 갈라져
 * 한쪽을 빠뜨리기 쉽다 — 실제로 빠뜨렸다. 셋째, 갈래가 타입에 있으면 빈 목록을 `loaded`로
 * 그리는 실수가 컴파일 단계에서 드러난다.
 *
 * 오류 분류는 014가 만든 `normalizeApiError`의 `api | network | unexpected`를 그대로 쓴다.
 * 002가 새 분류를 만들지 않는다.
 */
export type SectorCatalogResult =
  /** 보여 줄 섹터가 하나 이상 있다. */
  | { status: 'loaded'; catalog: SectorCatalog }
  /** 대응표는 발행되었는데 그 안에 섹터가 없다. 파이프라인이 채우면 달라진다. */
  | { status: 'emptyCatalog'; catalog: SectorCatalog }
  /** 대응표가 아직 발행되지 않았다(409 `SECTOR_MAP_NOT_READY`). */
  | { status: 'mapNotReady'; message: string }
  | {
      status: 'failed'
      kind: 'api' | 'network' | 'unexpected'
      /** 서버가 보낸 이름 그대로다. 아는 이름이 아니어도 버리지 않는다. */
      code: ApiErrorCode | null
      /** 서버가 보낸 문장이다. 화면이 쓸지 말지는 화면이 정한다. */
      message: string
      /**
       * 다시 불러서 달라질 수 있는가.
       *
       * research.md 결정 6이 "다시 눌러도 바뀌지 않는 상태에는 행동을 두지 않는다"를 요구하므로,
       * 그 판정을 화면이 아니라 오류 이름을 아는 이 층에서 한다.
       */
      retryable: boolean
    }

/**
 * 다시 불러도 달라지지 않는 서버 신호다.
 *
 * `SECTOR_MAP_UNUSABLE`은 저장된 대응표가 도메인 불변식을 어겼을 때 나오는 500이다. 서버 쪽
 * 자료를 고쳐야 풀리므로 사용자가 버튼을 다시 누르는 것으로는 달라지지 않는다.
 */
const unretryableCodes: readonly ServiceApiErrorCode[] = ['SECTOR_MAP_UNUSABLE']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isClassification(value: unknown): value is SectorClassification {
  return value === 'INDUSTRY' || value === 'THEME'
}

/**
 * 계약이 "모든 항목에 `representativeEtf`가 있다"를 보장한다. 없는 항목이 오면 계약 위반이므로
 * 그 항목만 숨겨서 넘기지 않고 응답 전체를 읽지 못한 것으로 다룬다.
 */
function readSector(value: unknown): SectorSummary | null {
  if (!isRecord(value)) return null
  const etf = value.representativeEtf

  if (
    typeof value.code !== 'string' ||
    typeof value.name !== 'string' ||
    !isClassification(value.classification) ||
    !isRecord(etf) ||
    typeof etf.instrumentId !== 'string' ||
    typeof etf.name !== 'string' ||
    typeof etf.listedOn !== 'string' ||
    typeof etf.benchmarkIndex !== 'string'
  ) {
    return null
  }

  return {
    code: value.code,
    name: value.name,
    classification: value.classification,
    representativeEtf: {
      instrumentId: etf.instrumentId,
      name: etf.name,
      listedOn: etf.listedOn,
      benchmarkIndex: etf.benchmarkIndex,
    },
  }
}

function readSectorCatalog(value: unknown): SectorCatalog | null {
  if (!isRecord(value)) return null
  if (typeof value.mapVersion !== 'string' || typeof value.isSample !== 'boolean') return null
  if (!Array.isArray(value.sectors)) return null

  const sectors: SectorSummary[] = []
  for (const item of value.sectors) {
    const sector = readSector(item)
    if (!sector) return null
    sectors.push(sector)
  }

  return { mapVersion: value.mapVersion, isSample: value.isSample, sectors }
}

const UNREADABLE_RESPONSE_MESSAGE = '섹터 목록을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 서버가 보낸 오류를 화면이 분기할 상태 값으로 옮긴다. */
function toFailureResult(error: ApiClientError): SectorCatalogResult {
  if (error.kind === 'api' && error.is('SECTOR_MAP_NOT_READY')) {
    return { status: 'mapNotReady', message: error.message }
  }

  return {
    status: 'failed',
    kind: error.kind,
    code: error.code,
    message: error.message,
    retryable: !unretryableCodes.some((code) => error.is(code)),
  }
}

/**
 * 섹터 목록을 한 번 받아 온다.
 *
 * 화면 진입마다 이 호출 **한 번**이다. `GET /sectors/evaluation-window`는 부르지 않는다 —
 * 그 산식은 003이 소유하는 워밍업 거래일 수를 필수 입력으로 받는다(research.md 결정 4).
 */
export async function fetchSectorCatalog(signal?: AbortSignal): Promise<SectorCatalogResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<SectorCatalog>>('/sectors', { signal })
    const body = response.data

    if (!body.success) {
      return toFailureResult(
        new ApiClientError('api', body.error.message, { code: body.error.code }),
      )
    }

    const catalog = readSectorCatalog(body.data)
    if (!catalog) {
      return {
        status: 'failed',
        kind: 'unexpected',
        code: null,
        message: UNREADABLE_RESPONSE_MESSAGE,
        retryable: true,
      }
    }

    // 발행된 대응표에 섹터가 하나도 없는 경우다. 백엔드가 이것을 409가 아니라 빈 배열로 내보내므로
    // 여기서 갈라 두지 않으면 화면이 빈 목록을 그린다(FR-017, spec Edge Cases).
    if (catalog.sectors.length === 0) {
      return { status: 'emptyCatalog', catalog }
    }

    return { status: 'loaded', catalog }
  } catch (error) {
    return toFailureResult(normalizeApiError(error))
  }
}

/**
 * 대표 상품 정보 조회의 결과다.
 *
 * 목록 조회와 같은 방식으로 **"아직 줄 것이 없다"를 예외가 아니라 상태로** 돌려준다
 * (research.md 결정 6). 그래야 화면이 빈 패널 대신 한 문장 안내를 낼 수 있다.
 */
export type InstrumentProfileResult =
  | { status: 'loaded'; profile: InstrumentProfile }
  /** 상품 정보를 만들 자료가 아직 없다(409 `INSTRUMENT_PROFILE_NOT_READY`). */
  | { status: 'notReady'; message: string }
  | {
      status: 'failed'
      kind: 'api' | 'network' | 'unexpected'
      code: ApiErrorCode | null
      message: string
      /** 다시 눌러 달라질 수 있는가. 판정은 오류 이름을 아는 이 층에서 한다. */
      retryable: boolean
    }

const UNREADABLE_PROFILE_MESSAGE = '대표 상품 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'

function readHolding(value: unknown): InstrumentHolding | null {
  if (!isRecord(value)) return null
  if (typeof value.name !== 'string' || typeof value.weight !== 'string') return null
  return { name: value.name, weight: value.weight }
}

function readHoldingsOverlap(value: unknown): HoldingsOverlap | null {
  if (!isRecord(value)) return null
  if (typeof value.asOfCurrentComposition !== 'boolean') return null
  if (!Array.isArray(value.topHoldings)) return null

  const topHoldings: InstrumentHolding[] = []
  for (const item of value.topHoldings) {
    const holding = readHolding(item)
    // 항목 하나가 깨졌는데 나머지만 그리면 합이 맞지 않는 구성이 사실처럼 보인다.
    if (!holding) return null
    topHoldings.push(holding)
  }

  return { asOfCurrentComposition: value.asOfCurrentComposition, topHoldings }
}

/**
 * 응답을 읽어 타입으로 옮긴다. 필드 하나라도 계약과 다르면 전체를 읽지 못한 것으로 다룬다.
 *
 * 일부만 그리지 않는 까닭은 이 패널이 "무엇을 사는 것인가"를 답하는 자리이기 때문이다. 구성이나
 * 출처가 빠진 채로 나머지를 보이면 사용자가 빠진 것을 모르고 판단한다(원칙 VI).
 */
function readInstrumentProfile(value: unknown): InstrumentProfile | null {
  if (!isRecord(value)) return null

  const holdingsOverlap = readHoldingsOverlap(value.holdingsOverlap)
  if (!holdingsOverlap) return null

  if (
    typeof value.instrumentId !== 'string' ||
    typeof value.name !== 'string' ||
    typeof value.benchmarkIndex !== 'string' ||
    typeof value.indexMethodologySummary !== 'string' ||
    typeof value.listedOn !== 'string' ||
    typeof value.manager !== 'string' ||
    typeof value.avgDailyValue3m !== 'string' ||
    typeof value.sourceAttribution !== 'string' ||
    typeof value.survivorshipNote !== 'string' ||
    typeof value.datasetId !== 'string' ||
    typeof value.dataDeadline !== 'string' ||
    typeof value.isSample !== 'boolean'
  ) {
    return null
  }

  return {
    instrumentId: value.instrumentId,
    name: value.name,
    benchmarkIndex: value.benchmarkIndex,
    indexMethodologySummary: value.indexMethodologySummary,
    listedOn: value.listedOn,
    manager: value.manager,
    holdingsOverlap,
    avgDailyValue3m: value.avgDailyValue3m,
    sourceAttribution: value.sourceAttribution,
    survivorshipNote: value.survivorshipNote,
    datasetId: value.datasetId,
    dataDeadline: value.dataDeadline,
    isSample: value.isSample,
  }
}

/** 서버가 보낸 오류를 화면이 분기할 상태 값으로 옮긴다. */
function toProfileFailure(error: ApiClientError): InstrumentProfileResult {
  if (error.kind === 'api' && error.is('INSTRUMENT_PROFILE_NOT_READY')) {
    return { status: 'notReady', message: error.message }
  }

  return {
    status: 'failed',
    kind: error.kind,
    code: error.code,
    message: error.message,
    // 없는 섹터를 다시 물어도 답이 달라지지 않는다. 대응표가 망가진 경우도 같다.
    retryable: !error.is('SECTOR_NOT_FOUND') && !error.is('SECTOR_MAP_UNUSABLE'),
  }
}

/**
 * 한 섹터의 대표 상품 정보를 받아 온다.
 *
 * 목록 조회와 **같은 `apiClient` 경로**를 쓴다. 화면이 열릴 때가 아니라 사용자가 펼칠 때 부르므로,
 * 목록만 보는 사용자는 이 왕복을 치르지 않는다.
 */
export async function fetchInstrumentProfile(
  code: string,
  signal?: AbortSignal,
): Promise<InstrumentProfileResult> {
  try {
    const response = await apiClient.get<ApiEnvelope<InstrumentProfile>>(
      `/sectors/${encodeURIComponent(code)}/instrument-profile`,
      { signal },
    )
    const body = response.data

    if (!body.success) {
      return toProfileFailure(
        new ApiClientError('api', body.error.message, { code: body.error.code }),
      )
    }

    const profile = readInstrumentProfile(body.data)
    if (!profile) {
      return {
        status: 'failed',
        kind: 'unexpected',
        code: null,
        message: UNREADABLE_PROFILE_MESSAGE,
        retryable: true,
      }
    }

    return { status: 'loaded', profile }
  } catch (error) {
    return toProfileFailure(normalizeApiError(error))
  }
}

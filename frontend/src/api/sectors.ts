import { ApiClientError, apiClient, normalizeApiError } from './client'
import type { ApiEnvelope, ApiErrorCode, ServiceApiErrorCode } from '@/types/api'
import type { EvaluationWindow, EvaluationWindowQuery, ExcludedRange } from '@/types/evaluationWindow'
import type { SectorCatalog, SectorClassification, SectorSummary } from '@/types/sector'

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
 * 그 산식은 003이 소유하는 워밍업 거래일 수를 필수 입력으로 받는다(research.md 결정 4). 그 조회는
 * 003 화면이 `fetchEvaluationWindow`로 따로 한다.
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

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

function isDate(value: unknown): value is string {
  return typeof value === 'string' && ISO_DATE.test(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
}

function readExcludedRange(value: unknown): ExcludedRange | null {
  if (!isRecord(value)) return null
  if (!isDate(value.start) || !isDate(value.end) || typeof value.reason !== 'string') return null
  return { start: value.start, end: value.end, reason: value.reason }
}

function readExcludedRanges(value: unknown): ExcludedRange[] | null {
  if (!Array.isArray(value)) return null
  const ranges: ExcludedRange[] = []
  for (const item of value) {
    const range = readExcludedRange(item)
    if (!range) return null
    ranges.push(range)
  }
  return ranges
}

/**
 * 평가 기간 응답을 읽는다. 계약과 어긋나면 `null`이다.
 *
 * <p><b>거래일 목록의 불변식까지 본다.</b> 화면은 `tradingDaysFromBase[N]`을 워밍업 N일의 공통 시작일로
 * 믿고 쓴다. 첫 원소가 기준일이 아니거나, 오름차순이 아니거나, 마지막 날이 `commonEnd`를 넘거나, 상한이
 * 목록 길이와 어긋나면 그 계산이 조용히 틀린 날짜를 내므로, 일부만 살리지 않고 응답 전체를 읽지 못한 것으로
 * 다룬다(원칙 VI).
 *
 * <p><b>빈 목록은 정상이다.</b> 기준일이 이미 `commonEnd`를 넘은 경우이며, 그때 001은 상한 키를 싣지 않는다.
 * 목록이 비었는데 상한이 오거나, 목록이 있는데 상한이 없으면 계약 위반이다.
 */
export function readEvaluationWindow(value: unknown): EvaluationWindow | null {
  if (!isRecord(value)) return null
  const {
    commonStart,
    commonEnd,
    warmupTradingDays,
    bindingCandidate,
    bindingSector,
    evaluable,
    datasetId,
    warmupBaseDate,
    tradingDaysFromBase,
    maxWarmupTradingDays,
  } = value

  if (
    (commonStart !== null && !isDate(commonStart)) ||
    !isDate(commonEnd) ||
    !isNonNegativeInteger(warmupTradingDays) ||
    typeof bindingCandidate !== 'string' ||
    typeof bindingSector !== 'string' ||
    typeof evaluable !== 'boolean' ||
    typeof datasetId !== 'string' ||
    !isDate(warmupBaseDate) ||
    !Array.isArray(tradingDaysFromBase)
  ) {
    return null
  }
  // 시작일을 잡지 못했는데 평가할 수 있다고 하면 두 칸이 서로 어긋난다.
  if (commonStart === null && evaluable) return null

  const excludedRanges = readExcludedRanges(value.excludedRanges)
  const excludedRangesFromBase = readExcludedRanges(value.excludedRangesFromBase)
  if (!excludedRanges || !excludedRangesFromBase) return null

  const days: string[] = []
  for (const day of tradingDaysFromBase) {
    // 같은 날이 두 번 오거나 순서가 뒤집히면 N번째 거래일이 하루씩 밀린다.
    if (!isDate(day) || (days.length > 0 && day <= days[days.length - 1])) return null
    days.push(day)
  }

  let limit: number | null
  if (days.length === 0) {
    if (maxWarmupTradingDays !== undefined) return null
    limit = null
  } else {
    if (days[0] !== warmupBaseDate || days[days.length - 1] > commonEnd) return null
    if (!isNonNegativeInteger(maxWarmupTradingDays) || maxWarmupTradingDays !== days.length - 1) {
      return null
    }
    limit = maxWarmupTradingDays
  }

  return {
    commonStart,
    commonEnd,
    warmupTradingDays,
    bindingCandidate,
    bindingSector,
    excludedRanges,
    evaluable,
    datasetId,
    warmupBaseDate,
    tradingDaysFromBase: days,
    maxWarmupTradingDays: limit,
    excludedRangesFromBase,
  }
}

const UNREADABLE_WINDOW_MESSAGE = '공통 평가 기간을 확인하지 못했습니다.'

/**
 * 후보 섹터 집합의 공통 평가 기간과 워밍업 계산 재료를 받아 온다(003 T042).
 *
 * <p>계약은 001 `contracts/sector-api.md`의 「GET /sectors/evaluation-window」 절이다. 자료가 아직 없으면
 * 409 `EVALUATION_WINDOW_NOT_READY`, 인자가 잘못되면 400이다.
 *
 * <p><b>섹터 집합이 바뀔 때만 부른다.</b> 워밍업은 `maxWarmupTradingDays`까지 이 응답 하나로 계산하므로
 * 설정값을 바꾸는 동안 다시 부르지 않는다(research R7-1).
 *
 * <p>실패와 계약 위반은 모두 `ApiClientError`로 거부한다. 화면은 어느 경우든 "공통 평가 기간을 확인할
 * 수 없다"로 막으므로 이유를 가르지 않는다. 404(엔드포인트가 아직 없음)도 여기에 포함된다.
 */
export async function fetchEvaluationWindow(
  query: EvaluationWindowQuery,
  signal?: AbortSignal,
): Promise<EvaluationWindow> {
  const params: Record<string, string | number> = {
    sectors: query.sectors.join(','),
    maxWarmupTradingDays: query.maxWarmupTradingDays,
  }
  if (query.strategy !== undefined) params.strategy = query.strategy

  let body: ApiEnvelope<unknown>
  try {
    const response = await apiClient.get<ApiEnvelope<unknown>>('/sectors/evaluation-window', {
      params,
      signal,
    })
    body = response.data
  } catch (error) {
    throw normalizeApiError(error)
  }

  if (!body.success) {
    throw new ApiClientError('api', body.error.message, { code: body.error.code })
  }
  const window = readEvaluationWindow(body.data)
  // 계약상 목록 길이는 요청한 상한 + 1을 넘지 않는다. 넘으면 다른 요청의 응답을 읽고 있는 것이다.
  if (
    !window ||
    (window.maxWarmupTradingDays !== null && window.maxWarmupTradingDays > query.maxWarmupTradingDays)
  ) {
    throw new ApiClientError('unexpected', UNREADABLE_WINDOW_MESSAGE)
  }
  return window
}

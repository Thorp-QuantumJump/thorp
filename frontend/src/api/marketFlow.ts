import { apiClient, normalizeApiError } from './client'
import type {
  FieldDetailResult,
  FieldStatus,
  FieldSummary,
  FieldSummaryResult,
  IndicatorDisplay,
  MarketFlowFieldId,
  MarketFlowSuccessEnvelope,
  PathPoint,
} from '@/types/marketFlow'

const fieldIds: MarketFlowFieldId[] = [
  'production',
  'consumption_investment',
  'external_trade',
  'labor',
  'prices_commodities',
  'finance_housing',
  'overseas',
]
const fieldStatuses: FieldStatus[] = ['READY', 'EXAMPLE', 'EMPTY', 'COLLECTION_FAILED']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isPathPoint(value: unknown): value is PathPoint {
  return (
    isRecord(value) &&
    typeof value.month === 'string' &&
    (value.value === null || typeof value.value === 'number')
  )
}

function isFieldSummary(value: unknown): value is FieldSummary {
  if (!isRecord(value)) return false
  return (
    fieldIds.includes(value.fieldId as MarketFlowFieldId) &&
    typeof value.displayName === 'string' &&
    typeof value.displayOrder === 'number' &&
    fieldStatuses.includes(value.status as FieldStatus) &&
    isNullableString(value.narrative) &&
    isStringArray(value.narrativeSubjects) &&
    isNullableString(value.referenceMonth) &&
    isStringArray(value.referenceMonthHeldBackBy) &&
    isNullableString(value.lastUpdatedAt) &&
    typeof value.isExampleData === 'boolean'
  )
}

function isIndicator(value: unknown): value is IndicatorDisplay {
  if (!isRecord(value)) return false
  const after = value.afterReferenceMonthRelease
  const validAfter =
    after === null ||
    (isRecord(after) &&
      typeof after.referenceMonth === 'string' &&
      typeof after.value === 'number' &&
      typeof after.unit === 'string')
  return (
    typeof value.indicatorId === 'string' &&
    typeof value.name === 'string' &&
    typeof value.unit === 'string' &&
    (value.value === null || typeof value.value === 'number') &&
    Array.isArray(value.path) &&
    value.path.length === 3 &&
    value.path.every(isPathPoint) &&
    typeof value.referenceMonth === 'string' &&
    isNullableString(value.releaseDate) &&
    typeof value.source === 'string' &&
    typeof value.agency === 'string' &&
    validAfter
  )
}

function readEnvelopeResult(value: unknown): unknown {
  if (
    !isRecord(value) ||
    value.httpStatus !== 'OK' ||
    value.isSuccess !== true ||
    typeof value.message !== 'string' ||
    value.code !== 200 ||
    !('result' in value)
  ) {
    throw new Error('시장 흐름 API 응답 형식이 계약과 다릅니다.')
  }
  return value.result
}

function parseSummaryEnvelope(value: unknown): MarketFlowSuccessEnvelope<FieldSummaryResult> {
  const result = readEnvelopeResult(value)
  if (
    !isRecord(result) ||
    typeof result.registryVersion !== 'string' ||
    typeof result.datasetVersion !== 'string' ||
    typeof result.narrativeRuleVersion !== 'string' ||
    typeof result.historicalComparisonNotice !== 'string' ||
    !Array.isArray(result.fields) ||
    !result.fields.every(isFieldSummary)
  ) {
    throw new Error('시장 흐름 분야 목록을 읽을 수 없습니다.')
  }
  return value as MarketFlowSuccessEnvelope<FieldSummaryResult>
}

function parseDetailEnvelope(value: unknown): MarketFlowSuccessEnvelope<FieldDetailResult> {
  const result = readEnvelopeResult(value)
  if (
    !isFieldSummary(result) ||
    !isRecord(result) ||
    typeof result.multipleReferenceMonthNotice !== 'string' ||
    !Array.isArray(result.indicators) ||
    !result.indicators.every(isIndicator)
  ) {
    throw new Error('시장 흐름 분야 상세를 읽을 수 없습니다.')
  }
  return value as MarketFlowSuccessEnvelope<FieldDetailResult>
}

export async function fetchMarketFlowFields(signal?: AbortSignal): Promise<FieldSummaryResult> {
  try {
    const response = await apiClient.get<unknown>('/market-flow/fields', { signal })
    return parseSummaryEnvelope(response.data).result
  } catch (error) {
    throw normalizeApiError(error)
  }
}

export async function fetchMarketFlowField(
  fieldId: MarketFlowFieldId,
  signal?: AbortSignal,
): Promise<FieldDetailResult> {
  try {
    const response = await apiClient.get<unknown>(
      `/market-flow/fields/${encodeURIComponent(fieldId)}`,
      { signal },
    )
    return parseDetailEnvelope(response.data).result
  } catch (error) {
    throw normalizeApiError(error)
  }
}

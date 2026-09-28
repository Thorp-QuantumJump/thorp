import { useCallback, useEffect, useState } from 'react'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import type { FieldSummary, IndicatorDisplay } from '@/types/marketFlow'
import styles from '@/pages/LandingPage.module.css'
import { describeFlowChange, formatFlowValue } from './marketFlowPreviewFormat'

/*
  첫 화면 둘째 칸의 시장 흐름 창. 시장 흐름 화면과 같은 자료(`GET /market-flow/fields`와 분야별 상세)를 읽어
  위에는 17개 지표가 흘러가는 띠를, 아래에는 일곱 분야의 요약을 보인다. 값을 지어내지 않는다 — 못 받으면
  못 받았다고 말하고 다시 시도할 길을 둔다.

  오르내림은 직전 달 대비 방향이며 상승 빨강·하락 파랑(`--thorp-up`·`--thorp-down`)이다. 좋고 나쁨의
  판단이 아니다.
*/

type FlowIndicator = IndicatorDisplay & { fieldName: string }

type LoadState =
  | { status: 'loading' }
  | { status: 'failed' }
  | {
      status: 'ready'
      fields: FieldSummary[]
      indicators: FlowIndicator[]
      detailsById: Record<string, FlowIndicator[]>
      /** 상세를 받지 못한 분야 이름. 그 분야의 지표는 띠에 없으므로 창에 밝힌다. */
      failedFields: string[]
    }

type ReadyState = Extract<LoadState, { status: 'ready' }>

/**
 * 월간 자료라 자주 바뀌지 않는다. 첫 화면과 시장 흐름 화면을 오갈 때마다 요청 여덟 개를 다시 보내지 않도록
 * 모두 받은 결과만 잠시 들고 있는다(useMarketIndicators의 캐시와 같은 방식).
 */
const CACHE_TTL_MS = 5 * 60_000
let cached: { value: ReadyState; storedAt: number } | null = null

function formatMonth(month: string | null) {
  return month ? month.replace('-', '.') : null
}

/** 세 달 경로를 작은 선으로. 값이 없는 달은 건너뛴다. */
function sparkPoints(indicator: IndicatorDisplay) {
  const points = indicator.path
    .map((point, index) => ({ index, value: point.value }))
    .filter((point): point is { index: number; value: number } => point.value !== null)
  if (points.length < 2) return ''
  const values = points.map((point) => point.value)
  const low = Math.min(...values)
  const range = Math.max(...values) - low || 1
  return points.map((point) => `${4 + point.index * 36},${(26 - ((point.value - low) / range) * 20).toFixed(1)}`).join(' ')
}

function FlowItem({ indicator }: { indicator: FlowIndicator }) {
  const change = describeFlowChange(indicator)
  return (
    <article className={styles.flowItem} data-trend={change.direction}>
      <div className={styles.flowItemMeta}>
        <span>{indicator.fieldName}</span>
        <small>{formatMonth(indicator.referenceMonth)}</small>
      </div>
      <b className={styles.flowItemName}>{indicator.name}</b>
      <div className={styles.flowItemReading}>
        <strong>{indicator.value === null ? '자료 없음' : formatFlowValue(indicator.value, indicator.unit)}</strong>
        <em data-direction={change.direction}>{change.text}</em>
      </div>
      <svg viewBox="0 0 80 30" aria-hidden="true">
        <polyline points={sparkPoints(indicator)} />
      </svg>
    </article>
  )
}

async function loadFlow(signal: AbortSignal): Promise<ReadyState> {
  if (cached && Date.now() - cached.storedAt < CACHE_TTL_MS) return cached.value
  const summary = await fetchMarketFlowFields(signal)
  const fields = [...summary.fields].sort((a, b) => a.displayOrder - b.displayOrder)
  // 한 분야가 실패해도 나머지 분야의 지표는 보인다.
  const details = await Promise.allSettled(fields.map((field) => fetchMarketFlowField(field.fieldId, signal)))
  const detailsById: Record<string, FlowIndicator[]> = {}
  const failedFields: string[] = []
  details.forEach((result, index) => {
    const field = fields[index]
    if (result.status === 'rejected') failedFields.push(field.displayName)
    detailsById[field.fieldId] =
      result.status === 'fulfilled' ? result.value.indicators.map((item) => ({ ...item, fieldName: field.displayName })) : []
  })
  const indicators = fields.flatMap((field) => detailsById[field.fieldId])
  const value: ReadyState = { status: 'ready', fields, indicators, detailsById, failedFields }
  if (failedFields.length === 0) cached = { value, storedAt: Date.now() }
  return value
}

/** 창 머리의 기준 표시. 가장 늦은 기준월과, 예시 자료가 섞였으면 그 사실을 적는다. */
function describeBasis(fields: FieldSummary[]) {
  const months = fields.map((field) => field.referenceMonth).filter((month): month is string => month !== null).sort()
  const latest = formatMonth(months[months.length - 1] ?? null)
  const example = fields.some((field) => field.isExampleData)
  return [example ? '예시 자료' : '월간 경제지표', latest ? `${latest} 기준` : null].filter(Boolean).join(' · ')
}

/** 받지 못한 분야를 한 문장으로. 조용히 빼면 띠에 지표가 모자란데도 다 받은 것처럼 보인다. */
function describeMissing(failedFields: string[]) {
  return `${failedFields.join('·')} 분야 자료를 불러오지 못했습니다.`
}

export function MarketFlowPreview() {
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [request, setRequest] = useState(0)
  const retry = useCallback(() => setRequest((value) => value + 1), [])

  useEffect(() => {
    const controller = new AbortController()
    setState({ status: 'loading' })
    loadFlow(controller.signal)
      .then((next) => {
        if (!controller.signal.aborted) setState(next)
      })
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: 'failed' })
      })
    return () => controller.abort()
  }, [request])

  return (
    <div className={styles.previewWindow} role="group" aria-label="시장 흐름 미리보기">
      <div className={styles.previewBar}>
        <span className={styles.previewDots} aria-hidden="true"><i /><i /><i /></span>
        <span className={styles.previewAddress} aria-hidden="true">thorp · <b>시장 흐름</b></span>
        <span className={styles.previewExample} data-state={state.status}>
          {state.status === 'ready' ? describeBasis(state.fields) : state.status === 'loading' ? '불러오는 중' : '연결 실패'}
        </span>
      </div>

      {state.status === 'ready' && state.indicators.length > 0 ? (
        <div className={styles.conveyorViewport} tabIndex={0} aria-label={`경제지표 ${state.indicators.length}개 흐름`}>
          {/* 같은 묶음을 두 번 늘어놓아 -50%만큼 옮기면 이음매 없이 이어진다. */}
          <div className={styles.conveyorTrack} style={{ animationDuration: `${Math.max(40, state.indicators.length * 4)}s` }}>
            <div className={styles.conveyorGroup}>
              {state.indicators.map((indicator) => <FlowItem key={indicator.indicatorId} indicator={indicator} />)}
            </div>
            <div className={styles.conveyorGroup} aria-hidden="true">
              {state.indicators.map((indicator) => <FlowItem key={`copy-${indicator.indicatorId}`} indicator={indicator} />)}
            </div>
          </div>
        </div>
      ) : null}

      {state.status === 'ready' && state.failedFields.length > 0 ? (
        <div className={styles.flowPartial} role="status">
          <p>{describeMissing(state.failedFields)}</p>
          <button type="button" onClick={retry}>다시 시도</button>
        </div>
      ) : null}

      {state.status === 'ready' ? (
        <ol className={styles.fieldPreview} aria-label="일곱 분야 요약">
          {state.fields.map((field) => {
            const members = state.detailsById[field.fieldId] ?? []
            return (
              <li key={field.fieldId}>
                <span className={styles.fieldNumber} aria-hidden="true">{String(field.displayOrder).padStart(2, '0')}</span>
                <b>{field.displayName}</b>
                <span className={styles.fieldNarrative}>{field.narrative ?? '이번 달 요약이 아직 없습니다.'}</span>
                <span className={styles.fieldDirections} aria-hidden="true">
                  {members.map((member) => <i key={member.indicatorId} data-direction={describeFlowChange(member).direction} />)}
                </span>
              </li>
            )
          })}
        </ol>
      ) : (
        <div className={styles.flowNotice} role="status">
          {state.status === 'loading' ? (
            <p>시장 흐름 자료를 불러오는 중입니다.</p>
          ) : (
            <>
              <p>시장 흐름 자료를 불러오지 못했습니다.</p>
              <button type="button" onClick={retry}>다시 시도</button>
            </>
          )}
        </div>
      )}
    </div>
  )
}

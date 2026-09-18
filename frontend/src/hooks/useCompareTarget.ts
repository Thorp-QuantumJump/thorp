import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { BacktestRequestError, createBacktest } from '@/api/backtest'
import { ResultError, retryResult } from '@/api/result'
import { buildResultPath, parseCompareQuery } from '@/components/result/compareQuery'
import { hasDrawableEquity } from '@/components/result/equityPoints'
import {
  type CompareChoice,
  buildCompareRequest,
  comparisonMismatch,
} from '@/components/result/compareRequest'
import type { PeriodConfirmation } from '@/components/result/PeriodMismatchDialog'
import type { PublicResultDetail } from '@/types/result'
import { type TimeseriesState, useResult } from './useResult'

export type CompareTargetState = {
  targetState: 'NONE' | 'REQUESTED' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED' | 'EXECUTION_LIMIT'
  detail: PublicResultDetail | null
  timeseries: TimeseriesState
  message: string | null
  retryAfterSeconds: number | null
  retryable: boolean
}

type Recalculation = PeriodConfirmation & {
  choice: CompareChoice | null
  target: PublicResultDetail | null
  acceptedBaseToken?: string
}
type Accepted = { publicToken?: string }
type Action = () => Promise<Accepted>
type Mutation =
  | { error: unknown; action: Action; searchId: string | null; choice: CompareChoice | null }
  | { sending: true }
  | null

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '비교 요청을 완료하지 못했습니다.'
}
function retryDelay(error: unknown): number | null {
  if (error instanceof ResultError) return error.retryAfterSeconds
  if (error instanceof BacktestRequestError) {
    const delay = error.details?.retryAfterSeconds
    if (typeof delay === 'number' && Number.isFinite(delay) && delay >= 0) return delay
  }
  return null
}
function publicTokenOf(accepted: Accepted): string {
  if (!accepted.publicToken || !/^[0-9a-f]{32}$/.test(accepted.publicToken)) {
    throw new Error('접수된 결과의 공개 주소를 확인하지 못했습니다.')
  }
  return accepted.publicToken
}
function readRequiredWarmup(error: BacktestRequestError): number | null {
  const value = error.details?.requiredWarmupTradingDays ?? error.details?.requiredWarmup
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null
}
function readRequiredStart(error: BacktestRequestError): string | null {
  const value = error.details?.requiredStart ?? error.details?.requiredStartDate
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : null
}

export function useCompareTarget(base: PublicResultDetail) {
  const location = useLocation()
  const navigate = useNavigate()
  const { compareToken, searchId } = parseCompareQuery(location.search)
  const result = useResult(compareToken)
  const [mutation, setMutation] = useState<Mutation>(null)
  const [manualPeriod, setManualPeriod] = useState<Recalculation | null>(null)
  const [dismissedToken, setDismissedToken] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const [recalculating, setRecalculating] = useState(false)
  const operation = useRef(0)
  const busy = useRef(false)

  useEffect(() => {
    operation.current += 1
    busy.current = false
    setMutation(null)
    setManualPeriod(null)
    setDialogError(null)
    setRecalculating(false)
    setDismissedToken(null)
    return () => {
      operation.current += 1
    }
  }, [compareToken, searchId])

  const detail =
    result.status === 'ready' && result.detail.publicToken === compareToken ? result.detail : null
  const state: CompareTargetState = {
    targetState: 'NONE',
    detail,
    timeseries: detail && result.status === 'ready' ? result.timeseries : { status: 'none' },
    message: null,
    retryAfterSeconds: null,
    retryable: false,
  }
  if (mutation && 'sending' in mutation) state.targetState = 'REQUESTED'
  else if (mutation && 'error' in mutation) {
    const error = mutation.error
    state.targetState =
      (error instanceof BacktestRequestError || error instanceof ResultError) &&
      (error.code === 'EXECUTION_LIMIT' || error.status === 429)
        ? 'EXECUTION_LIMIT'
        : 'FAILED'
    state.message = errorMessage(error)
    state.retryAfterSeconds = retryDelay(error)
    state.retryable = true
  } else if (compareToken) {
    if (result.status === 'expired') state.targetState = 'EXPIRED'
    else if (result.status === 'error' || result.status === 'notFound') {
      state.targetState = 'FAILED'
      state.message =
        result.status === 'error' ? result.message : '비교 대상 주소를 찾을 수 없습니다.'
      state.retryable = result.status === 'error'
    } else if (!detail) state.targetState = 'REQUESTED'
    else if (detail.status === 'QUEUED' || detail.status === 'RUNNING')
      state.targetState = 'RUNNING'
    else if (detail.status === 'FAILED' || detail.status === 'CANCELLED' || !detail.summary) {
      state.targetState = 'FAILED'
      state.message = detail.failure
        ? detail.failure.code + ': ' + detail.failure.message
        : '비교 분석이 완료되지 않아 같은 기간으로 비교할 수 없습니다.'
      state.retryable = detail.status === 'FAILED' || detail.status === 'CANCELLED'
    } else if (state.timeseries.status === 'unavailable') {
      state.targetState = 'FAILED'
      state.message = state.timeseries.message
      state.retryable = true
    } else if (state.timeseries.status === 'loaded') {
      if (hasDrawableEquity(state.timeseries.data.points)) state.targetState = 'READY'
      else {
        state.targetState = 'FAILED'
        state.message = '비교 곡선을 그릴 자산가치 기록이 없습니다.'
      }
    }
  }

  const mismatch = state.targetState === 'READY' && detail ? comparisonMismatch(base, detail) : null
  const inferredPeriod: Recalculation | null =
    (mismatch === 'period' || mismatch === 'versions') &&
    detail &&
    dismissedToken !== compareToken
      ? {
          start: [base.config.effectiveStart, detail.config.effectiveStart].sort().at(-1)!,
          end: [base.config.effectiveEnd, detail.config.effectiveEnd].sort()[0],
          warmupTradingDays: Math.max(
            base.config.warmupTradingDays,
            detail.config.warmupTradingDays,
          ),
          choice: null,
          target: detail,
          ...(mismatch === 'versions'
            ? { message: '자료 또는 계산 엔진 기준이 달라 두 결과를 같은 현재 기준으로 다시 계산합니다.' }
            : {}),
        }
      : null
  const period = manualPeriod ?? inferredPeriod

  const send = async (
    action: Action,
    nextSearchId: string | null,
    choice: CompareChoice | null = null,
  ) => {
    if (busy.current) return
    busy.current = true
    const current = ++operation.current
    setMutation({ sending: true })
    try {
      const accepted = await action()
      if (current !== operation.current) return
      const nextToken = publicTokenOf(accepted)
      setMutation(null)
      navigate(
        buildResultPath(base.publicToken, { compareToken: nextToken, searchId: nextSearchId }),
        { replace: true, state: location.state },
      )
      if (nextToken === compareToken) result.retry()
    } catch (error) {
      if (current !== operation.current) return
      if (error instanceof BacktestRequestError && error.code === 'INSUFFICIENT_HISTORY') {
        setMutation(null)
        const requiredStart = readRequiredStart(error)
        setManualPeriod({
          choice,
          target: choice ? null : detail,
          start:
            requiredStart && requiredStart > base.config.effectiveStart
              ? requiredStart
              : base.config.effectiveStart,
          end: base.config.effectiveEnd,
          warmupTradingDays: readRequiredWarmup(error),
          requiredStart,
          message: error.message,
        })
      } else setMutation({ error, action, searchId: nextSearchId, choice })
    } finally {
      if (current === operation.current) busy.current = false
    }
  }

  const select = (choice: CompareChoice) => {
    if (busy.current) return
    setDialogError(null)
    setManualPeriod(null)
    if (choice.strategy.warmupTradingDays > base.config.warmupTradingDays) {
      setManualPeriod({
        ...(choice.period ?? { start: null, end: null }),
        warmupTradingDays: choice.strategy.warmupTradingDays,
        choice,
        target: null,
      })
      return
    }
    void send(() => createBacktest(buildCompareRequest(base, choice)), null, choice)
  }

  const retry = () => {
    if (mutation && 'error' in mutation)
      void send(mutation.action, mutation.searchId, mutation.choice)
    else if (
      compareToken &&
      detail &&
      (detail.status === 'FAILED' || detail.status === 'CANCELLED')
    ) {
      void send(() => retryResult(compareToken), searchId)
    } else result.retry()
  }

  const clear = () => {
    operation.current += 1
    busy.current = false
    setMutation(null)
    setManualPeriod(null)
    navigate(buildResultPath(base.publicToken), { replace: true, state: location.state })
  }

  const confirmPeriod = async () => {
    if (
      !period ||
      busy.current ||
      period.warmupTradingDays === null ||
      !period.start ||
      !period.end ||
      period.start > period.end
    )
      return
    busy.current = true
    const current = ++operation.current
    setRecalculating(true)
    setDialogError(null)
    let acceptedBaseToken = period.acceptedBaseToken
    try {
      // 두 번째 요청이 실패해도 첫 번째 접수는 남는다. 명시적 재시도에서는 두 번째만 보낸다.
      const range = { start: period.start, end: period.end }
      const baseRequest = buildCompareRequest(base, null, period.warmupTradingDays, range)
      const targetRequest = buildCompareRequest(
        period.target ?? base,
        period.choice,
        period.warmupTradingDays,
        range,
      )
      if (!acceptedBaseToken) {
        acceptedBaseToken = publicTokenOf(await createBacktest(baseRequest))
        if (current !== operation.current) return
      }
      const targetToken = publicTokenOf(await createBacktest(targetRequest))
      if (current !== operation.current) return
      navigate(
        buildResultPath(acceptedBaseToken, {
          compareToken: targetToken,
          searchId: period.choice ? null : searchId,
        }),
        {
          state: { returnTo: location.pathname + location.search },
        },
      )
    } catch (error) {
      if (current !== operation.current) return
      let next = { ...period, acceptedBaseToken }
      if (error instanceof BacktestRequestError && error.code === 'INSUFFICIENT_HISTORY') {
        const required = readRequiredWarmup(error)
        const requiredStart = readRequiredStart(error)
        next = {
          ...period,
          acceptedBaseToken: undefined,
          warmupTradingDays: required,
          requiredStart,
          start:
            requiredStart && requiredStart > (period.start ?? '') ? requiredStart : period.start,
        }
      }
      setManualPeriod(next)
      const delay = retryDelay(error)
      setDialogError(
        errorMessage(error) +
          (delay !== null ? ' ' + delay + '초 뒤 다시 요청할 수 있습니다.' : ''),
      )
    } finally {
      if (current === operation.current) {
        busy.current = false
        setRecalculating(false)
      }
    }
  }

  return {
    ...state,
    compareToken,
    searchId,
    comparisonKind:
      searchId || detail?.config.executionType === 'SEARCH_BUNDLE'
        ? ('AUTO_SEARCH' as const)
        : ('BASIC' as const),
    compatibleTarget: state.targetState === 'READY' && !mismatch && !period ? detail : null,
    mismatch,
    period,
    dialogError,
    recalculating,
    select,
    retry,
    clear,
    confirmPeriod,
    cancelPeriod: () => {
      setManualPeriod(null)
      setDialogError(null)
      setDismissedToken(compareToken)
    },
    returnTo:
      typeof location.state?.returnTo === 'string' &&
      /^\/results\/[0-9a-f]{32}(?:\?|$)/.test(location.state.returnTo)
        ? (location.state.returnTo as string)
        : null,
  }
}

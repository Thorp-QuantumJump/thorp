import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { BacktestRequestError, createBacktest } from '@/api/backtest'
import { ResultError, retryResult } from '@/api/result'
import {
  MAX_COMPARE_TARGETS,
  buildResultPath,
  parseCompareQuery,
} from '@/components/result/compareQuery'
import { hasDrawableEquity } from '@/components/result/equityPoints'
import {
  type CompareChoice,
  buildCompareRequest,
  comparisonMismatch,
} from '@/components/result/compareRequest'
import type { PeriodConfirmation } from '@/components/result/PeriodMismatchDialog'
import type { PublicResultDetail } from '@/types/result'
import { type TimeseriesState, useResult } from './useResult'

/**
 * 비교 대상 하나의 상태(spec 005 data-model §3-2, `S15P21E205-419`로 여럿이 됐다).
 *
 * <p>주소에 든 대상(`token`)과 아직 접수 중이거나 접수에 실패해 주소에 못 오른 대상(`choice`만 있음)이 같은 모양으로
 * 나온다 — 화면이 대상마다 슬롯 하나를 그리고, 어느 쪽이든 다시 시도·해제할 수 있어야 한다.
 */
export type CompareTargetState = {
  /** 슬롯 식별자. 주소의 토큰이거나 접수 중인 후보의 자리 번호다 */
  key: string
  token: string | null
  /** 접수 중이거나 거절된 후보. 주소에 오른 대상은 결과(`detail`)가 설정을 들고 있어 `null`이다 */
  choice: CompareChoice | null
  targetState: 'REQUESTED' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED' | 'EXECUTION_LIMIT'
  detail: PublicResultDetail | null
  timeseries: TimeseriesState
  message: string | null
  retryAfterSeconds: number | null
  retryable: boolean
  /** 준비됐지만 기준과 조건·버전·기간이 달라 겹쳐 그리지 않는 이유. 없으면 `null` */
  mismatch: 'conditions' | 'versions' | 'period' | null
  /** 겹쳐 그릴 수 있는 결과. READY 이고 불일치가 없을 때만 */
  compatible: PublicResultDetail | null
}

type Recalculation = PeriodConfirmation & {
  /** 아직 접수하지 않은 후보들. 확인하면 기준과 같은 기간으로 함께 요청한다 */
  choices: CompareChoice[]
  /** 이미 결과가 있는 대상들. 확인하면 그 설정을 같은 기간으로 다시 요청한다 */
  targets: PublicResultDetail[]
  acceptedBaseToken?: string
  /** 접수가 이미 끝난 대상 토큰. 두 번째 시도에서 되풀이하지 않는다 */
  acceptedTargetTokens?: string[]
}
type Accepted = { publicToken?: string }
type Pending = {
  key: string
  choice: CompareChoice | null
  /** 접수 중이면 `null`, 거절됐으면 그 오류 */
  error: unknown | null
}

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
function isLimit(error: unknown): boolean {
  return (
    (error instanceof BacktestRequestError || error instanceof ResultError) &&
    (error.code === 'EXECUTION_LIMIT' || error.status === 429)
  )
}
function isInsufficientHistory(error: unknown): error is BacktestRequestError {
  return error instanceof BacktestRequestError && error.code === 'INSUFFICIENT_HISTORY'
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
function latest(dates: string[]): string {
  return [...dates].sort().at(-1)!
}
function earliest(dates: string[]): string {
  return [...dates].sort()[0]
}

/** 주소의 대상 하나를 조회 결과로 읽는다. */
function readTokenState(
  base: PublicResultDetail,
  token: string,
  result: ReturnType<typeof useResult>,
): CompareTargetState {
  const detail =
    result.status === 'ready' && result.detail.publicToken === token ? result.detail : null
  const state: CompareTargetState = {
    key: token,
    token,
    choice: null,
    targetState: 'REQUESTED',
    detail,
    timeseries: detail && result.status === 'ready' ? result.timeseries : { status: 'none' },
    message: null,
    retryAfterSeconds: null,
    retryable: false,
    mismatch: null,
    compatible: null,
  }
  if (result.status === 'expired') state.targetState = 'EXPIRED'
  else if (result.status === 'error' || result.status === 'notFound') {
    state.targetState = 'FAILED'
    state.message =
      result.status === 'error' ? result.message : '비교 대상 주소를 찾을 수 없습니다.'
    state.retryable = result.status === 'error'
  } else if (!detail) state.targetState = 'REQUESTED'
  else if (detail.status === 'QUEUED' || detail.status === 'RUNNING') state.targetState = 'RUNNING'
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
  if (state.targetState === 'READY' && detail) {
    state.mismatch = comparisonMismatch(base, detail)
    state.compatible = state.mismatch ? null : detail
  }
  return state
}

/** 아직 주소에 오르지 않은 후보의 상태. 접수 중이거나 접수가 거절됐다. */
function readPendingState(pending: Pending): CompareTargetState {
  const state: CompareTargetState = {
    key: pending.key,
    token: null,
    choice: pending.choice,
    targetState: 'REQUESTED',
    detail: null,
    timeseries: { status: 'none' },
    message: null,
    retryAfterSeconds: null,
    retryable: false,
    mismatch: null,
    compatible: null,
  }
  if (pending.error !== null) {
    state.targetState = isLimit(pending.error) ? 'EXECUTION_LIMIT' : 'FAILED'
    state.message = errorMessage(pending.error)
    state.retryAfterSeconds = retryDelay(pending.error)
    state.retryable = pending.choice !== null
  }
  return state
}

/**
 * 비교 대상 여럿(FR-004·FR-005, quickstart 3). 주소 `?compare=`의 토큰마다 결과를 읽고, 새로 고른 후보는 접수해 주소에 더한다.
 *
 * <p><b>대상은 상한이 셋이고 훅은 그 수만큼 조회를 든다.</b> 훅은 개수가 바뀌면 부를 수 없어 자리 셋을 늘 부르고 빈 자리는
 * `null` 토큰으로 놀린다.
 *
 * <p><b>화면이 조용히 재시도하지 않는다.</b> 한도·실패는 대상마다 자리에 남고 사용자가 누를 때만 다시 보낸다(원칙 IX).
 *
 * <p><b>기간 확인은 한 번이다.</b> 고른 후보 가운데 준비 기간이 기준보다 긴 것이 있으면 가장 긴 것 기준으로 공통 구간을 한 번
 * 확인하고, 확인하면 기준과 모든 후보를 그 구간으로 함께 요청한다(FR-005).
 */
export function useCompareTargets(base: PublicResultDetail) {
  const location = useLocation()
  const navigate = useNavigate()
  // 참고 지수선(`?index=`)은 이 훅이 다루지 않지만 비교 주소를 다시 쓸 때 잃지 않게 물려준다.
  const { compareTokens, searchId, index } = parseCompareQuery(location.search)
  // 훅은 반복문 안에서 부를 수 없다. 상한이 셋이라 자리 셋을 고정으로 둔다.
  const results = [
    useResult(compareTokens[0] ?? null),
    useResult(compareTokens[1] ?? null),
    useResult(compareTokens[2] ?? null),
  ]
  const [pending, setPending] = useState<Pending[]>([])
  const [manualPeriod, setManualPeriod] = useState<Recalculation | null>(null)
  const [dismissedFor, setDismissedFor] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const [recalculating, setRecalculating] = useState(false)
  const operation = useRef(0)
  const busy = useRef(false)
  const pendingSeq = useRef(0)

  const tokensKey = compareTokens.join(',')
  useEffect(() => {
    operation.current += 1
    busy.current = false
    setManualPeriod(null)
    setDialogError(null)
    setRecalculating(false)
    setDismissedFor(null)
    return () => {
      operation.current += 1
    }
  }, [tokensKey, searchId])

  const targets: CompareTargetState[] = [
    ...compareTokens.map((token, index) => readTokenState(base, token, results[index])),
    ...pending.map(readPendingState),
  ]
  const readyMismatched = targets
    .map((target) => target.detail)
    .filter((detail, index): detail is PublicResultDetail => {
      const mismatch = targets[index].mismatch
      return detail !== null && (mismatch === 'period' || mismatch === 'versions')
    })
  const inferredPeriod: Recalculation | null =
    readyMismatched.length > 0 && dismissedFor !== tokensKey
      ? {
          start: latest([
            base.config.effectiveStart,
            ...readyMismatched.map((detail) => detail.config.effectiveStart),
          ]),
          end: earliest([
            base.config.effectiveEnd,
            ...readyMismatched.map((detail) => detail.config.effectiveEnd),
          ]),
          warmupTradingDays: Math.max(
            base.config.warmupTradingDays,
            ...readyMismatched.map((detail) => detail.config.warmupTradingDays),
          ),
          choices: [],
          targets: readyMismatched,
          ...(targets.some((target) => target.mismatch === 'versions')
            ? {
                message:
                  '자료 또는 계산 엔진 기준이 달라 결과들을 같은 현재 기준으로 다시 계산합니다.',
              }
            : {}),
        }
      : null
  const period = manualPeriod ?? inferredPeriod

  const go = (tokens: string[]) =>
    navigate(buildResultPath(base.publicToken, { compareTokens: tokens, searchId, index }), {
      replace: true,
      state: location.state,
    })

  /** 후보들을 접수한다. 성공한 토큰은 주소에 더하고, 거절은 그 자리에 남긴다. */
  const submit = async (choices: CompareChoice[]) => {
    if (busy.current || choices.length === 0) return
    busy.current = true
    const current = ++operation.current
    const items: Pending[] = choices.map((choice) => ({
      key: 'pending-' + ++pendingSeq.current,
      choice,
      error: null,
    }))
    setPending((known) => [...known, ...items])
    const settled = await Promise.allSettled(
      items.map((item) => createBacktest(buildCompareRequest(base, item.choice!))),
    )
    if (current !== operation.current) return
    const accepted: string[] = []
    const failed: Pending[] = []
    let history: { choice: CompareChoice; error: BacktestRequestError } | null = null
    settled.forEach((outcome, index) => {
      const item = items[index]
      if (outcome.status === 'fulfilled') {
        try {
          accepted.push(publicTokenOf(outcome.value))
        } catch (error) {
          failed.push({ ...item, error })
        }
      } else if (isInsufficientHistory(outcome.reason) && history === null) {
        // 준비 자료 부족은 자리에 남기지 않고 공통 구간 확인으로 넘긴다. 여럿이면 첫 것 기준으로 한 번만 묻는다.
        history = { choice: item.choice!, error: outcome.reason }
      } else failed.push({ ...item, error: outcome.reason })
    })
    setPending((known) => [...known.filter((item) => !items.includes(item)), ...failed])
    const insufficient = history as { choice: CompareChoice; error: BacktestRequestError } | null
    if (insufficient) {
      const requiredStart = readRequiredStart(insufficient.error)
      setManualPeriod({
        choices: [insufficient.choice],
        targets: [],
        start:
          requiredStart && requiredStart > base.config.effectiveStart
            ? requiredStart
            : base.config.effectiveStart,
        end: base.config.effectiveEnd,
        warmupTradingDays: readRequiredWarmup(insufficient.error),
        requiredStart,
        message: insufficient.error.message,
      })
    }
    busy.current = false
    if (accepted.length > 0) {
      const next = [...compareTokens]
      for (const token of accepted) if (!next.includes(token)) next.push(token)
      void go(next.slice(0, MAX_COMPARE_TARGETS))
    }
  }

  /**
   * 후보들을 고른다. 준비 기간이 기준보다 긴 후보가 있으면 접수하지 않고 공통 구간 확인으로 넘긴다 — 확인하면 기준과 모든
   * 후보를 같은 구간으로 함께 요청한다.
   */
  const select = (choices: CompareChoice[]) => {
    if (busy.current) return
    const room = MAX_COMPARE_TARGETS - targets.length
    const chosen = choices.slice(0, Math.max(0, room))
    if (chosen.length === 0) return
    setDialogError(null)
    setManualPeriod(null)
    const longer = chosen.filter(
      (choice) => choice.strategy.warmupTradingDays > base.config.warmupTradingDays,
    )
    if (longer.length > 0) {
      const withPeriod = longer.filter((choice) => choice.period)
      setManualPeriod({
        start: withPeriod.length ? latest(withPeriod.map((choice) => choice.period!.start)) : null,
        end: withPeriod.length ? earliest(withPeriod.map((choice) => choice.period!.end)) : null,
        warmupTradingDays: Math.max(...longer.map((choice) => choice.strategy.warmupTradingDays)),
        choices: chosen,
        targets: [],
      })
      return
    }
    void submit(chosen)
  }

  /** 자리 하나를 다시 시도한다. 접수 거절은 다시 접수, 실패한 결과는 공개 토큰으로 재시도, 그 외는 다시 읽는다. */
  const retry = (key: string) => {
    const item = pending.find((candidate) => candidate.key === key)
    if (item) {
      if (busy.current || !item.choice) return
      setPending((known) => known.filter((candidate) => candidate.key !== key))
      void submit([item.choice])
      return
    }
    const index = compareTokens.indexOf(key)
    if (index === -1) return
    const detail = targets[index].detail
    if (detail && (detail.status === 'FAILED' || detail.status === 'CANCELLED')) {
      if (busy.current) return
      busy.current = true
      const current = ++operation.current
      void retryResult(key)
        .then((accepted) => {
          if (current !== operation.current) return
          const next = [...compareTokens]
          next[index] = publicTokenOf(accepted)
          void go(next)
        })
        .catch((error: unknown) => {
          if (current !== operation.current) return
          // 재시도는 후보가 아니라 결과라 설정을 다시 조립하지 않는다. 자리만 남겨 사유를 보인다.
          setPending((known) => [
            ...known,
            { key: 'pending-' + ++pendingSeq.current, choice: null, error },
          ])
        })
        .finally(() => {
          if (current === operation.current) busy.current = false
        })
      return
    }
    results[index].retry()
  }

  /** 자리 하나를 뺀다. 접수 중이던 것은 늦게 온 응답이 주소를 건드리지 않게 세대를 올린다. */
  const remove = (key: string) => {
    operation.current += 1
    busy.current = false
    setPending((known) => known.filter((item) => item.key !== key))
    if (compareTokens.includes(key)) void go(compareTokens.filter((token) => token !== key))
  }

  const clear = () => {
    operation.current += 1
    busy.current = false
    setPending([])
    setManualPeriod(null)
    void go([])
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
    const warmup = period.warmupTradingDays
    const range = { start: period.start, end: period.end }
    let acceptedBaseToken = period.acceptedBaseToken
    const acceptedTargets = [...(period.acceptedTargetTokens ?? [])]
    try {
      // 요청은 순서대로 보낸다. 하나가 실패해도 앞서 접수된 것은 남고, 다시 시도에서는 남은 것만 보낸다.
      if (!acceptedBaseToken) {
        acceptedBaseToken = publicTokenOf(
          await createBacktest(buildCompareRequest(base, null, warmup, range)),
        )
        if (current !== operation.current) return
      }
      const requests = [
        ...period.targets.map((target) => buildCompareRequest(target, null, warmup, range)),
        ...period.choices.map((choice) => buildCompareRequest(base, choice, warmup, range)),
      ]
      for (let index = acceptedTargets.length; index < requests.length; index += 1) {
        acceptedTargets.push(publicTokenOf(await createBacktest(requests[index])))
        if (current !== operation.current) return
      }
      void navigate(
        buildResultPath(acceptedBaseToken, {
          compareTokens: acceptedTargets,
          searchId: period.choices.length ? null : searchId,
          index,
        }),
        { state: { returnTo: location.pathname + location.search } },
      )
    } catch (error) {
      if (current !== operation.current) return
      let next: Recalculation = {
        ...period,
        acceptedBaseToken,
        acceptedTargetTokens: acceptedTargets,
      }
      if (isInsufficientHistory(error)) {
        const requiredStart = readRequiredStart(error)
        next = {
          ...period,
          acceptedBaseToken: undefined,
          acceptedTargetTokens: [],
          warmupTradingDays: readRequiredWarmup(error),
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
    targets,
    /** 겹쳐 그릴 수 있는 대상들. 기간 확인이 열려 있으면 비어 있다 — 확인 전까지는 기준만 그린다(FR-005) */
    compatibleTargets: period
      ? []
      : targets.filter((target) => target.compatible !== null),
    searchId,
    /** 더 고를 수 있는 자리 수 */
    room: Math.max(0, MAX_COMPARE_TARGETS - targets.length),
    sending: pending.some((item) => item.error === null),
    comparisonKind:
      searchId || targets.some((target) => target.detail?.config.executionType === 'SEARCH_BUNDLE')
        ? ('AUTO_SEARCH' as const)
        : ('BASIC' as const),
    period,
    dialogError,
    recalculating,
    select,
    retry,
    remove,
    clear,
    confirmPeriod,
    cancelPeriod: () => {
      setManualPeriod(null)
      setDialogError(null)
      setDismissedFor(tokensKey)
    },
    returnTo:
      typeof location.state?.returnTo === 'string' &&
      /^\/results\/[0-9a-f]{32}(?:\?|$)/.test(location.state.returnTo)
        ? (location.state.returnTo as string)
        : null,
  }
}

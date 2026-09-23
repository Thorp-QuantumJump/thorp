import { useEffect, useRef, useState } from 'react'
import { BacktestRequestError, createBacktest } from '@/api/backtest'
import { fetchStrategyRegistry } from '@/api/strategyRegistry'
import { Button } from '@/components/ui/button'
import { useResult } from '@/hooks/useResult'
import type { BacktestCreateRequest } from '@/types/backtestRequest'
import type { PublicResultDetail } from '@/types/result'
import { buildCompareRequest } from './compareRequest'
import { formatMoney, formatRatio } from './formatResult'
import styles from './auxiliaryViews.module.css'

type RequestState =
  | { status: 'idle' }
  | { status: 'sending' }
  | { status: 'accepted'; token: string }
  | { status: 'error'; message: string; retryAfterSeconds: number | null }
  | {
      status: 'history'
      message: string
      requiredWarmupTradingDays: number | null
      requiredStartDate: string | null
    }

function numberDetail(error: BacktestRequestError, key: string): number | null {
  const value = error.details?.[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function stringDetail(error: BacktestRequestError, key: string): string | null {
  const value = error.details?.[key]
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : null
}

function publicTokenOf(value: { publicToken?: string }): string {
  if (value.publicToken && /^[0-9a-f]{32}$/.test(value.publicToken)) return value.publicToken
  throw new Error('접수된 목표 위험 결과의 공개 주소를 확인하지 못했습니다.')
}

/** 등록표의 퍼센트 숫자(10)를 004 계약의 연환산 decimal 문자열("0.1000")로 옮긴다. */
function targetVolatility(value: number): string {
  return (value / 100).toFixed(4)
}

/**
 * '목표 위험 맞춤' 탭(FR-017, T078·T098). 탭을 처음 고를 때 붙고 그때 별도 실행을 한 번 요청한다. 탭을 옮겨도
 * 내려가지 않으므로 요청 상태가 남고, 다시 요청은 사용자가 눌러야 한다.
 */
export function TargetRiskView({ base }: { base: PublicResultDetail }) {
  const started = useRef(false)
  const [requestState, setRequestState] = useState<RequestState>({ status: 'idle' })
  const requestRef = useRef<BacktestCreateRequest | null>(null)
  const active = useRef(true)
  const resultToken = requestState.status === 'accepted' ? requestState.token : null
  const result = useResult(resultToken, { loadTimeseries: false })

  useEffect(() => {
    active.current = true
    // StrictMode는 마운트 효과를 두 번 돌린다. 요청은 한 번이어야 하므로 ref로 막는다.
    if (!started.current) {
      started.current = true
      void start()
    }
    return () => {
      active.current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 처음 붙을 때 한 번만 요청한다
  }, [])

  const send = async (request: BacktestCreateRequest) => {
    requestRef.current = request
    setRequestState({ status: 'sending' })
    try {
      const accepted = await createBacktest(request)
      if (active.current) {
        setRequestState({ status: 'accepted', token: publicTokenOf(accepted) })
      }
    } catch (error) {
      if (!active.current) return
      if (error instanceof BacktestRequestError && error.code === 'INSUFFICIENT_HISTORY') {
        setRequestState({
          status: 'history',
          message: error.message,
          requiredWarmupTradingDays: numberDetail(error, 'requiredWarmupTradingDays'),
          requiredStartDate: stringDetail(error, 'requiredStartDate'),
        })
        return
      }
      setRequestState({
        status: 'error',
        message:
          error instanceof Error ? error.message : '목표 위험 결과를 요청하지 못했습니다.',
        retryAfterSeconds:
          error instanceof BacktestRequestError && error.code === 'EXECUTION_LIMIT'
            ? numberDetail(error, 'retryAfterSeconds')
            : null,
      })
    }
  }

  const start = async () => {
    setRequestState({ status: 'sending' })
    try {
      const registry = await fetchStrategyRegistry(base.config.items.length)
      if (!active.current) return
      const request = buildCompareRequest(base, null)
      request.conditions.scenario = 'TARGET_RISK'
      request.conditions.scenarioParams = {
        targetVolatility: targetVolatility(
          registry.analysisConditions.targetVolatility.default,
        ),
      }
      await send(request)
    } catch (error) {
      if (!active.current) return
      setRequestState({
        status: 'error',
        message:
          error instanceof Error ? error.message : '목표 위험 결과를 요청하지 못했습니다.',
        retryAfterSeconds: null,
      })
    }
  }

  const retryRequest = () => {
    if (requestRef.current) {
      void send(requestRef.current)
      return
    }
    void start()
  }

  const retryWithHistory = () => {
    if (requestState.status !== 'history' || !requestRef.current) return
    const request = structuredClone(requestRef.current)
    if (requestState.requiredWarmupTradingDays !== null) {
      request.conditions.warmupTradingDays = requestState.requiredWarmupTradingDays
    }
    void send(request)
  }

  const detail = result.status === 'ready' ? result.detail : null
  const failure = detail?.failure ?? null
  const summaryUnavailable = detail?.summaryUnavailable ?? null
  const inFlight = detail?.status === 'QUEUED' || detail?.status === 'RUNNING'
  const limited =
    failure && ['ESTIMATION_FAILED', 'DATA_INSUFFICIENT'].includes(failure.code)

  return (
    <section className={styles.view} role="region" aria-label="목표 위험 맞춤">
      <h2 className={styles.title}>목표 위험 맞춤 시나리오</h2>
      {requestState.status === 'sending' ||
      (resultToken !== null && result.status === 'loading') ||
      inFlight ? (
        <p className={styles.copy} role="status">
          별도 시나리오 결과를 준비하고 있습니다.
        </p>
      ) : null}
      {requestState.status === 'error' ? (
        <>
          <p className={styles.reason} role="alert">
            {requestState.message}
            {requestState.retryAfterSeconds !== null
              ? ` ${requestState.retryAfterSeconds}초 뒤 다시 요청할 수 있습니다.`
              : ''}
          </p>
          <Button
            type="button"
            variant="outline"
            onClick={retryRequest}
          >
            다시 요청하기
          </Button>
        </>
      ) : null}
      {requestState.status === 'history' ? (
        <>
          <p className={styles.reason} role="alert">
            {requestState.message}
          </p>
          {requestState.requiredStartDate ? (
            <p className={styles.reason}>
              {requestState.requiredStartDate} 이후를 포함하도록 분석 기간을 다시 선택해야 합니다.
            </p>
          ) : null}
          {requestState.requiredWarmupTradingDays !== null &&
          requestState.requiredStartDate === null ? (
            <Button type="button" variant="outline" onClick={retryWithHistory}>
              필요 기간으로 다시 요청하기
            </Button>
          ) : null}
        </>
      ) : null}
      {result.status === 'expired' || result.status === 'notFound' ? (
        <p className={styles.reason}>별도 시나리오 결과를 더 이상 열 수 없습니다.</p>
      ) : null}
      {result.status === 'error' ? (
        <>
          <p className={styles.reason} role="alert">
            {result.message}
          </p>
          <Button type="button" variant="outline" onClick={result.retry}>
            결과 다시 불러오기
          </Button>
        </>
      ) : null}
      {summaryUnavailable ? (
        <>
          <p className={styles.reason} role="alert">
            별도 시나리오가 취소되어 핵심 결과를 계산하지 못했습니다.
          </p>
          <Button type="button" variant="outline" onClick={retryRequest}>
            다시 요청하기
          </Button>
        </>
      ) : null}
      {limited && failure ? (
        <p className={styles.reason}>
          자료가 충분하지 않아 목표 위험을 계산하지 못했습니다. {failure.message}
        </p>
      ) : null}
      {failure && !limited && !summaryUnavailable ? (
        <p className={styles.reason} role="alert">
          목표 위험 시나리오를 완료하지 못했습니다. {failure.message}
        </p>
      ) : null}
      {detail?.summary ? (
        <dl className={styles.metrics} aria-label="목표 위험 맞춤 핵심 결과">
          <div className={styles.metric}>
            <dt>비용 차감 후 전체 수익률</dt>
            <dd>{formatRatio(detail.summary.netTotalReturn)}</dd>
          </div>
          <div className={styles.metric}>
            <dt>가장 크게 줄었던 폭</dt>
            <dd>{formatRatio(detail.summary.maxDrawdown)}</dd>
          </div>
          <div className={styles.metric}>
            <dt>거래비용</dt>
            <dd>{formatMoney(detail.summary.totalTradingCost)}</dd>
          </div>
        </dl>
      ) : null}
    </section>
  )
}

import { useParams } from 'react-router-dom'
import { AppHeader } from '@/components/navigation/AppHeader'
import { strategyLabel } from '@/components/result/conditionLine'
import { EquityChart } from '@/components/result/EquityChart'
import { ResultSummaryCard } from '@/components/result/ResultSummaryCard'
import { ResultUnavailableNotice } from '@/components/result/ResultUnavailableNotice'
import { selectSummarySentence } from '@/components/result/summarySentences'
import { type TimeseriesState, useResult } from '@/hooks/useResult'
import type { PublicResultDetail } from '@/types/result'
import styles from './ResultPage.module.css'

/**
 * 결과 첫 화면(`/results/:token`, spec 005 US1).
 *
 * <p><b>바로 보이는 것은 네 가지뿐이다</b>: 조건 한 줄, 핵심 숫자 3개, 그래프 1개, 해석 요약 1문장
 * (FR-001, SC-001). 상세는 요청해야 열리고, 내부 계산 항목을 나열하지 않는다(FR-020).
 *
 * <p>로그인 없이 연다. 주소에는 공개 토큰만 있고 004의 내부 실행 식별자는 어디에도 없다(SC-009).
 *
 * <p>상태 한 문장(B06 T075)·자세히 보기(B07 T064)·비교(B05 T050)는 뒤 묶음이 표시한 자리에 끼운다.
 */
export function ResultPage() {
  const { token = '' } = useParams()
  // 토큰만 바뀌면 라우터가 같은 화면을 다시 쓴다. 앞 결과의 숫자가 새 주소 아래 한순간이라도 보이지
  // 않도록 토큰마다 새로 그린다.
  return <ResultScreen key={token} token={token} />
}

function ResultScreen({ token }: { token: string }) {
  const result = useResult(token)

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        {result.status === 'loading' ? (
          <p className={styles.status} role="status">
            결과를 불러오는 중입니다.
          </p>
        ) : null}

        {result.status === 'expired' ? <ResultUnavailableNotice reason="expired" /> : null}
        {result.status === 'notFound' ? <ResultUnavailableNotice reason="notFound" /> : null}

        {result.status === 'error' ? (
          <div className={styles.error} role="alert">
            <p className={styles.status}>{result.message}</p>
            <button type="button" className={styles.retry} onClick={result.retry}>
              다시 불러오기
            </button>
          </div>
        ) : null}

        {result.status === 'ready' ? (
          <ResultView detail={result.detail} timeseries={result.timeseries} />
        ) : null}
      </main>
    </div>
  )
}

function ResultView({
  detail,
  timeseries,
}: {
  detail: PublicResultDetail
  timeseries: TimeseriesState
}) {
  const completedThrough = completedRange(detail, timeseries)
  const { summary } = detail

  return (
    <article className={styles.result} aria-labelledby="result-title">
      <h1 id="result-title" className={styles.title}>
        분석 결과
      </h1>

      {detail.status === 'QUEUED' || detail.status === 'RUNNING' ? (
        <p className={styles.status} role="status">
          {inFlightMessage(detail)}
        </p>
      ) : null}

      {detail.status === 'FAILED' ? (
        <p className={styles.status}>분석이 끝나지 못해 결과 숫자가 없습니다.</p>
      ) : null}

      <ResultSummaryCard detail={detail} completedThrough={completedThrough} />

      <ResultChartSlot detail={detail} timeseries={timeseries} />

      {summary ? (
        <p className={styles.sentence}>
          {selectSummarySentence({
            base: {
              name: strategyLabel(detail),
              netTotalReturn: summary.netTotalReturn,
              maxDrawdown: summary.maxDrawdown,
              totalTradingCost: summary.totalTradingCost,
            },
            partial: detail.partial ? { completedThrough } : null,
          })}
        </p>
      ) : null}

      {/* B06 T075: 결과 의미를 바꾸는 상태의 한 문장이 이 자리에 온다. */}
      {/* B07 T064: '자세히 보기'가 이 자리에 온다. */}

      <p className={styles.source}>자료 출처: 한국거래소 통계정보</p>
    </article>
  )
}

/**
 * 그래프 자리(FR-001).
 *
 * <p>시계열을 받았을 때만 그린다. 받지 못했으면 빈 곡선 대신 받지 못한 사유를 한 문장으로 둔다 —
 * 빈 그래프는 "돌았고 비어 있었다"는 거짓이 된다. 아직 계산 중이면 그릴 것이 없으므로 자리를 비운다.
 *
 * <p>비교 대상 곡선은 B05가 이 컴포넌트의 `target`에 이어 붙인다.
 */
function ResultChartSlot({
  detail,
  timeseries,
}: {
  detail: PublicResultDetail
  timeseries: TimeseriesState
}) {
  if (timeseries.status === 'unavailable') {
    return (
      <p className={styles.status}>그래프를 그릴 시계열을 받지 못했습니다. {timeseries.message}</p>
    )
  }
  if (timeseries.status !== 'loaded') return null

  const { summary } = detail

  return (
    <EquityChart
      altNumbers={
        summary
          ? {
              netTotalReturn: summary.netTotalReturn,
              maxDrawdown: summary.maxDrawdown,
              totalTradingCost: summary.totalTradingCost,
            }
          : null
      }
      base={{
        name: strategyLabel(detail),
        points: timeseries.data.points,
        maxDrawdown: summary?.maxDrawdown ?? null,
      }}
      initialCash={detail.config.initialCash}
    />
  )
}

/**
 * 완료 범위. 계산 중이면 진행 보고의 날짜를, 끝난 결과면 받은 시계열의 마지막 날짜를 쓴다.
 * 공개 상세는 끝난 결과에 `progress`를 담지 않는다. 둘 다 없으면 지어내지 않고 `null`이다.
 */
function completedRange(detail: PublicResultDetail, timeseries: TimeseriesState): string | null {
  if (detail.progress?.lastTradingDate) return detail.progress.lastTradingDate
  if (timeseries.status !== 'loaded') return null
  const points = timeseries.data.points
  return points.length > 0 ? points[points.length - 1].date : null
}

function inFlightMessage(detail: PublicResultDetail): string {
  if (detail.status === 'QUEUED') return '분석이 차례를 기다리고 있습니다.'
  const progress = detail.progress
  if (progress?.completedTradingDays != null && progress.totalTradingDays) {
    return `분석을 계산하고 있습니다. 전체 ${progress.totalTradingDays}거래일 중 ${progress.completedTradingDays}거래일을 마쳤습니다.`
  }
  return '분석을 계산하고 있습니다.'
}

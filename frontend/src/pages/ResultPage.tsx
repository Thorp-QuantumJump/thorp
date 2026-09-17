import { useId, useMemo } from 'react'
import { useParams } from 'react-router-dom'
import { AppHeader } from '@/components/navigation/AppHeader'
import { strategyLabel } from '@/components/result/conditionLine'
import { DetailPanel } from '@/components/result/DetailPanel'
import { findDrawdownWindow } from '@/components/result/drawdownWindow'
import { isZeroValue } from '@/components/result/formatResult'
import {
  LOSS_WINDOW_NONE,
  LOSS_WINDOW_UNAVAILABLE,
  LossWindowView,
} from '@/components/result/LossWindowView'
import { OtherConditionsButton } from '@/components/result/OtherConditionsButton'
import { ResultSummaryCard } from '@/components/result/ResultSummaryCard'
import { ResultUnavailableNotice } from '@/components/result/ResultUnavailableNotice'
import { selectSummarySentence } from '@/components/result/summarySentences'
import { Button } from '@/components/ui/button'
import { useDisclosure } from '@/hooks/useDisclosure'
import { type TimeseriesState, hasViewableResult, useResult } from '@/hooks/useResult'
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
 * <p>'자세히 보기'·'손실이 커진 때 보기'·'다른 조건으로 비교'(B07)는 보조 모양 버튼이고, 누르면 이 화면
 * 안에서 열거나 설정 화면으로 옮긴다. 주소는 바꾸지 않는다(SC-006).
 *
 * <p>그래프(B04 T044)·상태 한 문장(B06 T075)·비교(B05 T050)는 뒤 묶음이 표시한 자리에 끼운다.
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
          <ResultView token={token} detail={result.detail} timeseries={result.timeseries} />
        ) : null}
      </main>
    </div>
  )
}

function ResultView({
  token,
  detail,
  timeseries,
}: {
  token: string
  detail: PublicResultDetail
  timeseries: TimeseriesState
}) {
  const completedThrough = completedRange(detail, timeseries)
  const { summary } = detail

  const viewable = hasViewableResult(detail)
  // 그래프 음영(B04)과 '손실이 커진 때 보기'가 같은 구간을 쓴다. 요약과 어긋나면 `null`이다(R-05).
  const lossWindow = useMemo(
    () =>
      summary && timeseries.status === 'loaded'
        ? findDrawdownWindow(timeseries.data.points, summary.maxDrawdown)
        : null,
    [summary, timeseries],
  )

  const detailPanelId = useId()
  const lossPanelId = useId()
  const lossReasonId = useId()
  const detailView = useDisclosure(detailPanelId)
  const lossView = useDisclosure(lossPanelId)

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

      {/*
        B04 T044: 자산가치 곡선과 최대 하락 구간 음영이 이 자리에 온다. '손실이 커진 때 보기'가 열려 있으면
        (`lossView.open`) 같은 `lossWindow`를 그래프에서 강조한다(FR-010).
      */}

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

      {/* 결과 화면의 주 모양 버튼은 비교 대상 고르기(B05 T050) 하나다. 여기 버튼은 모두 보조 모양이다. */}
      <div className={styles.actions}>
        {viewable ? (
          <Button type="button" variant="outline" {...detailView.toggleProps}>
            자세히 보기
          </Button>
        ) : null}
        {viewable ? (
          <Button
            type="button"
            variant="outline"
            disabled={lossWindow === null}
            aria-describedby={lossWindow === null ? lossReasonId : undefined}
            {...lossView.toggleProps}
          >
            손실이 커진 때 보기
          </Button>
        ) : null}
        <OtherConditionsButton config={detail.config} />
      </div>
      {viewable && lossWindow === null ? (
        <p id={lossReasonId} className={styles.hint}>
          {summary && isZeroValue(summary.maxDrawdown) ? LOSS_WINDOW_NONE : LOSS_WINDOW_UNAVAILABLE}
        </p>
      ) : null}

      {detailView.open ? (
        <DetailPanel id={detailPanelId} token={token} detail={detail} onClose={detailView.close} />
      ) : null}
      {lossView.open && lossWindow ? (
        <LossWindowView
          id={lossPanelId}
          token={token}
          lossWindow={lossWindow}
          items={detail.config.items}
          onClose={lossView.close}
        />
      ) : null}

      <p className={styles.source}>자료 출처: 한국거래소 통계정보</p>
    </article>
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

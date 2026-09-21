import { useId, useMemo } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { retryResult } from '@/api/result'
import { ComparisonKindBadge } from '@/components/result/ComparisonKindBadge'
import { CompareTargetPicker } from '@/components/result/CompareTargetPicker'
import { CompareTargetSlot } from '@/components/result/CompareTargetSlot'
import { PeriodMismatchDialog } from '@/components/result/PeriodMismatchDialog'
import { useCompareTarget } from '@/hooks/useCompareTarget'
import { AppHeader } from '@/components/navigation/AppHeader'
import { RecordOriginNotice } from '@/components/records/RecordOriginNotice'
import { strategyLabel } from '@/components/result/conditionLine'
import { DetailPanel } from '@/components/result/DetailPanel'
import { findDrawdownWindow } from '@/components/result/drawdownWindow'
import { EquityChart, type EquityChartSeries } from '@/components/result/EquityChart'
import { hasDrawableEquity } from '@/components/result/equityPoints'
import { isZeroValue } from '@/components/result/formatResult'
import {
  LOSS_WINDOW_NONE,
  LOSS_WINDOW_UNAVAILABLE,
  LossWindowView,
} from '@/components/result/LossWindowView'
import { IndexComparisonView } from '@/components/result/IndexComparisonView'
import { NextStepLinks } from '@/components/result/NextStepLinks'
import { ResultSummaryCard } from '@/components/result/ResultSummaryCard'
import { ResultStatusLine } from '@/components/result/ResultStatusLine'
import { ResultUnavailableNotice } from '@/components/result/ResultUnavailableNotice'
import { TargetRiskView } from '@/components/result/TargetRiskView'
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
 * <p>상태 한 문장(B06 T075)은 요약 카드 바로 아래에 두고, 비교(B05 T050)는 뒤 묶음이 이어 붙인다.
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
  const navigate = useNavigate()
  // 내 기록에서 열었으면(006 FR-006) 재계산하지 않았다는 한 줄을 덧붙인다. 다른 동작은 바꾸지 않는다.
  const [searchParams] = useSearchParams()
  const fromRecords = searchParams.get('from') === 'records'
  const completedThrough = completedRange(detail, timeseries)
  const compare = useCompareTarget(detail)
  const target = compare.compatibleTarget
  const pickerId = useId()
  const picker = useDisclosure(pickerId)
  const pickerButtonId = pickerId + '-trigger'
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
      {compare.returnTo ? <Link to={compare.returnTo}>원래 결과로 돌아가기</Link> : null}
      {fromRecords ? <RecordOriginNotice recordId={searchParams.get('recordId')} /> : null}

      {detail.status === 'QUEUED' || detail.status === 'RUNNING' ? (
        <p className={styles.status} role="status">
          {inFlightMessage(detail)}
        </p>
      ) : null}

      <ResultSummaryCard detail={detail} target={target} />

      <ResultStatusLine
        detail={detail}
        completedThrough={completedThrough}
        onRetry={
          detail.status === 'FAILED'
            ? async () => {
                const accepted = await retryResult(token)
                await navigate(`/results/${accepted.publicToken}`)
              }
            : undefined
        }
      />

      <ResultChartSlot
        detail={detail}
        timeseries={timeseries}
        target={
          target && compare.timeseries.status === 'loaded'
            ? {
                name: strategyLabel(target),
                points: compare.timeseries.data.points,
                maxDrawdown: target.summary?.maxDrawdown ?? null,
              }
            : null
        }
      />

      {summary ? (
        <p className={styles.sentence}>
          {selectSummarySentence({
            base: {
              name: strategyLabel(detail),
              netTotalReturn: summary.netTotalReturn,
              maxDrawdown: summary.maxDrawdown,
              totalTradingCost: summary.totalTradingCost,
            },
            target: target?.summary
              ? {
                  name: strategyLabel(target),
                  netTotalReturn: target.summary.netTotalReturn,
                  maxDrawdown: target.summary.maxDrawdown,
                  totalTradingCost: target.summary.totalTradingCost,
                }
              : null,
            partial: detail.partial ? { completedThrough } : null,
          })}
        </p>
      ) : null}

      {/* 결과 화면의 주 모양 버튼은 비교 대상 고르기(B05 T050) 하나다. 여기 버튼은 모두 보조 모양이다. */}
      <div className={styles.actions}>
        <Button
          id={pickerButtonId}
          type="button"
          disabled={!viewable || compare.targetState === 'REQUESTED'}
          {...picker.toggleProps}
        >
          비교 대상 고르기
        </Button>
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
      </div>
      {picker.open ? (
        <div id={pickerId}>
          <CompareTargetPicker
            base={detail}
            onClose={picker.close}
            onSelect={(choice) => {
              picker.close()
              compare.select(choice)
            }}
          />
        </div>
      ) : null}
      <ComparisonKindBadge kind={compare.comparisonKind} searchId={compare.searchId} />
      <CompareTargetSlot
        state={compare}
        onRetry={compare.retry}
        onClear={compare.clear}
        onPick={() => {
          if (!picker.open) picker.toggleProps.onClick()
        }}
      />
      {compare.mismatch === 'conditions' ? (
        <p role="status">
          섹터·금액·비용·자료 기준 또는 분석 시나리오가 달라 두 결과를 겹쳐 그리지 않았습니다. 기준
          결과에서 비교 대상을 다시 골라 주세요.
        </p>
      ) : null}
      {compare.mismatch === 'period' && !compare.period ? (
        <p role="status">
          평가 기간 또는 준비 기간이 달라 기준 결과만 표시합니다. 비교 대상을 다시 골라 주세요.
        </p>
      ) : null}
      {compare.mismatch === 'versions' && !compare.period ? (
        <p role="status">
          자료 또는 계산 엔진 기준이 달라 기준 결과만 표시합니다. 같은 현재 기준으로 다시 계산해
          비교할 수 있습니다.
        </p>
      ) : null}
      {compare.period ? (
        <PeriodMismatchDialog
          period={compare.period}
          busy={compare.recalculating}
          error={compare.dialogError}
          returnFocusId={pickerButtonId}
          onConfirm={() => void compare.confirmPeriod()}
          onCancel={compare.cancelPeriod}
        />
      ) : null}
      {viewable && lossWindow === null ? (
        <p id={lossReasonId} className={styles.hint}>
          {summary && isZeroValue(summary.maxDrawdown) ? LOSS_WINDOW_NONE : LOSS_WINDOW_UNAVAILABLE}
        </p>
      ) : null}

      {detailView.open ? (
        <DetailPanel
          id={detailPanelId}
          token={token}
          detail={detail}
          onClose={detailView.close}
          compare={
            target
              ? {
                  token: target.publicToken,
                  name: strategyLabel(target),
                  items: target.config.items,
                }
              : null
          }
        />
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

      <IndexComparisonView token={token} disabled={!viewable} />
      <TargetRiskView base={detail} disabled={!viewable} />
      <NextStepLinks
        config={detail.config}
        token={token}
        compareToken={compare.compareToken}
        compareReady={compare.targetState === 'READY'}
      />

      <p className={styles.source}>자료 출처: 한국거래소 통계정보</p>
    </article>
  )
}

/**
 * 그래프 자리(FR-001).
 *
 * <p>시계열을 받았을 때만 그린다. 받지 못했으면 빈 곡선 대신 받지 못한 사유를 한 문장으로 둔다 —
 * 빈 그래프는 "돌았고 비어 있었다"는 거짓이 된다. 받았더라도 자산가치 기록이 하나도 없으면(거래일을
 * 하나도 마치지 못하고 취소된 부분 결과) 같은 이유로 그리지 않고 그 사실을 알린다. 아직 계산 중이면
 * 그릴 것이 없으므로 자리를 비운다.
 *
 * <p>비교 대상 곡선은 B05가 이 컴포넌트의 `target`에 이어 붙인다.
 */
function ResultChartSlot({
  detail,
  timeseries,
  target,
}: {
  detail: PublicResultDetail
  timeseries: TimeseriesState
  target?: EquityChartSeries | null
}) {
  if (timeseries.status === 'unavailable') {
    return (
      <p className={styles.status}>그래프를 그릴 시계열을 받지 못했습니다. {timeseries.message}</p>
    )
  }
  if (timeseries.status !== 'loaded') return null
  if (!hasDrawableEquity(timeseries.data.points)) {
    return (
      <p className={styles.status}>
        받은 시계열에 자산가치 기록이 없어 그래프를 그리지 않았습니다.
      </p>
    )
  }

  const { summary } = detail

  return (
    <EquityChart
      target={target}
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

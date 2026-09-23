import { useId, useMemo } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Popover } from 'radix-ui'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { retryResult } from '@/api/result'
import { ComparisonKindBadge } from '@/components/result/ComparisonKindBadge'
import { CompareTargetPicker } from '@/components/result/CompareTargetPicker'
import comparisonStyles from '@/components/result/comparison.module.css'
import { CompareTargetSlot } from '@/components/result/CompareTargetSlot'
import { PeriodMismatchDialog } from '@/components/result/PeriodMismatchDialog'
import { useCompareTarget } from '@/hooks/useCompareTarget'
import { AppHeader } from '@/components/navigation/AppHeader'
import { RecordOriginNotice } from '@/components/records/RecordOriginNotice'
import { strategyLabel } from '@/components/result/conditionLine'
import { findDrawdownWindow } from '@/components/result/drawdownWindow'
import { EquityChart, type EquityChartSeries } from '@/components/result/EquityChart'
import { hasDrawableEquity } from '@/components/result/equityPoints'
import { SECTOR_SELECTION_PATH } from '@/components/result/prefillStrategySettings'
import { NextStepLinks } from '@/components/result/NextStepLinks'
import { ResultDetailTabs } from '@/components/result/ResultDetailTabs'
import { ResultConditionLine, ResultSummaryCard, ResultValuationNote } from '@/components/result/ResultSummaryCard'
import { ResultStatusLine } from '@/components/result/ResultStatusLine'
import { ResultUnavailableNotice } from '@/components/result/ResultUnavailableNotice'
import { selectSummarySentence } from '@/components/result/summarySentences'
import { Button } from '@/components/ui/button'
import { useDisclosure } from '@/hooks/useDisclosure'
import { useProgressSeries } from '@/hooks/useProgressSeries'
import { type TimeseriesState, hasViewableResult, useResult } from '@/hooks/useResult'
import type { PublicResultDetail, ResultTimeseriesPoint } from '@/types/result'
import styles from './ResultPage.module.css'

/**
 * 결과 첫 화면(`/results/:token`, spec 005 US1).
 *
 * <p>첫 화면에는 핵심 숫자 3개와 그래프를 먼저 보인다(FR-001, SC-001). 조건과 해석은 그래프 아래
 * 기본 요약 탭에 두고, 내부 계산 항목은 나열하지 않는다(FR-020).
 *
 * <p>로그인 없이 연다. 주소에는 공개 토큰만 있고 004의 내부 실행 식별자는 어디에도 없다(SC-009).
 *
 * <p>화면은 제목·핵심 숫자·그래프·탭 묶음(`ResultDetailTabs`, T098) 순이다.
 * 상태 문장과 해석은 기본으로 열린 '요약' 탭 안에 둔다. 다른 탭은 고를 때 그 자료를 받는다. 볼 결과가 없는 실행은 탭 없이 요약 내용만 그린다. 주 모양
 * 버튼은 '비교 대상 고르기' 하나고 '다른 조건으로 비교'(B07)는 보조 모양이다. 어느 쪽도 주소를 바꾸지
 * 않는다(SC-006).
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
  const recoveryDays = summary?.analytics?.recoveryDays
  const recoverySentence =
    recoveryDays === null
      ? '분석 기간 내 이전 고점을 회복하지 못했습니다.'
      : typeof recoveryDays === 'number' && recoveryDays > 0
        ? `이전 고점 회복까지 ${recoveryDays}거래일이 걸렸습니다.`
        : null

  const viewable = hasViewableResult(detail)
  // 계산이 도는 동안에만 구독한다. 끝난 결과에 걸면 아무것도 오지 않는 연결이 남는다.
  const inFlight = detail.status === 'QUEUED' || detail.status === 'RUNNING'
  const progressSeries = useProgressSeries(token, inFlight)
  // 그래프 음영(B04)과 '손실이 커진 때 보기'가 같은 구간을 쓴다. 요약과 어긋나면 `null`이다(R-05).
  const lossWindow = useMemo(
    () =>
      summary && timeseries.status === 'loaded'
        ? findDrawdownWindow(timeseries.data.points, summary.maxDrawdown)
        : null,
    [summary, timeseries],
  )

  // 차트 아래 탭의 요약 내용. 종료일 평가 안내도 여기로 내려 첫 화면을 간결하게 둔다.
  const summaryBlock = (
    <div className={styles.summaryStack}>
      <ResultConditionLine detail={detail} target={target} />
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
          {recoverySentence ? ` ${recoverySentence}` : null}
        </p>
      ) : null}
      <ResultValuationNote detail={detail} />
    </div>
  )

  return (
    <article className={styles.result} aria-labelledby="result-title">
      <header className={styles.intro}>
        {/* 처음 단계(섹터·비중 고르기)로 돌아가는 길. 전략 선택 화면의 뒤로 가기와 같은 모양이고, 행동이 아니라
            이동이라 버튼 줄에 두지 않는다. 브라우저 뒤로 가기가 아니라 경로를 직접 가리킨다 — 내 기록·공유
            주소로 들어온 사용자는 한 칸 뒤가 섹터 선택이 아니다. 고른 섹터·비중과 전략 설정은 탭 수명 저장소에
            남아 있어 돌아가도 비어 있지 않다. 현재 조건을 채워 전략 선택으로 넘기는 것은 '다른 조건으로
            비교'(B07)의 몫이다. */}
        <Link className={styles.backLink} to={SECTOR_SELECTION_PATH}>
          <ArrowLeft size={15} aria-hidden="true" />
          처음부터 다시 분석
        </Link>
        <h1 id="result-title" className={styles.title}>
          백테스트 결과
        </h1>
        <div className={styles.actions}>
          <Popover.Root
            open={picker.open}
            onOpenChange={(open) => {
              if (!open) picker.close()
            }}
          >
            <Popover.Anchor asChild>
              <Button
                id={pickerButtonId}
                type="button"
                disabled={!viewable || compare.targetState === 'REQUESTED'}
                {...picker.toggleProps}
              >
                비교 대상 고르기
              </Button>
            </Popover.Anchor>
            <Popover.Portal>
              <Popover.Content
                id={pickerId}
                className={comparisonStyles.popover}
                side="bottom"
                align="end"
                sideOffset={8}
                collisionPadding={16}
                aria-label="비교 대상 고르기"
                onCloseAutoFocus={(event) => {
                  event.preventDefault()
                  document.getElementById(pickerButtonId)?.focus()
                }}
              >
                <CompareTargetPicker
                  base={detail}
                  onClose={picker.close}
                  onSelect={(choice) => {
                    picker.close()
                    compare.select(choice)
                  }}
                />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
        </div>
      </header>
      {compare.returnTo ? <Link to={compare.returnTo}>원래 결과로 돌아가기</Link> : null}
      {fromRecords ? <RecordOriginNotice recordId={searchParams.get('recordId')} /> : null}

      {detail.status === 'QUEUED' || detail.status === 'RUNNING' ? (
        <p className={styles.status} role="status">
          {inFlightMessage(detail)}
        </p>
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
      <ResultSummaryCard detail={detail} />
      <ResultChartSlot
        detail={detail}
        timeseries={timeseries}
        progressPoints={progressSeries.points}
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
      {viewable ? (
        <ResultDetailTabs
          token={token}
          detail={detail}
          summary={summaryBlock}
          lossWindow={lossWindow}
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
      ) : (
        summaryBlock
      )}
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
 * 하나도 마치지 못하고 취소된 부분 결과) 같은 이유로 그리지 않고 그 사실을 알린다.
 *
 * <p><b>계산 중에는 진행 곡선을 그린다</b>(spec 004 R-05). 구독으로 받은 거래일별 점을 그대로 쓰므로
 * 그려진 값은 모두 워커가 실제로 계산한 것이다. 최종 시계열을 받으면 그쪽으로 바꾼다 — 같은 실행의
 * 같은 값이라 곡선이 이어진다. 한 점도 받지 못한 동안에는 그릴 것이 없으므로 자리를 비운다.
 *
 * <p><b>계산 중에는 하락 구간 음영과 대안 텍스트의 핵심 숫자를 붙이지 않는다.</b> 둘의 근거는 요약의
 * `maxDrawdown` 같은 확정된 값이고, 그 값은 실행이 끝나야 나온다. 중간 곡선으로 짐작해 칠하면 끝난 뒤
 * 음영이 다른 자리로 옮겨 간다.
 *
 * <p>비교 대상 곡선은 B05가 이 컴포넌트의 `target`에 이어 붙인다.
 */
function ResultChartSlot({
  detail,
  timeseries,
  progressPoints,
  target,
}: {
  detail: PublicResultDetail
  timeseries: TimeseriesState
  /** 계산 중 구독으로 받은 점. 최종 시계열이 오면 쓰이지 않는다 */
  progressPoints?: ResultTimeseriesPoint[]
  target?: EquityChartSeries | null
}) {
  if (timeseries.status === 'unavailable') {
    return (
      <p className={styles.status}>그래프를 그릴 시계열을 받지 못했습니다. {timeseries.message}</p>
    )
  }

  const { summary } = detail

  if (timeseries.status !== 'loaded') {
    const inProgress = progressPoints ?? []
    if (!hasDrawableEquity(inProgress)) return null
    return (
      <EquityChart
        altNumbers={null}
        base={{ name: strategyLabel(detail), points: inProgress, maxDrawdown: null }}
        initialCash={detail.config.initialCash}
      />
    )
  }

  if (!hasDrawableEquity(timeseries.data.points)) {
    return (
      <p className={styles.status}>
        받은 시계열에 자산가치 기록이 없어 그래프를 그리지 않았습니다.
      </p>
    )
  }

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

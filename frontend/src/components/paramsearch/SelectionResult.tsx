import { Info } from 'lucide-react'
import { CandidateSummaryCard } from './CandidateSummaryCard'
import { InlineHelp } from '@/components/common/InlineHelp'
import type {
  ParamSearchBaseline,
  ParamSearchConfig,
  ParamSearchSelection,
  SearchCandidateItem,
  SearchCounts,
  SelectionRule,
} from '@/types/paramSearch'
import {
  describeCandidateSetting,
  formatCount,
  formatPercent,
  sortMetricText,
} from '@/utils/paramSearchLabels'
import styles from './SelectionResult.module.css'

interface SelectionResultProps {
  selection: ParamSearchSelection
  selectionRule: SelectionRule | null
  baseline: ParamSearchBaseline | null
  config: ParamSearchConfig | null
  /** `selection.displayed`에 든 후보들. 서버가 준 순서를 그대로 쓴다. */
  candidates: SearchCandidateItem[]
  /** 전체 조합부터 화면 표시까지의 흐름을 짧게 보여 주는 실행 집계. */
  counts?: SearchCounts | null
  /** 표시 후보 조회가 끝났는지. 로딩 중인 빈 배열을 실제 빈 결과로 오해하지 않는다. */
  candidatesReady?: boolean
  /** 전체 실행 전, 지금까지 완료된 후보로 계속 갱신하는 잠정 선정인가. */
  provisional?: boolean
  onOpenDetail: (candidate: SearchCandidateItem) => void
  comparePicks: string[]
  onCompareToggle: (candidate: SearchCandidateItem) => void
}

/**
 * 표시 후보 집합(FR-018, SC-006).
 *
 * <p><b>이것은 순위표가 아니다.</b> 순위 번호·추천 문구·우승 배지를 넣지 않고, 대신 <b>무엇으로
 * 정렬했는지</b>를 밝힌다(plan 설계 제약 1·3, 원칙 III). 목록을 `ol`이 아니라 `ul`로 두는 것도
 * 같은 이유다 — 번호가 붙는 순간 "1위"가 생긴다.
 *
 * <p>충족한 후보가 표시 개수에 못 미치면 <b>그대로 적고 문턱을 낮춰 채우지 않는다</b>(원칙 VII).
 * 손실 제약을 걸지 않았으면 그 사실도 적는다 — 걸지 않은 필터를 통과했다고 보이면 안 된다(FR-013d).
 */
export function SelectionResult({
  selection,
  selectionRule,
  baseline,
  config,
  candidates,
  counts = null,
  candidatesReady = true,
  provisional = false,
  onOpenDetail,
  comparePicks,
  onCompareToggle,
}: SelectionResultProps) {
  const metric = sortMetricText(selectionRule?.sortMetric ?? 'NET_RETURN')

  // 후보가 모두 한 전략이면 이름을 목록 위에 한 번만 둔다 — 카드마다 되풀이하면 정작 후보끼리
  // 다른 설정값이 묻힌다. 여러 전략이 섞이면 카드가 각자 이름을 적어야 구분된다.
  const strategyNames = [
    ...new Set(candidates.map((candidate) => describeCandidateSetting(candidate, config).strategyName)),
  ]
  const sharedStrategy = strategyNames.length === 1 ? strategyNames[0] : null

  return (
    <section className={styles.section} aria-labelledby="selection-title">
      <header className={styles.header}>
        <div className={styles.headline}>
          <h2 id="selection-title">
            {provisional ? '현재까지 기준을 충족한 설정' : '기준을 충족한 설정'}
          </h2>
          <InlineHelp label="설정 선정 기준">
            {selection.drawdownFilterApplied ? (
              <p>
                기준 전략보다 가장 크게 줄었던 폭이 나쁘지 않은 후보를 먼저 남깁니다.
              </p>
            ) : (
              <p>이번 결과에는 손실 제약이 적용되지 않았습니다.</p>
            )}
            <p>
              인접 설정 안정성은 한 파라미터만 한 단계 다른 설정들과 비용 차감 후 수익률이
              얼마나 차이 나는지 계산한 평균입니다. 작을수록 주변 설정에서도 결과가
              일정합니다.
            </p>
            {selectionRule?.stabilityFilter && (
              <p>
                안정성이 좋은 후보 중 상위{' '}
                {formatCount(selectionRule.stabilityFilter.keepTopPercent)}%를 통과시킵니다. 이웃이
                없으면 계산할 수 없지만 안정성 기준에서 제외하지 않습니다.
              </p>
            )}
          </InlineHelp>
        </div>
        <p>
          {provisional
            ? `완료된 후보를 ${metric} 기준으로 다시 정렬합니다. 새 결과에 따라 순서가 바뀔 수 있습니다.`
            : `${metric} 기준으로 정렬한 집합입니다. 순위가 아닙니다.`}
          {selectionRule?.displayCount
            ? ` 최대 ${formatCount(selectionRule.displayCount)}개까지 보입니다.`
            : null}
        </p>
      </header>

      {provisional && (
        <p className={styles.loading} role="status" aria-live="polite">
          현재까지 계산된 결과입니다. 모든 후보가 끝나면 최종 선정으로 확정됩니다.
        </p>
      )}

      {counts && (
        <p className={styles.flow} aria-label="후보 선정 과정">
          <span>
            전체 조합 <strong>{formatCount(counts.expected)}개</strong>
          </span>
          <i aria-hidden="true">→</i>
          <span>
            실행 대상 <strong>{formatCount(counts.valid)}개</strong>
          </span>
          <i aria-hidden="true">→</i>
          <span>
            완료 <strong>{formatCount(counts.completed)}개</strong>
          </span>
          <i aria-hidden="true">→</i>
          <span>
            화면 표시 <strong>{formatCount(selection.displayed.length)}개</strong>
          </span>
        </p>
      )}

      <ul className={styles.notes}>
        {!provisional && selection.shortOfDisplayCount && selection.note && (
          <li>
            <Info size={14} aria-hidden="true" />
            {`${selection.note}. 기준을 낮춰 개수를 채우지 않습니다.`}
          </li>
        )}
        {!selection.drawdownFilterApplied && (
          <li>
            <Info size={14} aria-hidden="true" />
            손실 제약은 걸지 않았습니다. 기준 전략이 지정되지 않았거나 기준 실행이 끝나지
            않았습니다.
          </li>
        )}
        {selection.drawdownFilterApplied && baseline && (
          <li>
            <Info size={14} aria-hidden="true" />
            {`손실 제약 기준은 ${baseline.strategyName ?? baseline.strategyId}입니다`}
            {baseline.maxDrawdown
              ? ` (가장 크게 줄었던 폭 ${formatPercent(baseline.maxDrawdown)}).`
              : ' (기준 실행의 값이 아직 없습니다).'}
          </li>
        )}
      </ul>

      {!candidatesReady ? (
        <p className={styles.loading} role="status">
          선정된 설정을 불러오는 중입니다.
        </p>
      ) : candidates.length === 0 ? (
        <p className={styles.empty}>
          {provisional
            ? '아직 표시할 완료 후보가 없습니다. 계산이 끝난 후보가 생기면 여기에 바로 표시됩니다.'
            : '기준을 충족한 설정이 없습니다. 기준을 낮춰 채우지 않으므로 범위를 바꿔 다시 실행해 주세요.'}
        </p>
      ) : (
        <>
          {sharedStrategy && (
            <p className={styles.strategy}>
              <span>전략</span>
              <strong>{sharedStrategy}</strong>
              <span className={styles.strategyNote}>
                아래 후보는 모두 이 전략이며 설정값만 다릅니다.
              </span>
            </p>
          )}

          <ul className={styles.cards}>
            {candidates.map((candidate) => (
              <li key={candidate.candidateId}>
                <CandidateSummaryCard
                  candidate={candidate}
                  config={config}
                  showStrategy={sharedStrategy === null}
                  onOpenDetail={() => onOpenDetail(candidate)}
                  compareChecked={comparePicks.includes(candidate.candidateId)}
                  onCompareToggle={() => onCompareToggle(candidate)}
                />
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

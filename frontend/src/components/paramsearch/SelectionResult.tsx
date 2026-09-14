import { Info } from 'lucide-react'
import { CandidateSummaryCard } from './CandidateSummaryCard'
import type {
  ParamSearchBaseline,
  ParamSearchConfig,
  ParamSearchSelection,
  SearchCandidateItem,
  SelectionRule,
} from '@/types/paramSearch'
import { formatCount, formatPercent, sortMetricText } from '@/utils/paramSearchLabels'
import styles from './SelectionResult.module.css'

interface SelectionResultProps {
  selection: ParamSearchSelection
  selectionRule: SelectionRule | null
  baseline: ParamSearchBaseline | null
  config: ParamSearchConfig | null
  /** `selection.displayed`에 든 후보들. 서버가 준 순서를 그대로 쓴다. */
  candidates: SearchCandidateItem[]
  onOpenDetail: (candidateId: string) => void
  comparePicks: string[]
  onCompareToggle: (candidateId: string) => void
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
  onOpenDetail,
  comparePicks,
  onCompareToggle,
}: SelectionResultProps) {
  const metric = sortMetricText(selectionRule?.sortMetric ?? 'NET_RETURN')

  return (
    <section className={styles.section} aria-labelledby="selection-title">
      <header className={styles.header}>
        <h2 id="selection-title">기준을 충족한 설정</h2>
        <p>
          {`${metric} 기준으로 정렬한 집합입니다. 순위가 아닙니다.`}
          {selectionRule?.displayCount
            ? ` 최대 ${formatCount(selectionRule.displayCount)}개까지 보입니다.`
            : null}
        </p>
      </header>

      <ul className={styles.notes}>
        {selection.shortOfDisplayCount && selection.note && (
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

      {candidates.length === 0 ? (
        <p className={styles.empty}>
          기준을 충족한 설정이 없습니다. 기준을 낮춰 채우지 않으므로 범위를 바꿔 다시 실행해
          주세요.
        </p>
      ) : (
        <ul className={styles.cards}>
          {candidates.map((candidate) => (
            <li key={candidate.candidateId}>
              <CandidateSummaryCard
                candidate={candidate}
                config={config}
                onOpenDetail={() => onOpenDetail(candidate.candidateId)}
                compareChecked={comparePicks.includes(candidate.candidateId)}
                onCompareToggle={() => onCompareToggle(candidate.candidateId)}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

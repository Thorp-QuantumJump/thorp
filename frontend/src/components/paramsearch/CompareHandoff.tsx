import { ArrowUpRight, X } from 'lucide-react'
import type {
  ParamSearchBaseline,
  ParamSearchConfig,
  SearchCandidateItem,
} from '@/types/paramSearch'
import { describeCandidateSetting, formatCount } from '@/utils/paramSearchLabels'
import styles from './CompareHandoff.module.css'

interface CompareHandoffProps {
  picks: SearchCandidateItem[]
  config: ParamSearchConfig | null
  baseline: ParamSearchBaseline | null
  onRemove: (candidateId: string) => void
  onClear: () => void
  /**
   * 005 비교 화면으로 넘기는 동작. **없으면 진입 지점만 보이고 눌리지 않는다** —
   * 005가 아직 없으므로 지금이 그 상태다.
   */
  onOpenCompare?: (candidateIds: string[]) => void
}

/**
 * 고른 후보를 [005] 비교 대상으로 넘기는 자리(FR-019).
 *
 * <p><b>'자동 탐색 후보 비교'임을 기본 설정 비교와 구분해 표시한다.</b> 둘을 같은 화면 이름으로
 * 보이면 사용자가 "내가 고른 설정끼리의 비교"와 "탐색이 고른 설정과의 비교"를 섞는다.
 *
 * <p>비교 기준은 바꾸지 않는다 — 사용자 고정 비중과 동일 비중 기준을 그대로 둔다. 탐색이 고른
 * 후보가 기준 자리에 들어가면 비교가 자기 자신을 기준으로 삼게 된다.
 *
 * <p>005가 붙기 전까지는 <b>진입 지점과 표시만</b> 만든다. 눌리지 않는 버튼을 이유 없이 두지 않고
 * 무엇이 없어서 막혔는지 적는다.
 */
export function CompareHandoff({
  picks,
  config,
  baseline,
  onRemove,
  onClear,
  onOpenCompare,
}: CompareHandoffProps) {
  if (picks.length === 0) return null

  const baselineName = baseline?.strategyName ?? baseline?.strategyId ?? null

  return (
    <aside className={styles.tray} aria-labelledby="compare-handoff-title">
      <header className={styles.header}>
        <div>
          <span className={styles.kind}>자동 탐색 후보 비교</span>
          <h2 id="compare-handoff-title">
            {`비교에 담은 설정 ${formatCount(picks.length)}개`}
          </h2>
        </div>
        <button type="button" className={styles.clear} onClick={onClear}>
          모두 비우기
        </button>
      </header>

      <ul className={styles.picks}>
        {picks.map((candidate) => {
          const { strategyName, parameterText } = describeCandidateSetting(candidate, config)
          return (
            <li key={candidate.candidateId}>
              <span className={styles.pickText}>
                <strong>{strategyName}</strong>
                {` · ${parameterText}`}
              </span>
              <button
                type="button"
                onClick={() => onRemove(candidate.candidateId)}
                aria-label={`${strategyName} ${parameterText} 비교에서 빼기`}
              >
                <X size={13} aria-hidden="true" />
              </button>
            </li>
          )
        })}
      </ul>

      <p className={styles.note}>
        {baselineName
          ? `비교 기준은 ${baselineName}과 사용자 고정 비중을 그대로 씁니다. 탐색이 고른 후보를 기준으로 바꾸지 않습니다.`
          : '비교 기준은 사용자 고정 비중과 동일 비중을 그대로 씁니다.'}
      </p>

      {onOpenCompare ? (
        <button
          type="button"
          className={styles.open}
          onClick={() => onOpenCompare(picks.map((candidate) => candidate.candidateId))}
        >
          비교 화면에서 보기
          <ArrowUpRight size={15} aria-hidden="true" />
        </button>
      ) : (
        <p className={styles.blocked}>
          비교 화면이 아직 없어 넘기지 못합니다. 담은 설정은 이 화면에서 그대로 유지됩니다.
        </p>
      )}
    </aside>
  )
}

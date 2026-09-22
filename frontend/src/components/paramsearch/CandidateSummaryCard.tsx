import { CandidateReason } from './CandidateReason'
import type { ParamSearchConfig, SearchCandidateItem } from '@/types/paramSearch'
import {
  describeCandidateParameters,
  describeCandidateSetting,
  formatPercent,
  formatWon,
} from '@/utils/paramSearchLabels'
import styles from './CandidateSummaryCard.module.css'

interface CandidateSummaryCardProps {
  candidate: SearchCandidateItem
  /** 축 라벨·단위·전략 이름을 읽을 곳. 없으면 축 이름을 그대로 쓴다. */
  config: ParamSearchConfig | null
  /** 상세를 여는 동작. 주면 '상세 보기' 버튼이 생긴다. */
  onOpenDetail?: () => void
  /** 비교에 담기. 둘 다 주어야 체크 상자가 생긴다. */
  compareChecked?: boolean
  onCompareToggle?: () => void
  /**
   * 카드에 전략 이름을 적을지.
   *
   * 한 묶음의 후보가 모두 같은 전략이면 목록이 같은 이름을 카드 수만큼 되풀이한다 — 그때는 이름을
   * 목록 위에 한 번만 두고 카드는 설정값에 자리를 내준다. 여러 전략이 섞이면 카드마다 필요하다.
   */
  showStrategy?: boolean
}

/**
 * 표시 후보 하나의 첫 화면(FR-018).
 *
 * <p><b>세 숫자만 먼저 보인다</b> — 비용 차감 후 수익·가장 크게 줄었던 폭·거래비용. 인접 설정
 * 안정성·선정 이유·탈락 사유는 상세에 둔다.
 *
 * <p><b>계산하지 않은 값을 "0%"로 그리지 않는다.</b> 값이 없으면 자리를 비우고 <b>왜 없는지</b>를
 * 함께 적는다 — 제외·실패·미실행과 "수익이 0이었다"는 전혀 다른 사실이다(FR-011, 원칙 VI).
 *
 * <p><b>설정값은 축마다 라벨과 값을 나눠 보인다.</b> 후보끼리 다른 것은 설정값 하나뿐이라, 한 줄로
 * 이어 붙이면 어느 축이 달라졌는지 읽어 내는 데 시간이 걸린다.
 *
 * <p>순위 번호·추천 문구·우승 배지를 넣지 않는다(plan 설계 제약 1, 원칙 III).
 */
export function CandidateSummaryCard({
  candidate,
  config,
  onOpenDetail,
  compareChecked,
  onCompareToggle,
  showStrategy = true,
}: CandidateSummaryCardProps) {
  const { strategyName } = describeCandidateSetting(candidate, config)
  const parameters = describeCandidateParameters(candidate, config)
  const netReturn = formatPercent(candidate.netReturn, { sign: true })
  const maxDrawdown = formatPercent(candidate.maxDrawdown)
  const tradingCost = formatWon(candidate.tradingCost)
  const hasNumbers = netReturn !== null || maxDrawdown !== null || tradingCost !== null
  const showCompare = compareChecked !== undefined && onCompareToggle !== undefined
  const compareId = `compare-${candidate.candidateId}`
  // 결과가 없는 후보를 비교에 담으면 비교 화면이 빈 자리를 그려야 한다. 담기지 않게 막는다.
  const comparable = candidate.status === 'COMPLETED'

  return (
    <article className={styles.card}>
      <header className={styles.header}>
        {showStrategy && <span className={styles.strategy}>{strategyName}</span>}
        {parameters.length === 0 ? (
          <p className={styles.noParameters}>설정 값 없음</p>
        ) : (
          <dl className={styles.settings}>
            {parameters.map((parameter) => (
              <div key={parameter.name}>
                <dt>{parameter.label}</dt>
                <dd>{parameter.valueText}</dd>
              </div>
            ))}
          </dl>
        )}
      </header>

      {hasNumbers ? (
        <dl className={styles.metrics}>
          <div>
            <dt>비용 차감 후 수익</dt>
            <dd data-tone={toneOf(candidate.netReturn)}>
              {netReturn ?? <span className={styles.absent}>값 없음</span>}
            </dd>
          </div>
          <div>
            <dt>가장 크게 줄었던 폭</dt>
            <dd data-tone="negative">
              {maxDrawdown ?? <span className={styles.absent}>값 없음</span>}
            </dd>
          </div>
          <div>
            <dt>거래비용</dt>
            <dd>{tradingCost ?? <span className={styles.absent}>값 없음</span>}</dd>
          </div>
        </dl>
      ) : (
        // 세 숫자가 모두 없으면 표를 그리지 않는다. 빈 칸 세 개보다 사유 한 줄이 정확하다.
        <CandidateReason candidate={candidate} />
      )}

      {(showCompare || onOpenDetail) && (
        <footer className={styles.actions}>
          {showCompare && (
            <span className={styles.compare}>
              <input
                id={compareId}
                type="checkbox"
                checked={compareChecked}
                onChange={onCompareToggle}
                disabled={!comparable}
              />
              <label htmlFor={compareId}>
                {comparable ? '비교에 담기' : '결과가 없어 담을 수 없습니다'}
              </label>
            </span>
          )}
          {onOpenDetail && (
            <button type="button" className={styles.detailButton} onClick={onOpenDetail}>
              상세 보기
            </button>
          )}
        </footer>
      )}
    </article>
  )
}

/** 색은 보조 수단이다 — 부호가 이미 값에 들어 있다(design-system 2.3). */
function toneOf(value: string | null): 'positive' | 'negative' | 'neutral' {
  if (value === null) return 'neutral'
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed === 0) return 'neutral'
  return parsed > 0 ? 'positive' : 'negative'
}

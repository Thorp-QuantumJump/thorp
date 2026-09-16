import type { StrategyView } from '@/types/strategyRegistry'
import styles from './StrategyCard.module.css'

/**
 * 워밍업과 최소 평가 기간을 함께 본 판정(FR-011, FR-011a).
 *
 * <p><b>이 카드는 판정하지 않는다.</b> 계산에는 001이 돌려주는 공통 평가 가능 범위와 사용자가 고른
 * 최소 평가 기간이 필요하고, 그 둘은 카드가 아는 값이 아니다. 카드는 결과와 문장을 받아 보이기만
 * 한다.
 */
export type WarmupVerdict =
  /** 워밍업을 채우고도 평가할 구간이 충분히 남는다. */
  | { state: 'ok' }
  /** 고를 수 있으나 평가 구간이 줄어든다. 줄어든 사실을 문장으로 알린다. */
  | { state: 'reduced'; message: string }
  /** 워밍업조차 채우지 못하거나 남는 기간이 최소 평가 기간에 못 미친다. */
  | { state: 'blocked'; message: string }

interface StrategyCardProps {
  strategy: StrategyView
  selected: boolean
  /** 워밍업 판정. 계산은 호출자가 하고 카드는 결과만 받는다. */
  warmup: WarmupVerdict
  /** 고를 수 있는 카드를 눌렀을 때만 불린다. */
  onSelect: () => void
}

/**
 * 전략 카드 하나(FR-001).
 *
 * <p><b>이름과 한 줄 설명만 보인다.</b> 축 라벨·기본값·허용 범위는 등록표 응답에 함께 실려 오지만
 * 이 카드는 그것을 그리지 않는다 — 첫 화면의 단순함이 이 제품의 설계 원칙이고, 세부 설정은
 * '상세 설정'에서 연다.
 *
 * <p><b>순위·추천 문구·우승 배지를 두지 않는다.</b> 다섯 방식이 퀀트 알고리즘 전체이거나 그들
 * 사이에 우열이 있다는 인상을 주는 표시를 넣지 않는다(FR-001).
 *
 * <p><b>고를 수 없는 카드도 초점을 받는다.</b> `disabled` 대신 `aria-disabled`를 쓰는 까닭이다 —
 * `disabled`를 걸면 키보드 사용자가 그 카드에 닿지 못해 <b>왜</b> 고를 수 없는지 읽을 방법이
 * 사라진다. 눌러도 선택되지 않게 막는 것은 처리부에서 한다(NFR-001).
 *
 * <p><b>상태를 색으로만 알리지 않는다.</b> 선택 불가와 기간 축소는 문장으로 함께 전달하고,
 * 스크린리더에는 `aria-pressed`·`aria-disabled`·`aria-describedby`로 전달한다(NFR-002).
 */
export function StrategyCard({ strategy, selected, warmup, onSelect }: StrategyCardProps) {
  // 고를 수 없는 이유는 둘이고 출처가 다르다. 등록표가 판정한 유효성 제약과, 화면이 계산한
  // 워밍업 판정이다. 둘 다 걸렸으면 둘 다 보인다 — 하나만 보이면 한 가지를 고쳐도 여전히
  // 고를 수 없는 카드를 사용자가 마주하게 된다.
  const reasons: { key: string; text: string }[] = []
  if (!strategy.selectable && strategy.unselectableReason) {
    reasons.push({ key: 'registry', text: strategy.unselectableReason })
  }
  if (warmup.state === 'blocked' || warmup.state === 'reduced') {
    reasons.push({ key: 'warmup', text: warmup.message })
  }

  const selectable = strategy.selectable && warmup.state !== 'blocked'
  const noticeId = reasons.length > 0 ? `strategy-card-notice-${strategy.strategyId}` : undefined
  const descriptionId = `strategy-card-description-${strategy.strategyId}`
  const describedBy = noticeId ? `${descriptionId} ${noticeId}` : descriptionId

  return (
    <button
      type="button"
      className={styles.card}
      // aria-disabled 는 "지금은 누를 수 없다"를 알리면서 초점은 남긴다.
      aria-disabled={!selectable}
      aria-pressed={selectable ? selected : undefined}
      aria-describedby={describedBy}
      data-selected={selected ? 'true' : undefined}
      data-selectable={selectable ? 'true' : 'false'}
      data-warmup={warmup.state}
      onClick={() => {
        if (selectable) onSelect()
      }}
    >
      <span className={styles.name}>{strategy.name}</span>
      <span id={descriptionId} className={styles.description}>
        {strategy.description}
      </span>

      {reasons.length > 0 && (
        <span id={noticeId} className={styles.notice}>
          {reasons.map((reason) => (
            <span key={reason.key} className={styles.reason} data-kind={reason.key}>
              {/* 색을 읽지 못해도 뜻이 남도록 상태를 낱말로 먼저 적는다. */}
              <span className={styles.reasonLabel}>
                {selectable ? '참고' : '고를 수 없음'}
              </span>
              {reason.text}
            </span>
          ))}
        </span>
      )}
    </button>
  )
}

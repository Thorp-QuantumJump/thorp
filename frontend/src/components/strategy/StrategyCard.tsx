import type { StrategyView } from '@/types/strategyRegistry'
import styles from './StrategyCard.module.css'

/**
 * 워밍업과 최소 평가 기간을 함께 본 판정(FR-011, FR-011a).
 *
 * <p><b>이 카드는 판정하지 않는다.</b> 계산에는 001이 돌려주는 공통 평가 가능 범위와 사용자가 고른
 * 최소 평가 기간이 필요하고, 그 둘은 카드가 아는 값이 아니다. 카드는 결과와 문장을 받아 보이기만
 * 한다.
 *
 * <p><b>판정하지 못한 상태에 `ok`를 쓰지 않는다.</b> 그 둘은 다른 사실이고, 접으면 화면이 하지 않은
 * 판정의 결과를 말하게 된다(원칙 IV). 001의 평가 기간 조회가 아직 붙지 않은 동안 호출자는
 * `unknown`을 넘긴다.
 */
export type WarmupVerdict =
  /** 워밍업을 채우고도 평가할 구간이 충분히 남는다. */
  | { state: 'ok' }
  /** 고를 수 있으나 평가 구간이 줄어든다. 줄어든 사실을 문장으로 알린다. */
  | { state: 'reduced'; message: string }
  /** 워밍업조차 채우지 못하거나 남는 기간이 최소 평가 기간에 못 미친다. */
  | { state: 'blocked'; message: string }
  /** 판정에 필요한 값을 아직 받지 못했다. **고를 수 없다는 뜻이 아니다.** */
  | { state: 'unknown'; message: string }

interface StrategyCardProps {
  strategy: StrategyView
  selected: boolean
  /** 워밍업 판정. 계산은 호출자가 하고 카드는 결과만 받는다. */
  warmup: WarmupVerdict
  /** 고를 수 있는 카드를 눌렀을 때만 불린다. */
  onSelect: () => void
}

/** 계약이 이유를 싣기로 해 놓고 싣지 않았을 때 쓰는 문장. 이유를 지어내지 않는다. */
const REASON_MISSING = '고를 수 없는 이유를 확인하지 못했습니다.'

type CardNotice = { key: string; label: string; text: string }

/**
 * 카드에 붙일 안내를 모은다.
 *
 * <p>고를 수 없는 이유는 둘이고 출처가 다르다. 등록표가 판정한 유효성 제약과, 화면이 계산한 워밍업
 * 판정이다. <b>둘 다 걸렸으면 둘 다 보인다</b> — 하나만 보이면 그것을 고쳐도 여전히 고를 수 없는
 * 카드를 사용자가 마주하게 된다.
 */
function collectNotices(strategy: StrategyView, warmup: WarmupVerdict): CardNotice[] {
  const notices: CardNotice[] = []

  if (!strategy.selectable) {
    // 계약 §1은 `selectable`이 false면 `unselectableReason`을 반드시 싣는다. 비어 있다면 그것은
    // 계약 위반이고, **화면이 숨겨서 넘기지 않는다**(`api/sectors.ts`가 대표 ETF가 빠진 항목을 두고
    // 세운 관례와 같다). 다만 전략 하나의 결함으로 등록표 전체를 버리지는 않는다 — 나머지 전략은
    // 정상인데 함께 막으면 FR-018이 막으려던 상황보다 넓게 막게 된다.
    notices.push({
      key: 'registry',
      label: '고를 수 없음',
      text: strategy.unselectableReason ?? REASON_MISSING,
    })
  }

  if (warmup.state === 'blocked') {
    notices.push({ key: 'warmup', label: '고를 수 없음', text: warmup.message })
  } else if (warmup.state === 'reduced') {
    notices.push({ key: 'warmup', label: '참고', text: warmup.message })
  } else if (warmup.state === 'unknown') {
    notices.push({ key: 'warmup', label: '확인하지 못함', text: warmup.message })
  }

  return notices
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
 * <p><b>상태를 색으로만 알리지 않는다.</b> 선택 불가와 기간 축소는 낱말과 문장으로 함께 전달하고,
 * 스크린리더에는 `aria-pressed`·`aria-disabled`·`aria-describedby`로 전달한다(NFR-002).
 */
export function StrategyCard({ strategy, selected, warmup, onSelect }: StrategyCardProps) {
  const notices = collectNotices(strategy, warmup)

  // 워밍업을 판정하지 못한 것은 고를 수 없다는 뜻이 아니다. 등록표가 이미 내린 판정을 그대로 두고,
  // 판정하지 못했다는 사실만 문장으로 알린다. 실행 직전의 최종 판정은 서버가 다시 한다(FR-006).
  const selectable = strategy.selectable && warmup.state !== 'blocked'
  const noticeId = notices.length > 0 ? `strategy-card-notice-${strategy.strategyId}` : undefined
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

      {notices.length > 0 && (
        <span id={noticeId} className={styles.notice}>
          {notices.map((notice) => (
            <span key={notice.key} className={styles.reason} data-kind={notice.key}>
              {/* 색을 읽지 못해도 뜻이 남도록 상태를 낱말로 먼저 적는다. */}
              <span className={styles.reasonLabel}>{notice.label}</span>
              {notice.text}
            </span>
          ))}
        </span>
      )}
    </button>
  )
}

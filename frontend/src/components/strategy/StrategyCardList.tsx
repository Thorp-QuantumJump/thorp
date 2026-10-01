import { type KeyboardEvent, useEffect, useRef } from 'react'
import { RegistryUnavailableNotice } from './RegistryUnavailableNotice'
import type { SharedNotice, SharedNoticeRef, WarmupVerdict } from './cardNotices'
import { StrategyCard } from './StrategyCard'
import type { RegistryResponse, StrategyView } from '@/types/strategyRegistry'
import styles from './StrategyCardList.module.css'

/**
 * 등록표 조회의 세 가지 결과. 화면을 그리는 분기는 예외가 아니라 상태다.
 *
 * <p>실패했다가 다시 불러오는 중에는 <code>failed</code>에 <code>retrying</code>을 세운다.
 * <code>loading</code>으로 되돌리면 안내가 사라졌다가 다시 나타나고, 그때마다
 * <code>role="alert"</code>가 다시 읽혀 초점도 함께 튄다.
 */
export type RegistryViewState =
  /** 첫 조회 중이다. */
  | { status: 'loading' }
  | { status: 'loaded'; registry: RegistryResponse }
  | {
      status: 'failed'
      /** 서버가 보낸 문장. 없으면 안내가 기본 문구를 쓴다. */
      message?: string
      /** 지금까지 실패한 횟수. 둘 이상이면 재시도 뒤의 실패다. */
      failureCount: number
      retrying: boolean
    }

interface StrategyCardListProps {
  state: RegistryViewState
  selectedStrategyId: string | null
  onSelect: (strategyId: string) => void
  onRetry: () => void
  /**
   * 전략 하나의 워밍업 판정(FR-011).
   *
   * **기본 설정 기준의 `warmupTradingDays`로 판정한다.** 설정을 바꾼 뒤의 재판정은 전략 설정이
   * 소유하며 이 목록의 일이 아니다. 판정에는 001의 공통 평가 가능 범위와 사용자가 고른 최소 평가
   * 기간이 함께 필요하므로, 그 둘을 아는 호출자가 계산해 넘긴다.
   *
   * **선택 항목으로 두지 않는다.** 기본값을 `ok`로 두면 호출자가 배선을 빠뜨려도 모든 카드가
   * 고를 수 있는 상태로 정상처럼 그려지고, FR-011의 판정이 조용히 사라진다. 아직 판정할 수 없는
   * 동안에는 `unknown`을 돌려주어 그 사실이 화면에 남게 한다.
   */
  judgeWarmup: (strategy: StrategyView) => WarmupVerdict
  /**
   * 한 곳에 모아 적은 사유(`collectSharedNotices`). **문장은 이 목록이 그리지 않는다** — 화면 머리의 제목 아래에서
   * `StrategyNoticeList`가 적고, 카드는 번호와 `id`로 그 문장을 가리킨다. 같은 결과를 두 자리에 넘겨야 번호와
   * 문장이 어긋나지 않는다.
   */
  notices: readonly SharedNotice[]
  /** 카드에 마우스를 올리면 보일 그 전략의 평가 가능 범위. 모르면 `null`이고, 주지 않으면 띄우지 않는다 */
  rangeOf?: (strategy: StrategyView) => { start: string; end: string } | null
}

/**
 * 자리 표시 카드의 개수다.
 *
 * **전략이 몇인지 뜻하지 않는다.** 전략의 수는 등록표가 정하고 화면은 응답을 받기 전까지 그것을
 * 모른다(FR-005). 불러오는 동안 빈자리의 크기를 보이려는 값일 뿐이며 스크린리더에는 읽히지 않는다.
 */
const PLACEHOLDER_COUNT = 3

/** 화살표 키가 초점을 옮기는 방향. 목록이 가로로 늘어서도 좁은 폭에서는 접히므로 위아래와 좌우를 같게 다룬다. */
const FOCUS_STEP: Record<string, number> = {
  ArrowDown: 1,
  ArrowRight: 1,
  ArrowUp: -1,
  ArrowLeft: -1,
}

/**
 * 전략 선택 목록(FR-001, FR-018).
 *
 * <p><b>라디오 그룹이다.</b> 다섯 중 하나를 고르는 일이므로 `radiogroup`·`radio`로 알리고, 탭 한
 * 번으로 그룹에 들어와 화살표로 항목을 옮겨 다닌다(roving tabindex). 전략이 늘어도 다음 영역까지
 * 가는 탭 횟수가 늘지 않는다.
 *
 * <p><b>화살표는 초점만 옮기고 고르지는 않는다.</b> 표준 라디오는 옮기면서 고르지만, 이 목록에는
 * 고를 수 없는 항목이 섞여 있어 옮기기만 해도 선택이 바뀌면 막힌 항목 위에서 선택이 사라진 것처럼
 * 보인다. 고르는 것은 Enter·Space가 한다(NFR-001).
 *
 * <p><b>응답의 <code>strategies</code> 순서를 그대로 쓴다.</b> 화면이 다시 정렬하지 않는다 —
 * 항목의 차례는 등록표가 소유하는 표시 규칙이고, 화면이 순서를 손대면 그것이 전략 지식의 중복이다.
 *
 * <p>조회 중에는 항목 전부가 선택 불가인 자리 표시다. 실패하면 목록 대신
 * <code>RegistryUnavailableNotice</code>를 두고 <b>임시 값이나 예비 등록표로 대신 동작하지 않는다.</b>
 *
 * <p><b>고른 전략의 상세는 이 목록이 그리지 않는다.</b> 목록 사이에 상세를 끼워 넣으면 고를 때마다
 * 아래 항목이 밀려 내려가 견주던 자리를 잃는다. 상세는 목록 아래 카드가 맡는다.
 *
 * <p><b>사유는 카드 밖 한 곳에 모아 적는다.</b> 고른 섹터가 적으면 상한 규칙 하나가 여러 전략을 함께 막는데,
 * 그 문장을 카드마다 되풀이하면 무엇 때문에 막혔는지보다 경고가 많다는 인상만 남는다. 카드 한 장에만 걸린 사유도
 * 같은 자리에 모아, 사유가 있는 카드만 높아져 줄이 들쭉날쭉하지 않게 한다. 카드는 이름 옆에 번호만 보이고,
 * 보조기술에는 카드의 설명으로 그 문장이 그대로 읽힌다(`aria-describedby`). 문장은 화면 머리의 제목 아래에
 * 적힌다(`StrategyNoticeList`, 2026-10-01).
 */
export function StrategyCardList({
  state,
  selectedStrategyId,
  onSelect,
  onRetry,
  judgeWarmup,
  notices,
  rangeOf,
}: StrategyCardListProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const recoveredFromFailure = useRef(false)

  useEffect(() => {
    if (state.status === 'failed') {
      recoveredFromFailure.current = true
      return
    }
    // 실패를 겪은 뒤 목록이 돌아왔을 때만 초점을 옮긴다. 첫 진입에는 옮기지 않는다.
    if (state.status === 'loaded' && recoveredFromFailure.current) {
      recoveredFromFailure.current = false
      listRef.current?.focus()
    }
  }, [state.status])

  if (state.status === 'failed') {
    return (
      <RegistryUnavailableNotice
        message={state.message}
        onRetry={onRetry}
        retrying={state.retrying}
        failureCount={state.failureCount}
      />
    )
  }

  if (state.status === 'loading') {
    // 알림 문장은 격자 밖에 둔다. 격자 안에 두면 문장이 카드 한 칸을 차지해 빈 카드처럼 읽힌다.
    return (
      <div className={styles.wrapper}>
        <p className={styles.loading} role="status">
          전략 설정을 불러오는 중입니다.
        </p>
        <div className={styles.list} aria-hidden="true">
          {Array.from({ length: PLACEHOLDER_COUNT }, (_, index) => (
            <div key={index} className={styles.placeholder} />
          ))}
        </div>
      </div>
    )
  }

  const strategies = state.registry.strategies
  const verdicts = strategies.map((strategy) => judgeWarmup(strategy))

  const shared = new Map<string, SharedNoticeRef>(
    notices.map((notice) => [notice.text, notice]),
  )

  const selectedIndex = strategies.findIndex(
    (strategy) => strategy.strategyId === selectedStrategyId,
  )
  // 고른 것이 없으면 첫 항목이 탭 차례를 받는다. 그래야 그룹에 들어올 문이 하나는 열려 있다.
  const tabStopIndex = selectedIndex >= 0 ? selectedIndex : 0

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const step = FOCUS_STEP[event.key]
    if (step === undefined) return
    const radios = listRef.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]')
    if (!radios || radios.length === 0) return
    const current = [...radios].indexOf(document.activeElement as HTMLButtonElement)
    if (current < 0) return
    event.preventDefault()
    // 끝에서 반대쪽으로 넘어간다. 목록이 짧아 되돌아가는 편이 끝에 멈추는 것보다 빠르다.
    radios[(current + step + radios.length) % radios.length].focus()
  }

  return (
    <div className={styles.wrapper}>
      <div
        ref={listRef}
        className={styles.list}
        // 재시도에 성공했을 때 초점을 받을 자리다. 탭 차례에는 들어가지 않는다.
        tabIndex={-1}
        role="radiogroup"
        aria-label="전략 고르기"
        onKeyDown={handleKeyDown}
      >
        {strategies.map((strategy, index) => (
          <StrategyCard
            key={strategy.strategyId}
            strategy={strategy}
            selected={selectedStrategyId === strategy.strategyId}
            warmup={verdicts[index]}
            onSelect={() => onSelect(strategy.strategyId)}
            tabIndex={index === tabStopIndex ? 0 : -1}
            shared={shared}
            range={rangeOf?.(strategy)}
          />
        ))}
      </div>
    </div>
  )
}

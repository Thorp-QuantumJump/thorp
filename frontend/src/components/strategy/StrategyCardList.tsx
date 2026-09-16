import { useEffect, useRef } from 'react'
import { RegistryUnavailableNotice } from './RegistryUnavailableNotice'
import { StrategyCard, type WarmupVerdict } from './StrategyCard'
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
   * **기본 설정 기준의 `warmupTradingDays`로 판정한다.** 설정을 바꾼 뒤의 재판정은 상세 설정이
   * 소유하며 이 목록의 일이 아니다. 판정에는 001의 공통 평가 가능 범위와 사용자가 고른 최소 평가
   * 기간이 함께 필요하므로, 그 둘을 아는 호출자가 계산해 넘긴다. 주지 않으면 전부 `ok`다.
   */
  judgeWarmup?: (strategy: StrategyView) => WarmupVerdict
}

const OK: WarmupVerdict = { state: 'ok' }

/**
 * 자리 표시 카드의 개수다.
 *
 * **전략이 몇인지 뜻하지 않는다.** 전략의 수는 등록표가 정하고 화면은 응답을 받기 전까지 그것을
 * 모른다(FR-005). 불러오는 동안 빈자리의 크기를 보이려는 값일 뿐이며 스크린리더에는 읽히지 않는다.
 */
const PLACEHOLDER_COUNT = 3

/**
 * 전략 카드 목록(FR-001, FR-018).
 *
 * <p><b>응답의 <code>strategies</code> 순서를 그대로 쓴다.</b> 화면이 다시 정렬하지 않는다 —
 * 카드의 차례는 등록표가 소유하는 표시 규칙이고, 화면이 순서를 손대면 그것이 전략 지식의 중복이다.
 *
 * <p>조회 중에는 카드 전부가 선택 불가인 자리 표시다. 실패하면 카드 대신
 * <code>RegistryUnavailableNotice</code>를 두고 <b>임시 값이나 예비 등록표로 대신 동작하지 않는다.</b>
 *
 * <p>다시 시도해 성공하면 초점이 새 카드 영역으로 옮겨 간다(NFR-001). 실패했을 때 초점을 '다시
 * 시도' 버튼으로 되돌리는 것은 안내 컴포넌트가 맡는다.
 */
export function StrategyCardList({
  state,
  selectedStrategyId,
  onSelect,
  onRetry,
  judgeWarmup,
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
    return (
      <div className={styles.list}>
        <p className={styles.loading} role="status">
          전략 설정을 불러오는 중입니다.
        </p>
        {Array.from({ length: PLACEHOLDER_COUNT }, (_, index) => (
          <div key={index} className={styles.placeholder} aria-hidden="true" />
        ))}
      </div>
    )
  }

  return (
    <div
      ref={listRef}
      className={styles.list}
      // 재시도에 성공했을 때 초점을 받을 자리다. 탭 차례에는 들어가지 않는다.
      tabIndex={-1}
      role="group"
      aria-label="전략 고르기"
    >
      {state.registry.strategies.map((strategy) => (
        <StrategyCard
          key={strategy.strategyId}
          strategy={strategy}
          selected={selectedStrategyId === strategy.strategyId}
          warmup={judgeWarmup?.(strategy) ?? OK}
          onSelect={() => onSelect(strategy.strategyId)}
        />
      ))}
    </div>
  )
}

import { type KeyboardEvent, type ReactNode, useId, useRef, useState } from 'react'
import styles from './resultTabs.module.css'

/**
 * 결과 화면의 보조 보기 탭(spec 005 FR-007·FR-010·FR-017, T098).
 *
 * <p>버튼마다 패널을 여닫던 네 보기를 탭 하나로 묶는다. 한 번에 하나만 보이고 세로로 쌓이지 않는다. 주소는
 * 바꾸지 않는다(SC-006).
 *
 * <p><b>탭의 내용은 처음 고를 때 그린다.</b> 그때 그 탭의 자료를 받고, 다른 탭으로 옮겨도 내리지 않고 숨긴다 —
 * 돌아왔을 때 다시 받지 않고, 목표 위험 맞춤처럼 요청 상태를 가진 탭이 상태를 잃지 않는다.
 *
 * <p>키보드: 좌우 방향키·Home·End로 탭을 옮기고 옮긴 탭이 바로 열린다. 열 수 없는 탭은 `aria-disabled`이고
 * 초점은 받되 열리지 않으며, 사유는 탭 줄 아래 문장으로 두고 `aria-describedby`로 잇는다(FR-022).
 */
export type ResultTab = {
  id: string
  label: string
  /** 열 수 없는 탭. `reason`과 함께 준다. 값을 0으로 채워 열지 않는다(FR-009) */
  disabled?: boolean
  reason?: string
  content: ReactNode
}

type ResultTabsProps = {
  /** 탭 줄의 이름. 스크린 리더가 "… 탭 목록"으로 읽는다 */
  label: string
  tabs: readonly ResultTab[]
  defaultTab: string
}

const KEY_MOVES: Record<string, (index: number, count: number) => number> = {
  ArrowRight: (index, count) => (index + 1) % count,
  ArrowLeft: (index, count) => (index - 1 + count) % count,
  Home: () => 0,
  End: (_index, count) => count - 1,
}

export function ResultTabs({ label, tabs, defaultTab }: ResultTabsProps) {
  const baseId = useId()
  const [active, setActive] = useState(defaultTab)
  const [visited, setVisited] = useState<ReadonlySet<string>>(() => new Set([defaultTab]))
  const tabRefs = useRef(new Map<string, HTMLButtonElement>())

  const tabId = (id: string) => `${baseId}-tab-${id}`
  const panelId = (id: string) => `${baseId}-panel-${id}`
  const reasonId = (id: string) => `${baseId}-reason-${id}`

  const select = (id: string) => {
    const tab = tabs.find((candidate) => candidate.id === id)
    if (!tab || tab.disabled) return
    setActive(id)
    setVisited((current) => (current.has(id) ? current : new Set(current).add(id)))
  }

  const moveByKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const move = KEY_MOVES[event.key]
    if (!move) return
    const index = tabs.findIndex((tab) => tabId(tab.id) === (event.target as HTMLElement).id)
    if (index < 0) return
    event.preventDefault()
    const next = tabs[move(index, tabs.length)]
    tabRefs.current.get(next.id)?.focus()
    select(next.id)
  }

  return (
    <div className={styles.tabs}>
      <div role="tablist" aria-label={label} className={styles.tablist} onKeyDown={moveByKey}>
        {tabs.map((tab) => {
          const selected = tab.id === active
          return (
            <button
              key={tab.id}
              ref={(node) => {
                if (node) tabRefs.current.set(tab.id, node)
                else tabRefs.current.delete(tab.id)
              }}
              type="button"
              role="tab"
              id={tabId(tab.id)}
              className={styles.tab}
              aria-selected={selected}
              aria-controls={visited.has(tab.id) ? panelId(tab.id) : undefined}
              aria-disabled={tab.disabled || undefined}
              aria-describedby={tab.disabled && tab.reason ? reasonId(tab.id) : undefined}
              tabIndex={selected ? 0 : -1}
              onClick={() => select(tab.id)}
            >
              {tab.label}
            </button>
          )
        })}
      </div>
      {tabs
        .filter((tab) => tab.disabled && tab.reason)
        .map((tab) => (
          <p key={tab.id} id={reasonId(tab.id)} className={styles.reason}>
            {tab.reason}
          </p>
        ))}
      {tabs
        .filter((tab) => visited.has(tab.id))
        .map((tab) => (
          <div
            key={tab.id}
            role="tabpanel"
            id={panelId(tab.id)}
            aria-labelledby={tabId(tab.id)}
            hidden={tab.id !== active}
            tabIndex={0}
            className={styles.panel}
          >
            {tab.content}
          </div>
        ))}
    </div>
  )
}

import { useCallback, useMemo, useRef, useState } from 'react'
import type { RegimeLinkEntryPath } from '@/types/regimeLink'

/**
 * 011 탐색 상태 (T091 · research R12·R15).
 *
 * 주소(`snapshot`·`case`·`full`)와 **탭** `sessionStorage`에 나눠 둔다. 같은 탭에서 005로 가
 * 비교 대상을 바꾸고 다시 들어오면(주소에 `snapshot` 없이 같은 `base`) 복귀로 보고 저장된
 * snapshot으로 목록을 다시 확정한다. 새 탭·새 세션은 첫 진입이다.
 *
 * **다른 탭의 값은 쓰지 않는다.** `localStorage`가 아니라 `sessionStorage`인 이유다.
 * 저장소 접근이 막히면 첫 진입으로 떨어지고 화면을 막지 않는다.
 */
export type RegimeExplorationState = {
  snapshotKey: string
  entryPath: RegimeLinkEntryPath
  full: boolean
  /** 로그인 사용자의 직전 검색 기록. 익명은 없다. */
  linkSearchId?: string
  /** 익명이 변경을 표시하려고 들고 다니는 직전 목록(사례 종료월). */
  previousCases?: string[]
}

const prefix = 'thorp.regimeLink.exploration.'

function storageKey(base: string): string {
  return `${prefix}${base}`
}

export function readExploration(base: string): RegimeExplorationState | null {
  try {
    const raw = window.sessionStorage.getItem(storageKey(base))
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const value = parsed as Partial<RegimeExplorationState>
    if (typeof value.snapshotKey !== 'string' || value.snapshotKey.length === 0) return null
    if (value.entryPath !== 'MARKET_FLOW' && value.entryPath !== 'STRATEGY_RESULT') return null
    return {
      snapshotKey: value.snapshotKey,
      entryPath: value.entryPath,
      full: value.full === true,
      linkSearchId: typeof value.linkSearchId === 'string' ? value.linkSearchId : undefined,
      previousCases: Array.isArray(value.previousCases)
        ? value.previousCases.filter((item): item is string => typeof item === 'string')
        : undefined,
    }
  } catch {
    // 저장소를 못 읽는 것은 첫 진입과 같다. 화면을 막지 않는다.
    return null
  }
}

export function writeExploration(base: string, state: RegimeExplorationState): void {
  try {
    window.sessionStorage.setItem(storageKey(base), JSON.stringify(state))
  } catch {
    // 저장하지 못해도 이번 화면은 그대로 돌아간다.
  }
}

export type RegimeExploration = {
  /** 복귀인지 첫 진입인지. 복귀면 분야 선택을 건너뛴다. */
  isReturn: boolean
  stored: RegimeExplorationState | null
  state: RegimeExplorationState | null
  remember: (state: RegimeExplorationState) => void
}

/**
 * @param base 기준 결과의 공개 토큰. 토큰별로 따로 기억한다.
 * @param snapshotInUrl 주소의 `snapshot`. 있으면 새로고침이거나 이미 고른 검색이다.
 */
export function useRegimeExploration(
  base: string,
  snapshotInUrl: string | null,
): RegimeExploration {
  const stored = useMemo(() => (base ? readExploration(base) : null), [base])
  const initial = useRef(stored)
  const [state, setState] = useState<RegimeExplorationState | null>(() => {
    if (snapshotInUrl && stored && stored.snapshotKey === snapshotInUrl) return stored
    if (snapshotInUrl) return null
    return stored
  })

  const remember = useCallback(
    (next: RegimeExplorationState) => {
      setState(next)
      if (base) writeExploration(base, next)
    },
    [base],
  )

  return {
    // 주소에 snapshot이 없는데 저장된 값이 있으면 같은 탭으로 돌아온 것이다.
    isReturn: !snapshotInUrl && initial.current !== null,
    stored: initial.current,
    state,
    remember,
  }
}

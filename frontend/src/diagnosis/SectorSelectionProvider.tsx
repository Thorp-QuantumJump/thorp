import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { SectorSelectionContext } from './sector-selection-context'
import { sortSectorsForDisplay } from './sectorOrder'
import { readStoredSelection, writeStoredSelection } from './selectionStorage'
import { distributeEven, isValidWeightTenths, summarizeWeights } from './weights'
import type { SectorSummary } from '@/types/sector'

/**
 * 선택 상태와 상태 전이를 한 곳에 둔다. data-model.md 「상태 전이」 표의 아홉 행을 그대로
 * 구현하며, 분기값은 `weightsTouched`다.
 *
 * - 아직 고치지 않았으면 섹터를 더하거나 뺄 때마다 **전체를** 동일 비중으로 다시 채운다
 * - 한 번이라도 고쳤으면 기존 값을 건드리지 않고 새 섹터에만 0을 준다 (FR-005, SC-009)
 * - 섹터를 뺄 때는 남은 값을 그대로 둔다. 합계가 100% 아래로 내려가는 것은 오류가 아니라
 *   FR-007이 안내하는 정상 상태다
 * - '동일하게 나누기'는 값을 다시 채우면서 `weightsTouched`를 false로 되돌린다
 *   (research.md 결정 10)
 *
 * 해제했다가 다시 고르는 것은 '섹터 체크'와 같다. **이전 비중을 기억해 두었다가 되살리지 않는다.**
 * 되살리면 사용자가 지운 값이 말없이 되돌아온다.
 */

type SelectionState = {
  /**
   * **값을 만들 당시의** 대응표 버전이다. 지금 화면이 받은 버전이 아니다(원칙 VIII).
   *
   * 복원한 값은 이전 버전에서 만들어졌으므로 그 버전을 그대로 들고 간다. 사용자가 선택이나
   * 비중을 바꾸면 그 시점의 값은 지금 대응표에서 만들어진 것이므로 현재 버전으로 다시 찍는다.
   */
  mapVersion: string
  selectedCodes: string[]
  weightTenths: Record<string, number>
  weightsTouched: boolean
}

type SectorSelectionProviderProps = {
  /** 현재 `GET /sectors`가 준 섹터 목록이다. 이 목록에 없는 코드는 상태에 들어오지 못한다. */
  sectors: readonly SectorSummary[]
  /** 값을 만들 당시의 대응표 버전이다 (원칙 VIII). */
  mapVersion: string
  children: ReactNode
}

/** 키 하나를 뺀 새 기록을 만든다. 원본을 건드리지 않는다. */
function omit(weights: Record<string, number>, code: string): Record<string, number> {
  const next: Record<string, number> = {}
  for (const [key, value] of Object.entries(weights)) {
    if (key !== code) next[key] = value
  }
  return next
}

export function SectorSelectionProvider({
  sectors,
  mapVersion,
  children,
}: SectorSelectionProviderProps) {
  // 표시 순서는 화면이 정한다. 부르는 쪽이 어떤 순서로 넘기든 여기서 다시 정렬해, 잔여분이 붙는
  // 자리가 화면과 계산에서 갈라지지 않게 한다(research.md 결정 2).
  const displayRank = useMemo(() => {
    const ranks = new Map<string, number>()
    sortSectorsForDisplay(sectors).forEach((sector, index) => ranks.set(sector.code, index))
    return ranks
  }, [sectors])

  // 화면에 들어올 때 한 번 읽는다. 보관된 값은 현재 목록에 비추어 검증된 뒤에 들어온다(FR-014).
  const [restored] = useState(() => readStoredSelection(sectors))

  const [state, setState] = useState<SelectionState>(() => ({
    // 복원한 값은 이전 대응표에서 만들어졌다. 그 사실을 지금 버전으로 덮어쓰면 003·004의 재현
    // 기록이 "새 버전에서 만든 값"이라고 잘못 말하게 된다.
    mapVersion: restored.snapshot?.mapVersion ?? mapVersion,
    selectedCodes: restored.snapshot?.selectedCodes ?? [],
    weightTenths: restored.snapshot?.weightTenths ?? {},
    weightsTouched: restored.snapshot?.weightsTouched ?? false,
  }))

  /**
   * 마지막으로 보관한 상태다. 첫 렌더에서는 보관하지 않고 기록만 한다.
   *
   * **화면이 뜨는 것만으로는 아무것도 쓰지 않는다.** 예전에 그렇게 했더니, 읽을 수 없는 형식이라
   * 일부러 건드리지 않고 지켜 둔 값을 화면이 뜨는 순간 빈 상태로 덮어썼다. 뒤로 가기로 캐시에
   * 남은 옛 화면이 열리는 것만으로 새 형식의 값이 사라지는 경로였다.
   *
   * 상태 전이는 바뀐 것이 없으면 이전 객체를 그대로 돌려주므로, 이 비교는 "사용자가 무언가
   * 바꿨는가"와 같은 뜻이다.
   */
  const persisted = useRef<SelectionState | null>(null)

  // 사용자가 바꾼 뒤에만 보관한다. 합계가 맞지 않는 상태도 그대로 보관한다(FR-014, 계약 불변식 3).
  useEffect(() => {
    if (persisted.current === null || persisted.current === state) {
      persisted.current = state
      return
    }
    persisted.current = state
    writeStoredSelection(state)
  }, [state])

  const sortCodes = useCallback(
    (codes: readonly string[]) =>
      [...codes].sort(
        (left, right) => (displayRank.get(left) ?? 0) - (displayRank.get(right) ?? 0),
      ),
    [displayRank],
  )

  const toggleSector = useCallback(
    (code: string, selected: boolean) => {
      // 목록에 없는 코드는 상태에 들어오지 못한다. 불변식 3을 여기서 지킨다.
      if (!displayRank.has(code)) return

      setState((previous) => {
        const wasSelected = previous.selectedCodes.includes(code)
        if (wasSelected === selected) return previous

        const selectedCodes = selected
          ? sortCodes([...previous.selectedCodes, code])
          : previous.selectedCodes.filter((selectedCode) => selectedCode !== code)

        // 아직 한 번도 고치지 않았으면 더할 때도 뺄 때도 전체를 다시 채운다.
        if (!previous.weightsTouched) {
          return {
            mapVersion,
            selectedCodes,
            weightTenths: distributeEven(selectedCodes),
            weightsTouched: false,
          }
        }

        // 한 번이라도 고쳤으면 기존 값을 건드리지 않는다. 새 섹터에만 0을 준다.
        return {
          mapVersion,
          selectedCodes,
          weightTenths: selected
            ? { ...previous.weightTenths, [code]: 0 }
            : omit(previous.weightTenths, code),
          weightsTouched: true,
        }
      })
    },
    [displayRank, mapVersion, sortCodes],
  )

  const setWeight = useCallback(
    (code: string, weightTenths: number) => {
      // 받을 수 없는 값은 애초에 여기까지 오지 않는다. 그래도 값이 상태에 들어오는 길목이므로
      // 불변식 2를 한 번 더 지킨다.
      if (!isValidWeightTenths(weightTenths)) return

      setState((previous) => {
        if (!previous.selectedCodes.includes(code)) return previous
        // 같은 값이 다시 들어오면 아무것도 바꾸지 않는다. 슬라이더를 끌 때 같은 값이 여러 번
        // 올라오는데, 여기서 걸러 두면 그만큼 다시 그리지 않는다.
        if (previous.weightTenths[code] === weightTenths && previous.weightsTouched) {
          return previous
        }

        return {
          mapVersion,
          selectedCodes: previous.selectedCodes,
          weightTenths: { ...previous.weightTenths, [code]: weightTenths },
          weightsTouched: true,
        }
      })
    },
    [mapVersion],
  )

  const distributeEvenly = useCallback(() => {
    setState((previous) => ({
      mapVersion,
      selectedCodes: previous.selectedCodes,
      weightTenths: distributeEven(previous.selectedCodes),
      // 사용자가 스스로 초깃값으로 되돌렸으므로 그 뒤에는 지켜야 할 사용자 값이 없다.
      weightsTouched: false,
    }))
  }, [mapVersion])

  const isSelected = useCallback(
    (code: string) => state.selectedCodes.includes(code),
    [state.selectedCodes],
  )

  const value = useMemo(() => {
    const summary = summarizeWeights(state.selectedCodes, state.weightTenths)

    return {
      mapVersion: state.mapVersion,
      selectedCodes: state.selectedCodes,
      weightTenths: state.weightTenths,
      weightsTouched: state.weightsTouched,
      dropped: restored.dropped,
      restoreProblem: restored.problem,
      ...summary,
      isSelected,
      toggleSector,
      setWeight,
      distributeEvenly,
    }
  }, [state, restored, isSelected, toggleSector, setWeight, distributeEvenly])

  return (
    <SectorSelectionContext.Provider value={value}>{children}</SectorSelectionContext.Provider>
  )
}

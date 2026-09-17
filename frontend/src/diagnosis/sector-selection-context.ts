import { createContext, useContext } from 'react'
import type { DroppedSelection, SelectionRestoreProblem } from './selectionStorage'

/**
 * 선택 상태를 화면 조각들이 함께 읽는 자리다.
 *
 * 컴포넌트와 같은 파일에 두지 않는 까닭은 014의 `auth-context.ts`와 같다. Fast Refresh가
 * 컴포넌트만 내보내는 파일에서만 동작하므로 훅과 컨텍스트를 따로 둔다.
 *
 * **전역 상태 라이브러리를 도입하지 않는다.** React Context로 충분하다(plan 「Structure Decision」).
 */
export type SectorSelectionContextValue = {
  /**
   * 지금 값을 만든 대응표 버전이다. 복원한 값은 만들 당시의 버전을 그대로 들고 있고, 사용자가
   * 선택이나 비중을 바꾸면 그때 받은 버전으로 다시 찍힌다(원칙 VIII).
   */
  mapVersion: string
  /** 표시 순서로 정규화되어 있다. */
  selectedCodes: readonly string[]
  /** 키 집합이 `selectedCodes`와 정확히 같다. 값은 0 이상 1000 이하 정수다. */
  weightTenths: Readonly<Record<string, number>>
  /** 사용자가 비중을 한 번이라도 고쳤는지. 이 값이 섹터를 더하고 뺄 때의 동작을 가른다. */
  weightsTouched: boolean
  /** 세션 복원 때 끝내 선택에 남지 못한 항목과 그 까닭이다. 코드마다 한 번만 담긴다. */
  dropped: readonly DroppedSelection[]
  /** 보관된 값을 쓸 수 없었던 까닭이다. 없으면 null이다. */
  restoreProblem: SelectionRestoreProblem | null

  // 아래는 저장하지 않고 매번 계산하는 파생값이다(data-model.md 「파생값」).
  totalTenths: number
  isBalanced: boolean
  remainderTenths: number
  canProceed: boolean
  hasZeroWeight: boolean

  isSelected: (code: string) => boolean
  toggleSector: (code: string, selected: boolean) => void
  setWeight: (code: string, weightTenths: number) => void
  /** '동일하게 나누기'다. 값을 다시 채우면서 `weightsTouched`를 false로 되돌린다. */
  distributeEvenly: () => void
  /**
   * 선택과 비중을 **한 번에** 바꾼다. 배우기 예시가 들어오는 경로가 쓴다(FR-012).
   *
   * 고르기와 비중 넣기를 여러 번 나눠 부르지 않는 까닭은, 그 사이에 동일 비중 자동 채움이
   * 끼어들어 예시가 정한 값이 잠시 다른 값으로 바뀌기 때문이다. 예시는 섹터와 비중을 함께
   * 정해서 오므로 상태 전이도 한 번이어야 한다.
   *
   * 받은 값은 부르는 쪽에서 이미 검증된 것이며(현재 목록에 있는 코드, 0 이상 1000 이하 정수),
   * 여기서도 목록에 없는 코드는 받지 않는다. **합계를 맞추지 않는다.** 합계가 1000이 아닌
   * 예시도 그대로 채우고 부족·초과 안내로 처리한다(계약 2절).
   */
  applySelection: (codes: readonly string[], weightTenths: Readonly<Record<string, number>>) => void
}

export const SectorSelectionContext = createContext<SectorSelectionContextValue | null>(null)

export function useSectorSelection(): SectorSelectionContextValue {
  const context = useContext(SectorSelectionContext)
  if (!context) throw new Error('useSectorSelection must be used within SectorSelectionProvider')
  return context
}

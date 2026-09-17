import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useSectorSelection } from './sector-selection-context'
import { isValidWeightTenths } from './weights'
import type { SectorSummary } from '@/types/sector'

/**
 * 배우기(013)가 밀어 넣는 예시 값을 받는 입구다(FR-012, research.md 결정 9).
 *
 * 수단은 React Router의 navigation state 하나다. 페이로드 형태는 `contracts/selection-handoff.md`
 * 2절이며 이 파일은 그 계약을 다시 정의하지 않는다.
 *
 * **쿼리 문자열을 쓰지 않는다.** 비중이 담긴 링크는 공유되면 '추천 비중'처럼 읽힐 여지가 있고
 * (원칙 III), 외부에서 값을 주입하는 경로가 열린다.
 *
 * **예시 내용 자체는 013이 소유한다.** 어떤 주제가 어떤 섹터·비중을 쓰는지 002가 정하지 않는다.
 * 여기서 하는 일은 들어온 값을 검증하고, 기존 입력을 지킬지 물은 뒤 채우는 것까지다.
 */

/** 계약 2절의 페이로드가 navigation state에 실리는 자리다. */
export const LEARNING_EXAMPLE_STATE_KEY = 'sectorSelectionExample'

/** 검증을 통과한 예시다. 화면이 그대로 상태에 넣을 수 있는 모양이다. */
export type LearningExample = {
  /** 어디서 온 예시인지. 013이 채운다. */
  source: string
  /** 어느 주제의 예시인지. 내용은 013이 소유한다. */
  topicId: string
  /** 표시 순서가 아니라 예시가 준 순서 그대로다. 정규화는 상태가 한다. */
  codes: string[]
  weightTenths: Record<string, number>
  /** 현재 목록에 없거나 비중을 쓸 수 없어 빠진 코드다. */
  droppedCodes: string[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * navigation state에서 예시를 읽어 현재 섹터 목록에 비추어 검증한다.
 *
 * **들어온 값을 신뢰하지 않는다.** 저장소에서 읽는 값과 **같은 검사**를 거친다. 현재
 * `GET /sectors` 응답에 있는 코드만, 0 이상 1000 이하 정수 비중만 받아들인다(계약 「읽는 쪽의
 * 의무」). 예시가 013에서 온다고 해서 검사를 건너뛰면, 그 경로가 바로 외부 주입 통로가 된다.
 *
 * 쓸 수 있는 섹터가 하나도 남지 않으면 null을 돌려준다. 채울 것이 없으면 덮어쓸지 물을 일도 없다.
 */
export function readLearningExample(
  state: unknown,
  sectors: readonly SectorSummary[],
): LearningExample | null {
  if (!isRecord(state)) return null

  const payload = state[LEARNING_EXAMPLE_STATE_KEY]
  if (!isRecord(payload)) return null
  if (!Array.isArray(payload.selected)) return null

  const known = new Set(sectors.map((sector) => sector.code))
  const codes: string[] = []
  const weightTenths: Record<string, number> = {}
  const droppedCodes: string[] = []

  for (const item of payload.selected) {
    if (!isRecord(item) || typeof item.code !== 'string') continue

    const { code } = item
    // 같은 코드가 두 번 실려 오면 뒤엣것을 버린다. 선택은 코드마다 한 번뿐이다.
    if (code in weightTenths) continue
    if (!known.has(code) || !isValidWeightTenths(item.weightTenths)) {
      if (!droppedCodes.includes(code)) droppedCodes.push(code)
      continue
    }

    codes.push(code)
    weightTenths[code] = item.weightTenths
  }

  if (codes.length === 0) return null

  return {
    source: typeof payload.source === 'string' ? payload.source : '',
    topicId: typeof payload.topicId === 'string' ? payload.topicId : '',
    codes,
    weightTenths,
    droppedCodes,
  }
}

/** 예시가 기다리는 동안 화면이 무엇을 해야 하는지다. */
export type LearningExampleEntry = {
  /** 덮어쓸지 사용자에게 묻는 중인 예시다. 없으면 null이다. */
  pending: LearningExample | null
  /** 덮어쓰기에 동의했다. 예시 값으로 채운다. */
  confirmOverwrite: () => void
  /** 덮어쓰지 않기로 했다. 기존 값을 그대로 둔다. */
  cancelOverwrite: () => void
}

/**
 * 배우기 예시 진입을 처리한다. `SectorSelectionProvider` 안에서만 쓸 수 있다.
 *
 * | 들어온 상태 | 동작 |
 * |---|---|
 * | 아직 아무 섹터도 고르지 않았다 | **묻지 않고** 채운다 (SC-011) |
 * | 이미 고른 섹터가 있다 | 덮어쓸지 먼저 묻고, 거절하면 기존 값을 그대로 둔다 |
 *
 * **예시를 읽는 즉시 navigation state를 `replace`로 지운다.** 지우지 않으면 뒤로 가기나
 * 새로고침 때 확인이 다시 뜬다. 지운 뒤에도 묻는 중인 예시는 이 훅의 상태에 남아 있으므로
 * 사용자가 답할 때까지 사라지지 않는다.
 *
 * 예시 합계가 1000이 아니어도 **그대로 채운다.** 자동으로 맞추지 않고 FR-007의 부족·초과
 * 안내로 처리한다(계약 2절).
 */
export function useLearningExampleEntry(sectors: readonly SectorSummary[]): LearningExampleEntry {
  const location = useLocation()
  const navigate = useNavigate()
  const { selectedCodes, applySelection } = useSectorSelection()

  const [pending, setPending] = useState<LearningExample | null>(null)

  /**
   * 이 이동에 실린 예시를 이미 다 썼는가.
   *
   * **`location.state`가 비었는지로 판정하지 않는다.** 아래에서 state를 지우는 `navigate`가
   * 반영되기 전에 이 효과가 한 번 더 돌 수 있고, 그때 같은 예시를 두 번째로 읽는다. 예시를
   * 채운 직후라 그 두 번째에는 고른 섹터가 이미 있으므로, 사용자가 입력한 적이 없는데도 덮어쓰기
   * 확인이 떠 SC-011이 세는 "확인을 물은 사례"가 생긴다. 소비 여부는 화면 밖 값이 아니라 여기서
   * 직접 기억한다.
   */
  const consumed = useRef(false)

  // 고른 섹터가 하나라도 있으면 지켜야 할 사용자 입력이 있다는 뜻이다. 비중은 선택 없이 존재할
  // 수 없으므로(선택 상태의 불변식 1) 선택 개수 하나로 판정이 끝난다.
  const hasOwnInput = selectedCodes.length > 0

  useEffect(() => {
    if (consumed.current) return

    const example = readLearningExample(location.state, sectors)
    if (!example) return

    // 읽는 즉시 소비한다. 아래에서 어느 갈래로 가든 이 이동에 실린 값은 다 쓴 것이다.
    consumed.current = true
    void navigate(location.pathname, { replace: true, state: null })

    if (hasOwnInput) {
      setPending(example)
      return
    }

    applySelection(example.codes, example.weightTenths)
  }, [location.state, location.pathname, sectors, hasOwnInput, applySelection, navigate])

  const confirmOverwrite = useCallback(() => {
    if (!pending) return
    applySelection(pending.codes, pending.weightTenths)
    setPending(null)
  }, [pending, applySelection])

  const cancelOverwrite = useCallback(() => setPending(null), [])

  return { pending, confirmOverwrite, cancelOverwrite }
}

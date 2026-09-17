import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useSectorSelection } from './sector-selection-context'
import type { DroppedSelection } from './selectionStorage'
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
  /**
   * 예시에 실려 있었으나 끝내 채우지 못한 항목과 그 까닭이다. 코드마다 한 번만 담는다.
   *
   * **까닭을 한 자루에 담지 않는다.** 보관된 값을 복원하는 경로와 같은 구분을 쓴다. 화면이
   * "지금 목록에 없어 빠졌다"고만 말하면 비중이 망가져 빠진 항목까지 없는 섹터로 둔갑한다.
   */
  dropped: DroppedSelection[]
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
  const candidates: DroppedSelection[] = []

  for (const item of payload.selected) {
    if (!isRecord(item) || typeof item.code !== 'string') continue

    const { code } = item

    // 까닭을 나눠 담는다. 화면이 세 경우를 같은 문장으로 알리면 사실과 다른 말을 하게 된다.
    if (!known.has(code)) {
      candidates.push({ code, reason: 'notInCatalog' })
      continue
    }
    if (!isValidWeightTenths(item.weightTenths)) {
      candidates.push({ code, reason: 'unusableWeight' })
      continue
    }
    if (code in weightTenths) {
      // 같은 코드가 두 번 실려 오면 뒤엣것을 버린다. 선택은 코드마다 한 번뿐이다.
      candidates.push({ code, reason: 'duplicate' })
      continue
    }

    codes.push(code)
    weightTenths[code] = item.weightTenths
  }

  if (codes.length === 0) return null

  // 끝내 채워진 코드는 잃은 것이 아니다. 같은 코드를 여러 번 담지도 않는다. 이 두 가지를 하지
  // 않으면 화면이 실제로 잃은 섹터 수보다 큰 숫자를 말하게 된다.
  const dropped: DroppedSelection[] = []
  const reported = new Set<string>()
  for (const candidate of candidates) {
    if (candidate.code in weightTenths) continue
    if (reported.has(candidate.code)) continue
    reported.add(candidate.code)
    dropped.push(candidate)
  }

  return {
    source: typeof payload.source === 'string' ? payload.source : '',
    topicId: typeof payload.topicId === 'string' ? payload.topicId : '',
    codes,
    weightTenths,
    dropped,
  }
}

/** 예시가 기다리는 동안 화면이 무엇을 해야 하는지다. */
export type LearningExampleEntry = {
  /** 덮어쓸지 사용자에게 묻는 중인 예시다. 없으면 null이다. */
  pending: LearningExample | null
  /**
   * 실제로 채운 예시에서 끝내 빠진 항목이다. 아직 채우지 않았으면 비어 있다.
   *
   * **채우고 나서 조용히 넘어가지 않는다.** 예시가 섹터 셋을 실어 왔는데 하나가 지금 목록에
   * 없어 둘만 채워지면, 사용자는 합계가 왜 100%가 아닌지 알 수 없다. 계약의 「읽는 쪽의 의무」가
   * 빠진 항목을 한 문장으로 알리라고 정한 자리다(원칙 VI).
   */
  dropped: readonly DroppedSelection[]
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
  const [dropped, setDropped] = useState<readonly DroppedSelection[]>([])

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
    //
    // **지우는 것은 실어 온 state뿐이다.** 주소의 질의 문자열과 조각은 그대로 둔다. 경로만으로
    // 주소를 다시 만들면 013이 붙여 보낸 값이 예시를 소비하는 순간 말없이 사라진다.
    consumed.current = true
    void navigate(`${location.pathname}${location.search}${location.hash}`, {
      replace: true,
      state: null,
    })

    if (hasOwnInput) {
      setPending(example)
      return
    }

    applySelection(example.codes, example.weightTenths)
    setDropped(example.dropped)
  }, [
    location.state,
    location.pathname,
    location.search,
    location.hash,
    sectors,
    hasOwnInput,
    applySelection,
    navigate,
  ])

  const confirmOverwrite = useCallback(() => {
    if (!pending) return
    applySelection(pending.codes, pending.weightTenths)
    setDropped(pending.dropped)
    setPending(null)
  }, [pending, applySelection])

  // 덮어쓰지 않기로 했으면 예시를 쓰지 않은 것이므로 빠진 항목을 알릴 일도 없다.
  const cancelOverwrite = useCallback(() => setPending(null), [])

  return { pending, dropped, confirmOverwrite, cancelOverwrite }
}

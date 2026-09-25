import { createContext, useContext, useEffect } from 'react'
import type { AssistantPageKind } from '@/types/assistant'

/**
 * 도우미가 어느 화면의 무엇을 보고 있는지(spec 015 Key Entities "페이지 맥락").
 *
 * <p>페이지는 마운트 때 자기 맥락을 등록하고 언마운트 때 지운다. 버튼·말풍선은 `App`에 한 번만 있으므로
 * 등록된 맥락이 없으면 그리지 않는다. 1차는 결과 화면(`RESULT`)만 등록한다.
 */
export type AssistantPage = {
  kind: AssistantPageKind
  publicToken: string
}

export type AssistantPageRegistry = {
  page: AssistantPage | null
  register: (page: AssistantPage) => () => void
}

export const AssistantPageContext = createContext<AssistantPageRegistry | null>(null)

export function useAssistantPageRegistry(): AssistantPageRegistry | null {
  return useContext(AssistantPageContext)
}

/**
 * 화면이 자기 맥락을 등록한다. 제공자 밖(테스트 등)에서는 아무것도 하지 않는다.
 *
 * <p>`page`는 호출자가 `useMemo`로 고정한다. 매 렌더 새 객체면 등록을 반복한다.
 */
export function useAssistantPageContext(page: AssistantPage | null): void {
  const registry = useContext(AssistantPageContext)
  useEffect(() => {
    if (!registry || !page) return undefined
    return registry.register(page)
  }, [registry, page])
}

import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { fetchAssistantCapabilities } from '@/api/assistant'
import type { AssistantCapabilities } from '@/types/assistant'
import { AssistantFab } from './AssistantFab'
import {
  type AssistantPage,
  AssistantPageContext,
  type AssistantPageRegistry,
  useAssistantPageRegistry,
} from './assistantPageContext'

/** 로그인·가입·관리 화면에는 버튼을 두지 않는다(FR-001). 맥락이 등록돼도 이 경로면 그리지 않는다. */
const HIDDEN_PATH_PREFIXES = ['/login', '/signup', '/admin']

/** 페이지가 등록한 맥락을 `App` 수준에서 들고 있는다. 라우트가 바뀌어도 살아 있다. */
export function AssistantProvider({ children }: { children: ReactNode }) {
  const [page, setPage] = useState<AssistantPage | null>(null)

  const register = useCallback((next: AssistantPage) => {
    setPage(next)
    return () => {
      setPage((current) => (current === next ? null : current))
    }
  }, [])

  const value = useMemo<AssistantPageRegistry>(() => ({ page, register }), [page, register])
  return <AssistantPageContext.Provider value={value}>{children}</AssistantPageContext.Provider>
}

/**
 * 플로팅 버튼의 마운트 자리(`App`, 라우트 전환 바깥). 등록된 맥락이 있고, 숨김 경로가 아니고, 서버 플래그가
 * 그 화면 종류를 켰을 때만 버튼을 그린다(FR-001·FR-016). 플래그는 앱 수명 동안 한 번만 묻는다.
 */
export function AssistantWidget() {
  const registry = useAssistantPageRegistry()
  const { pathname } = useLocation()
  const [capabilities, setCapabilities] = useState<AssistantCapabilities | null>(null)
  const page = registry?.page ?? null

  useEffect(() => {
    if (!page || capabilities) return undefined
    let alive = true
    void fetchAssistantCapabilities().then((loaded) => {
      if (alive) setCapabilities(loaded)
    })
    return () => {
      alive = false
    }
  }, [page, capabilities])

  if (!registry || !page) return null
  if (HIDDEN_PATH_PREFIXES.some((prefix) => pathname.startsWith(prefix))) return null
  if (!capabilities?.enabled || !capabilities.scopes.includes(page.kind)) return null
  return <AssistantFab page={page} />
}

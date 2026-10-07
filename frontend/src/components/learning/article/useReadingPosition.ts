import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { prepareLeaveRecording, recordPosition, sendPositionOnLeave } from '@/api/learningMe'

interface ReadingPositionOptions {
  /** 글 전체. 그 안의 `data-article-end` 표지를 감싼 요소가 본문이다(ArticleBody) */
  root: RefObject<HTMLElement | null>
  articleId: string
  /** 위치를 기록하는가. 로그인한 새 기준 글에서만 참이다. 거짓이면 아무 요청도 보내지 않는다 */
  active: boolean
  /**
   * 가장 큰 비율을 처음부터 잴 단위. 확인된 로그인 상태(로그인했으면 사용자까지)로 짓는다. 확인 중과 `error`는
   * 바뀐 것으로 보지 않으므로 그동안 잰 비율을 이어 간다(research R33)
   */
  sessionKey: string
}

/** 화면 아래 끝이 본문의 어디까지 내려왔는지 0~100 정수로 잰다(research R32). */
export function measureReadPercent(body: HTMLElement): number | null {
  const rect = body.getBoundingClientRect()
  if (rect.height <= 0) return null
  const ratio = ((window.innerHeight - rect.top) / rect.height) * 100
  return Math.min(100, Math.max(0, Math.floor(ratio)))
}

function bodyOf(root: HTMLElement | null): HTMLElement | null {
  return root?.querySelector('[data-article-end]')?.parentElement ?? null
}

/**
 * 읽은 위치 기록(013 FR-020, research R32). 이 화면에서 잰 가장 큰 비율을 돌려준다. 글 머리의 '읽은 정도' 막대가
 * 쓴다.
 *
 * <p><b>가장 큰 비율을 기억했다가 떠날 때마다 한 번 보낸다.</b> 비율이 그대로여도 보낸다. 서버가 더 큰 비율만
 * 남기고 마지막으로 연 시각은 늘 바꾸므로, 다시 열었다 떠난 글도 '마지막으로 연 글'이 되고 0%에서 떠난 글도
 * '읽는 중'이 된다.
 *
 * <p><b>떠나는 길이 둘이다.</b> 화면 안 이동(이 글 화면이 사라지거나 다른 글로 바뀜)은 `apiClient`로, 탭 숨김·
 * 닫기(`visibilitychange`의 hidden, `pagehide`)는 keepalive `fetch`로 보낸다. 한 번 떠날 때 둘이 겹치면 한 번만
 * 보내고, 탭이 다시 보이면 다음 떠남을 기다린다.
 *
 * <p><b>로그인 상태가 바뀌면 보내지 않고 처음부터 잰다.</b> 앞 사용자의 비율을 다음 사용자에게 싣지 않고,
 * 로그아웃한 뒤의 요청이 만료로 읽히지 않게 한다.
 *
 * <p><b>개발 모드의 다시 걸기는 떠남이 아니다.</b> StrictMode는 화면을 열자마자 효과를 정리했다 곧바로 다시
 * 건다. 정리할 때 보낼 기록을 바로 보내지 않고 미뤘다가, 같은 글·같은 로그인 상태로 다시 걸리면 취소한다.
 */
export function useReadingPosition({
  root,
  articleId,
  active,
  sessionKey,
}: ReadingPositionOptions): number {
  const activeRef = useRef(active)
  const sessionRef = useRef(sessionKey)
  const pendingLeaveRef = useRef<{ key: string; timer: ReturnType<typeof setTimeout> } | null>(
    null,
  )
  const [furthest, setFurthest] = useState(0)

  // 레이아웃 효과로 맞춘다. 커밋 중에 먼저 돌므로, 로그인 상태가 바뀌어 아래 효과를 정리할 때는 이미 새 값이다.
  useLayoutEffect(() => {
    activeRef.current = active
    sessionRef.current = sessionKey
  })

  // keepalive 요청은 떠나는 순간에 토큰을 받을 수 없으므로 기록을 시작할 때 미리 받아 둔다.
  useEffect(() => {
    if (active) prepareLeaveRecording()
  }, [active])

  useEffect(() => {
    const session = sessionKey
    const leaveKey = `${articleId}|${session}`
    const pendingLeave = pendingLeaveRef.current
    if (pendingLeave?.key === leaveKey) {
      clearTimeout(pendingLeave.timer)
      pendingLeaveRef.current = null
    }
    let max = 0
    let left = false
    setFurthest(0)

    const measure = () => {
      const body = bodyOf(root.current)
      if (!body) return
      const percent = measureReadPercent(body)
      if (percent !== null && percent > max) {
        max = percent
        setFurthest(percent)
      }
    }

    const leaveByTab = () => {
      if (left || !activeRef.current) return
      measure()
      left = true
      sendPositionOnLeave(articleId, max)
    }

    const handleVisibility = () => {
      if (document.visibilityState === 'hidden') leaveByTab()
      else left = false
    }

    measure()
    window.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', measure)
    window.addEventListener('pagehide', leaveByTab)
    document.addEventListener('visibilitychange', handleVisibility)

    return () => {
      window.removeEventListener('scroll', measure)
      window.removeEventListener('resize', measure)
      window.removeEventListener('pagehide', leaveByTab)
      document.removeEventListener('visibilitychange', handleVisibility)
      // 화면 안 이동으로 떠났다. 로그인 상태가 바뀌어 다시 걸리는 정리라면 보내지 않는다.
      if (left || !activeRef.current || sessionRef.current !== session) return
      const percent = max
      const timer = setTimeout(() => {
        if (pendingLeaveRef.current?.timer === timer) pendingLeaveRef.current = null
        void recordPosition(articleId, percent)
      }, 0)
      pendingLeaveRef.current = { key: leaveKey, timer }
    }
  }, [root, articleId, sessionKey])

  return furthest
}

/**
 * 기록된 비율이 화면 아래 끝에 오도록 스크롤한다. `measureReadPercent`의 거꾸로다. 본문을 찾지 못하면 아무것도
 * 하지 않는다(013 FR-020 '이어서 읽기').
 */
export function scrollToReadPercent(root: HTMLElement | null, percent: number): void {
  const body = bodyOf(root)
  if (!body) return
  const rect = body.getBoundingClientRect()
  const top = window.scrollY + rect.top + (rect.height * percent) / 100 - window.innerHeight
  window.scrollTo({ top: Math.max(0, top) })
}

/**
 * 기록된 비율에 걸린 본문 요소로 초점을 옮긴다(FR-019). 화면만 옮기면 키보드와 화면 낭독기의 위치는 글 맨 위에
 * 남는다. 초점을 받을 수 없는 요소에는 잠시 `tabindex="-1"`을 달고, 초점이 떠나면 뗀다. 화면은 다시 움직이지
 * 않는다.
 */
export function focusReadPosition(root: HTMLElement | null, percent: number): void {
  const body = bodyOf(root)
  if (!body) return
  const bodyRect = body.getBoundingClientRect()
  const line = bodyRect.top + (bodyRect.height * percent) / 100
  let target: HTMLElement | null = null
  for (const child of Array.from(body.children)) {
    if (!(child instanceof HTMLElement) || child.hasAttribute('data-article-end')) continue
    if (target !== null && child.getBoundingClientRect().top > line) break
    target = child
  }
  if (!target) return
  const element = target
  if (!element.hasAttribute('tabindex')) {
    element.setAttribute('tabindex', '-1')
    element.addEventListener('blur', () => element.removeAttribute('tabindex'), { once: true })
  }
  element.focus({ preventScroll: true })
}

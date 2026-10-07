import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react'
import { articleEndOf } from './articleMarkers'

interface ArticleCompletionOptions {
  /** 글 전체. 그 안의 `data-article-end` 표지가 본문 끝이다(ArticleBody) */
  root: RefObject<HTMLElement | null>
  /** 완료 요청을 보내도 되는가. 로그인한 새 기준 글이고 아직 완료하지 않았을 때만 참이다 */
  active: boolean
  /** 글의 최소 머문 시간(research R20) */
  minDwellSeconds: number
  /**
   * 머문 시간을 처음부터 셀 단위(`articleSessionKey`). 글과 확인된 로그인 상태(로그인했으면 사용자까지)로 짓는다.
   * 확인 중(`checking`)과 `error`는 바뀐 것으로 보지 않으므로 이 값도 바뀌지 않는다(research R33)
   */
  sessionKey: string
  /**
   * 바뀌면 이미 부른 판정을 풀고 다시 판정한다. 센 시간은 이어 간다. 다른 곳에서 글이 바뀌어 완료가 거절된 뒤 글을
   * 다시 불러왔을 때 쓴다. 머문 시간을 처음부터 세는 것은 로그인 상태나 사용자가 바뀐 때뿐이다(R33)
   */
  rearmKey?: number
  /**
   * 로그인 상태를 다시 확인하는 중이다. 그동안 두 조건이 참이 되면 요청을 미뤘다가, 확인이 끝나 같은 상태로
   * 돌아온 것을 안 뒤에 보낸다(R33)
   */
  held: boolean
  /** 두 조건이 함께 참이 된 첫 순간에 한 번 부른다 */
  onReady: () => void
}

/**
 * 글 완료 판정(013 FR-021, research R32).
 *
 * <p><b>두 조건이 함께 참이 되는 첫 순간에 한 번.</b> 본문 끝 표지가 화면에 들어와 있고, 글을 연 뒤 탭이 보이는
 * 동안 센 시간이 최소 머문 시간을 넘었을 때다. 어느 쪽이 먼저 채워져도 된다. 탭이 숨겨진 동안은 세지 않는다.
 * 서버는 머문 시간을 다시 재지 않는다.
 *
 * <p><b>로그인 상태나 사용자가 바뀌면 처음부터 센다.</b> 로그인 전에 센 시간은 계정에 더하지 않는다(spec Edge
 * Cases). 재확인은 바뀐 것이 아니므로 센 시간을 이어 간다.
 *
 * <p>`IntersectionObserver`가 없는 환경에서는 본문 끝을 판정할 수 없어 완료하지 않는다.
 */
export function useArticleCompletion({
  root,
  active,
  minDwellSeconds,
  sessionKey,
  rearmKey = 0,
  held,
  onReady,
}: ArticleCompletionOptions): void {
  const onReadyRef = useRef(onReady)
  const gateRef = useRef({ active, held })
  const evaluateRef = useRef<() => void>(() => {})
  const rearmRef = useRef<() => void>(() => {})

  useLayoutEffect(() => {
    onReadyRef.current = onReady
    gateRef.current = { active, held }
  })

  useEffect(() => {
    const required = minDwellSeconds * 1000
    let counted = 0
    let visibleSince: number | null = document.visibilityState === 'visible' ? Date.now() : null
    let endVisible = false
    let fired = false
    // 정리된 뒤에 늦게 온 관찰 결과로 다시 판정하지 않는다. 앞 사용자의 시간으로 다음 사용자를 완료하지 않게 한다.
    let disposed = false
    let timer: ReturnType<typeof setTimeout> | null = null

    const elapsed = () => counted + (visibleSince === null ? 0 : Date.now() - visibleSince)

    const evaluate = () => {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      if (fired || disposed) return
      const remaining = required - elapsed()
      if (remaining > 0) {
        // 탭이 보이는 동안만 시간이 흐르므로 그때만 다음 판정을 건다.
        if (visibleSince !== null) timer = setTimeout(evaluate, remaining)
        return
      }
      const gate = gateRef.current
      if (!endVisible || !gate.active || gate.held) return
      fired = true
      onReadyRef.current()
    }
    evaluateRef.current = evaluate
    rearmRef.current = () => {
      fired = false
      evaluate()
    }

    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        if (visibleSince === null) visibleSince = Date.now()
      } else if (visibleSince !== null) {
        counted += Date.now() - visibleSince
        visibleSince = null
      }
      evaluate()
    }
    document.addEventListener('visibilitychange', handleVisibility)

    let observer: IntersectionObserver | null = null
    const marker = articleEndOf(root.current)
    if (marker && typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver((entries) => {
        const last = entries[entries.length - 1]
        if (!last) return
        endVisible = last.isIntersecting
        evaluate()
      })
      observer.observe(marker)
    }

    evaluate()
    return () => {
      disposed = true
      document.removeEventListener('visibilitychange', handleVisibility)
      observer?.disconnect()
      if (timer !== null) clearTimeout(timer)
      evaluateRef.current = () => {}
      rearmRef.current = () => {}
    }
  }, [root, minDwellSeconds, sessionKey])

  // 처음 그릴 때는 건너뛴다. 값이 바뀔 때만 판정을 다시 연다.
  const lastRearmRef = useRef(rearmKey)
  useEffect(() => {
    if (lastRearmRef.current === rearmKey) return
    lastRearmRef.current = rearmKey
    rearmRef.current()
  }, [rearmKey])

  // 요청을 보내도 되는지가 바뀌면 다시 판정한다. 확인이 끝나 미뤄 둔 완료를 이때 보낸다.
  useEffect(() => {
    evaluateRef.current()
  }, [active, held])
}

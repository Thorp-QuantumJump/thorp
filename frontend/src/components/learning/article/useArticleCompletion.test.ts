import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useArticleCompletion } from './useArticleCompletion'

/**
 * 013 T190. 완료는 본문 끝과 최소 머문 시간이 함께 참이 되는 첫 순간에 한 번이다(FR-021, research R32·R33,
 * quickstart 10-1·10-10).
 *
 * <p>`IntersectionObserver` 대역은 이 파일 안에 둔다. `src/test/setup.ts`에 전역으로 두면 없는 환경을 다루는
 * 다른 테스트가 깨진다.
 */

class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = []
  readonly callback: IntersectionObserverCallback
  observed: Element[] = []

  constructor(callback: IntersectionObserverCallback) {
    this.callback = callback
    FakeIntersectionObserver.instances.push(this)
  }

  observe(target: Element) {
    this.observed.push(target)
  }

  unobserve() {}

  disconnect() {
    this.observed = []
  }

  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

function setEndVisible(visible: boolean) {
  const observer = FakeIntersectionObserver.instances.at(-1)
  if (!observer) throw new Error('관찰자가 없습니다.')
  act(() => {
    observer.callback(
      [{ isIntersecting: visible } as IntersectionObserverEntry],
      observer as unknown as IntersectionObserver,
    )
  })
}

let visibility: DocumentVisibilityState = 'visible'

function setTabVisible(visible: boolean) {
  visibility = visible ? 'visible' : 'hidden'
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
}

function advance(seconds: number) {
  act(() => {
    vi.advanceTimersByTime(seconds * 1000)
  })
}

function articleRoot() {
  const root = document.createElement('article')
  const body = document.createElement('div')
  const end = document.createElement('div')
  end.setAttribute('data-article-end', '')
  body.append(end)
  root.append(body)
  return { current: root }
}

type Props = Parameters<typeof useArticleCompletion>[0]

function renderCompletion(overrides: Partial<Props> = {}) {
  const onReady = vi.fn()
  const initialProps: Props = {
    root: articleRoot(),
    active: true,
    minDwellSeconds: 80,
    sessionKey: 'swell-reading|authenticated:7',
    held: false,
    onReady,
    ...overrides,
  }
  const hook = renderHook((props: Props) => useArticleCompletion(props), { initialProps })
  const rerender = (next: Partial<Props>) => hook.rerender({ ...initialProps, ...next })
  return { onReady, rerender, unmount: hook.unmount }
}

describe('useArticleCompletion', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    FakeIntersectionObserver.instances = []
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    visibility = 'visible'
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('본문 끝 표지를 관찰하고, 끝까지 내려도 최소 머문 시간 전에는 완료하지 않는다', () => {
    const { onReady } = renderCompletion()

    expect(FakeIntersectionObserver.instances.at(-1)?.observed[0]?.hasAttribute('data-article-end')).toBe(
      true,
    )
    setEndVisible(true)
    advance(79)
    expect(onReady).not.toHaveBeenCalled()

    advance(1)
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('시간이 먼저 차도 본문 끝이 화면에 들어오는 순간에 완료한다', () => {
    const { onReady } = renderCompletion()

    advance(200)
    expect(onReady).not.toHaveBeenCalled()

    setEndVisible(true)
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('탭이 숨겨진 동안의 시간은 세지 않는다', () => {
    const { onReady } = renderCompletion()
    setEndVisible(true)

    advance(30)
    setTabVisible(false)
    advance(300)
    setTabVisible(true)
    advance(49)
    expect(onReady).not.toHaveBeenCalled()

    advance(1)
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('한 번만 부른다', () => {
    const { onReady } = renderCompletion()
    setEndVisible(true)
    advance(80)

    setEndVisible(false)
    setEndVisible(true)
    advance(200)

    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('보내도 되지 않는 동안에는 부르지 않고, 보내도 되면 그때 부른다', () => {
    const { onReady, rerender } = renderCompletion({ active: false })
    setEndVisible(true)
    advance(100)
    expect(onReady).not.toHaveBeenCalled()

    rerender({ active: true })
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('재확인 동안 두 조건이 차면 미뤘다가 같은 상태로 확인되면 보낸다', () => {
    const { onReady, rerender } = renderCompletion()
    setEndVisible(true)
    advance(60)

    rerender({ held: true })
    advance(30)
    expect(onReady).not.toHaveBeenCalled()

    rerender({ held: false })
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('checking이나 error를 거쳐 같은 사용자로 돌아오면 센 시간을 이어 간다', () => {
    const { onReady, rerender } = renderCompletion()
    setEndVisible(true)
    advance(60)

    // 재확인은 sessionKey를 바꾸지 않고 held만 켠다.
    rerender({ held: true })
    rerender({ held: false })
    advance(20)

    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('다른 사용자나 비로그인으로 바뀌면 처음부터 센다', () => {
    const { onReady, rerender } = renderCompletion()
    setEndVisible(true)
    advance(60)

    rerender({ sessionKey: 'swell-reading|authenticated:8' })
    setEndVisible(true)
    advance(60)
    expect(onReady).not.toHaveBeenCalled()

    advance(20)
    expect(onReady).toHaveBeenCalledTimes(1)

    rerender({ sessionKey: 'swell-reading|anonymous', active: false })
    rerender({ sessionKey: 'swell-reading|authenticated:8', active: true })
    setEndVisible(true)
    advance(79)
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('정리된 뒤 늦게 온 관찰 결과로는 판정하지 않는다', () => {
    const { onReady, rerender } = renderCompletion()
    advance(70)
    const stale = FakeIntersectionObserver.instances.at(-1)!

    rerender({ sessionKey: 'swell-reading|authenticated:8' })
    act(() => {
      stale.callback(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        stale as unknown as IntersectionObserver,
      )
    })
    advance(20)

    // 앞 사용자의 70초로 다음 사용자를 완료하지 않는다.
    expect(onReady).not.toHaveBeenCalled()
  })
})

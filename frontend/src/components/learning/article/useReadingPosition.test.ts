import { act, cleanup, renderHook } from '@testing-library/react'
import { AxiosHeaders } from 'axios'
import { StrictMode, createElement, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { focusReadPosition, scrollToReadPercent, useReadingPosition } from './useReadingPosition'

/**
 * 013 T190. 읽은 위치는 가장 큰 비율을 떠날 때마다 한 번 보낸다(FR-020, research R32·R33, quickstart 10-2·10-10).
 */

let visibility: DocumentVisibilityState = 'visible'
let bodyTop = 768

/** 본문 높이 1000px. 화면 아래 끝(768px)이 본문의 몇 %에 있는지가 읽은 비율이다. */
function scrollToPercent(percent: number) {
  bodyTop = 768 - percent * 10
  act(() => {
    window.dispatchEvent(new Event('scroll'))
  })
}

function setTabVisible(visible: boolean) {
  visibility = visible ? 'visible' : 'hidden'
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
}

function articleRoot() {
  const root = document.createElement('article')
  const body = document.createElement('div')
  const end = document.createElement('div')
  end.setAttribute('data-article-end', '')
  body.append(end)
  root.append(body)
  vi.spyOn(body, 'getBoundingClientRect').mockImplementation(
    () => ({ top: bodyTop, height: 1000 }) as DOMRect,
  )
  return { current: root }
}

type Props = Parameters<typeof useReadingPosition>[0]

function renderPosition(overrides: Partial<Props> = {}) {
  const initialProps: Props = {
    root: articleRoot(),
    articleId: 'swell-reading',
    active: true,
    sessionKey: 'authenticated:7',
    ...overrides,
  }
  const hook = renderHook((props: Props) => useReadingPosition(props), { initialProps })
  return { ...hook, rerender: (next: Partial<Props>) => hook.rerender({ ...initialProps, ...next }) }
}

function keepaliveBodies(fetchSpy: ReturnType<typeof vi.spyOn>) {
  return fetchSpy.mock.calls.map(([, init]: [unknown, RequestInit | undefined]) =>
    JSON.parse(String(init?.body)),
  )
}

describe('useReadingPosition', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>
  let put: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    visibility = 'visible'
    bodyTop = 768
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))
    vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { success: true, data: null, error: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: { headers: new AxiosHeaders() },
    })
    put = vi.spyOn(apiClient, 'put').mockResolvedValue({ status: 204 })
    document.cookie = 'XSRF-TOKEN=leave-token; path=/'
  })

  afterEach(async () => {
    // 화면을 내릴 때의 기록은 한 박자 미뤄 나가므로(StrictMode 대응) 다음 테스트로 새지 않게 여기서 비운다.
    cleanup()
    await new Promise((resolve) => setTimeout(resolve, 0))
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('화면 안 이동으로 떠나면 가장 큰 비율을 apiClient로 한 번 보낸다', async () => {
    const { unmount } = renderPosition()
    scrollToPercent(62)
    scrollToPercent(10)

    unmount()

    await vi.waitFor(() => expect(put).toHaveBeenCalledTimes(1))
    expect(put.mock.calls[0]).toEqual(['/learning/me/articles/swell-reading/position', { percent: 62 }])
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('비율이 그대로여도 떠날 때마다 보낸다(0%에서 떠나도 보낸다)', async () => {
    const { unmount } = renderPosition()

    unmount()

    await vi.waitFor(() => expect(put).toHaveBeenCalledTimes(1))
    expect(put.mock.calls[0][1]).toEqual({ percent: 0 })
  })

  it('탭을 숨기면 keepalive 요청에 X-XSRF-TOKEN을 싣고, 숨김과 닫기가 겹치면 한 번만 보낸다', () => {
    renderPosition()
    scrollToPercent(62)

    setTabVisible(false)
    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })

    expect(fetchSpy).toHaveBeenCalledTimes(1)
    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit]
    expect(init.keepalive).toBe(true)
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'leave-token' })
    expect(keepaliveBodies(fetchSpy)).toEqual([{ percent: 62 }])
  })

  it('탭이 다시 보였다가 또 떠나면 다시 보낸다', () => {
    renderPosition()
    scrollToPercent(30)
    setTabVisible(false)

    setTabVisible(true)
    scrollToPercent(45)
    setTabVisible(false)

    expect(keepaliveBodies(fetchSpy)).toEqual([{ percent: 30 }, { percent: 45 }])
  })

  it('비로그인에서는 떠날 때 fetch도 apiClient도 부르지 않는다', async () => {
    const { unmount } = renderPosition({ active: false, sessionKey: 'anonymous' })
    scrollToPercent(80)

    setTabVisible(false)
    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })
    unmount()
    await Promise.resolve()

    expect(fetchSpy).not.toHaveBeenCalled()
    expect(put).not.toHaveBeenCalled()
  })

  it('같은 sessionKey로 다시 그려지면 가장 큰 비율을 이어 간다', async () => {
    // 재확인(checking·error)이 sessionKey를 바꾸지 않는 것은 글 화면 테스트가 실제 로그인 상태로 본다.
    const { rerender, unmount } = renderPosition()
    scrollToPercent(62)

    rerender({})
    scrollToPercent(20)
    unmount()

    await vi.waitFor(() => expect(put).toHaveBeenCalledTimes(1))
    expect(put.mock.calls[0][1]).toEqual({ percent: 62 })
  })

  it('로그인 상태가 바뀌면 앞 사용자의 비율을 보내지 않고 처음부터 잰다', async () => {
    const { rerender, unmount } = renderPosition()
    scrollToPercent(80)
    scrollToPercent(15)

    // 새 사용자는 지금 화면 위치(15%)부터 잰다. 앞 사용자의 80%는 넘어오지 않는다.
    rerender({ sessionKey: 'authenticated:8' })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(put).not.toHaveBeenCalled()

    scrollToPercent(20)
    unmount()

    await vi.waitFor(() => expect(put).toHaveBeenCalledTimes(1))
    expect(put.mock.calls[0][1]).toEqual({ percent: 20 })
  })

  it('이 화면에서 잰 가장 큰 비율을 돌려준다', () => {
    const { result } = renderPosition()
    expect(result.current).toBe(0)

    scrollToPercent(45)
    scrollToPercent(30)

    expect(result.current).toBe(45)
  })

  it('개발 모드(StrictMode)가 효과를 다시 걸어도 글을 열자마자 위치를 보내지 않고, 떠날 때 한 번 보낸다', async () => {
    const strict = ({ children }: { children: ReactNode }) => createElement(StrictMode, null, children)
    const hook = renderHook((props: Props) => useReadingPosition(props), {
      initialProps: {
        root: articleRoot(),
        articleId: 'swell-reading',
        active: true,
        sessionKey: 'authenticated:7',
      },
      wrapper: strict,
    })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(put).not.toHaveBeenCalled()

    hook.unmount()

    await vi.waitFor(() => expect(put).toHaveBeenCalledTimes(1))
  })
})

describe('이어서 읽을 자리', () => {
  let scrollTo: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(300)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  /** 본문이 문서 위에서 500px(화면 위에서 200px), 높이 1000px이고 문단 셋이 300px씩 놓인다. */
  function bodyWithBlocks() {
    const root = document.createElement('article')
    const body = document.createElement('div')
    const blocks = [0, 1, 2].map((index) => {
      const block = document.createElement('p')
      block.textContent = `문단 ${index + 1}`
      vi.spyOn(block, 'getBoundingClientRect').mockReturnValue({
        top: 200 + index * 300,
        height: 300,
      } as DOMRect)
      body.append(block)
      return block
    })
    const end = document.createElement('div')
    end.setAttribute('data-article-end', '')
    body.append(end)
    root.append(body)
    document.body.append(root)
    vi.spyOn(body, 'getBoundingClientRect').mockReturnValue({ top: 200, height: 1000 } as DOMRect)
    return { root, blocks }
  }

  it('기록된 비율이 화면 아래 끝에 오도록 옮긴다', () => {
    const { root } = bodyWithBlocks()

    scrollToReadPercent(root, 62)

    // 문서 기준 본문 위(300 + 200) + 본문의 62%(620) − 화면 높이(768)
    expect(scrollTo).toHaveBeenCalledWith({ top: 352 })
    root.remove()
  })

  it('기록된 비율에 걸린 문단으로 초점을 옮기고 화면은 다시 움직이지 않는다', () => {
    const { root, blocks } = bodyWithBlocks()

    focusReadPosition(root, 62)

    // 62% 지점(화면 위에서 820px)은 셋째 문단(800px부터)에 걸린다.
    expect(document.activeElement).toBe(blocks[2])
    expect(blocks[2].getAttribute('tabindex')).toBe('-1')
    expect(scrollTo).not.toHaveBeenCalled()

    blocks[2].blur()
    expect(blocks[2].hasAttribute('tabindex')).toBe(false)
    root.remove()
  })
})

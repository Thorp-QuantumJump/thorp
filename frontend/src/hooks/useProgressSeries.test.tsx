import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProgressEvent, ProgressSubscriber } from '@/api/progressStream'
import { useProgressSeries } from './useProgressSeries'

const stream = vi.hoisted(() => ({ subscribe: vi.fn() }))
vi.mock('@/api/progressStream', async (load) => ({
  ...(await load<typeof import('@/api/progressStream')>()),
  subscribeProgress: stream.subscribe,
}))

const TOKEN = 'a'.repeat(32)

/** 구독자를 붙잡아 두고 이벤트를 손으로 밀어 넣는다. 해제 여부도 확인한다 */
function captureSubscriber() {
  const captured: { subscriber?: ProgressSubscriber; unsubscribed: boolean } = {
    unsubscribed: false,
  }
  stream.subscribe.mockImplementation((_token: string, subscriber: ProgressSubscriber) => {
    captured.subscriber = subscriber
    return () => {
      captured.unsubscribed = true
    }
  })
  return captured
}

/** `requestAnimationFrame`을 손으로 돌린다. 프레임마다 몇 점이 공개되는지 보기 위해서다 */
function installManualFrames() {
  const callbacks: FrameRequestCallback[] = []
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    callbacks.push(callback)
    return callbacks.length
  })
  vi.stubGlobal('cancelAnimationFrame', () => {})
  return {
    /** 대기 중인 콜백을 한 번씩 실행한다 */
    tick(times = 1) {
      for (let round = 0; round < times; round += 1) {
        const pending = callbacks.splice(0, callbacks.length)
        for (const callback of pending) callback(0)
      }
    },
  }
}

function progressEvent(dates: string[], completed: number, total: number): ProgressEvent {
  return {
    stage: 'COMPUTING',
    completedTradingDays: completed,
    totalTradingDays: total,
    lastTradingDate: dates[dates.length - 1],
    points: dates.map((date, index) => ({
      date,
      equity: String(10_000_000 + index * 1_000),
      cumulativeReturn: '0.001',
      drawdown: '0',
    })),
  }
}

/** 훅의 상태를 밖에서 읽기 위한 껍데기 */
function Probe({ token, active }: { token: string | null; active: boolean }) {
  const state = useProgressSeries(token, active)
  return (
    <output data-dates={state.points.map((point) => point.date).join(',')}>
      {state.finished ? 'finished' : 'running'}
      {state.progress ? `:${state.progress.completedTradingDays}/${state.progress.totalTradingDays}` : ''}
    </output>
  )
}

function dates(container: HTMLElement): string[] {
  const raw = container.querySelector('output')?.getAttribute('data-dates') ?? ''
  return raw === '' ? [] : raw.split(',')
}

describe('useProgressSeries', () => {
  let frames: ReturnType<typeof installManualFrames>

  beforeEach(() => {
    stream.subscribe.mockReset()
    frames = installManualFrames()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('계산 중이 아니면 구독하지 않는다', () => {
    captureSubscriber()

    render(<Probe active={false} token={TOKEN} />)

    expect(stream.subscribe).not.toHaveBeenCalled()
  })

  it('토큰이 없으면 구독하지 않는다', () => {
    captureSubscriber()

    render(<Probe active token={null} />)

    expect(stream.subscribe).not.toHaveBeenCalled()
  })

  it('한 묶음으로 받은 점을 한 프레임에 다 그리지 않고 나눠 공개한다', () => {
    const captured = captureSubscriber()
    const { container } = render(<Probe active token={TOKEN} />)

    const batch = progressEvent(
      Array.from({ length: 20 }, (_, index) => `2025-03-${String(index + 1).padStart(2, '0')}`),
      20,
      120,
    )
    act(() => captured.subscriber?.onProgress(batch))
    act(() => frames.tick())

    const shown = dates(container)
    expect(shown.length).toBeGreaterThan(0)
    expect(shown.length).toBeLessThan(20)
    // 공개한 것은 받은 순서의 앞쪽이다. 순서를 바꾸지 않는다.
    expect(shown[0]).toBe('2025-03-01')
  })

  it('프레임을 거듭하면 받은 점을 모두 공개한다', () => {
    const captured = captureSubscriber()
    const { container } = render(<Probe active token={TOKEN} />)

    act(() =>
      captured.subscriber?.onProgress(
        progressEvent(['2025-03-01', '2025-03-02', '2025-03-03'], 3, 120),
      ),
    )
    act(() => frames.tick(10))

    expect(dates(container)).toEqual(['2025-03-01', '2025-03-02', '2025-03-03'])
  })

  it('재전달된 점은 버린다 — 같은 날짜를 두 번 그리지 않는다', () => {
    const captured = captureSubscriber()
    const { container } = render(<Probe active token={TOKEN} />)

    act(() => captured.subscriber?.onProgress(progressEvent(['2025-03-01', '2025-03-02'], 2, 120)))
    act(() => frames.tick(10))
    // 브로커가 같은 이벤트를 다시 보냈다. 뒤의 한 점만 새것이다.
    act(() =>
      captured.subscriber?.onProgress(
        progressEvent(['2025-03-01', '2025-03-02', '2025-03-03'], 3, 120),
      ),
    )
    act(() => frames.tick(10))

    expect(dates(container)).toEqual(['2025-03-01', '2025-03-02', '2025-03-03'])
  })

  it('종결을 받으면 남은 대기열을 비우고 끝났음을 알린다', () => {
    const captured = captureSubscriber()
    const { container } = render(<Probe active token={TOKEN} />)

    const batch = progressEvent(
      Array.from({ length: 20 }, (_, index) => `2025-04-${String(index + 1).padStart(2, '0')}`),
      20,
      20,
    )
    act(() => captured.subscriber?.onProgress(batch))
    act(() => captured.subscriber?.onFinished('COMPLETED'))
    act(() => frames.tick())

    expect(dates(container)).toHaveLength(20)
    expect(container.querySelector('output')?.textContent).toContain('finished')
  })

  it('연결이 끊겨도 이미 그린 곡선은 지우지 않는다', () => {
    const captured = captureSubscriber()
    const { container } = render(<Probe active token={TOKEN} />)

    act(() => captured.subscriber?.onProgress(progressEvent(['2025-03-01'], 1, 120)))
    act(() => frames.tick(5))
    act(() => captured.subscriber?.onError())

    expect(dates(container)).toEqual(['2025-03-01'])
  })

  it('진행 보고의 거래일 수를 그대로 싣는다', () => {
    const captured = captureSubscriber()
    const { container } = render(<Probe active token={TOKEN} />)

    act(() => captured.subscriber?.onProgress(progressEvent(['2025-03-01'], 40, 120)))

    expect(container.querySelector('output')?.textContent).toContain('40/120')
  })

  it('화면을 떠나면 구독을 해제한다', () => {
    const captured = captureSubscriber()
    const { unmount } = render(<Probe active token={TOKEN} />)

    unmount()

    expect(captured.unsubscribed).toBe(true)
  })
})

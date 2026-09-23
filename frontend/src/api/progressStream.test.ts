import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { subscribeProgress } from './progressStream'

const TOKEN = 'a'.repeat(32)

/** 브라우저의 `EventSource`를 대신한다. jsdom 에는 이 API 가 없다 */
class FakeEventSource {
  static opened: FakeEventSource[] = []

  readonly url: string
  closed = false
  private readonly handlers = new Map<string, ((event: unknown) => void)[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.opened.push(this)
  }

  addEventListener(type: string, handler: (event: unknown) => void) {
    const current = this.handlers.get(type) ?? []
    current.push(handler)
    this.handlers.set(type, current)
  }

  close() {
    this.closed = true
  }

  /** 서버가 이벤트를 보낸 것처럼 만든다 */
  emit(type: string, data: string) {
    for (const handler of this.handlers.get(type) ?? []) handler({ data })
  }
}

function subscriberSpy() {
  return { onProgress: vi.fn(), onFinished: vi.fn(), onError: vi.fn() }
}

function progressBody(overrides: Record<string, unknown> = {}) {
  return JSON.stringify({
    stage: 'COMPUTING',
    completedTradingDays: 20,
    totalTradingDays: 120,
    lastTradingDate: '2025-03-20',
    points: [
      { date: '2025-03-19', equity: '10010000', cumulativeReturn: '0.001', drawdown: '0' },
      { date: '2025-03-20', equity: '10020000', cumulativeReturn: '0.002', drawdown: '0' },
    ],
    ...overrides,
  })
}

describe('진행 시계열 구독(subscribeProgress)', () => {
  beforeEach(() => {
    FakeEventSource.opened = []
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('공개 토큰 경로로 연결한다', () => {
    subscribeProgress(TOKEN, subscriberSpy())

    expect(FakeEventSource.opened).toHaveLength(1)
    expect(FakeEventSource.opened[0].url).toBe(`/api/results/${TOKEN}/progress`)
  })

  it.each(['..', '../results/1', 'ABCDEF0123456789ABCDEF0123456789', `${TOKEN}0`, ''])(
    '형식이 틀린 토큰(%s)은 연결하지 않는다',
    (token) => {
      subscribeProgress(token, subscriberSpy())

      expect(FakeEventSource.opened).toHaveLength(0)
    },
  )

  it('EventSource 가 없는 환경에서는 연결하지 않고 예외도 올리지 않는다', () => {
    vi.stubGlobal('EventSource', undefined)

    const unsubscribe = subscribeProgress(TOKEN, subscriberSpy())

    expect(() => unsubscribe()).not.toThrow()
  })

  it('progress 이벤트의 점을 그대로 넘긴다', () => {
    const subscriber = subscriberSpy()
    subscribeProgress(TOKEN, subscriber)

    FakeEventSource.opened[0].emit('progress', progressBody())

    expect(subscriber.onProgress).toHaveBeenCalledTimes(1)
    const event = subscriber.onProgress.mock.calls[0][0]
    expect(event.points.map((point: { date: string }) => point.date)).toEqual([
      '2025-03-19',
      '2025-03-20',
    ])
    expect(event.completedTradingDays).toBe(20)
    expect(event.totalTradingDays).toBe(120)
  })

  it.each([
    ['JSON 이 아닌 본문', '{'],
    ['points 가 배열이 아닌 본문', progressBody({ points: null })],
    ['거래일 수가 없는 본문', progressBody({ completedTradingDays: undefined })],
  ])('읽을 수 없는 %s은 버린다 — 지어낸 점을 곡선에 넣지 않는다', (_name, body) => {
    const subscriber = subscriberSpy()
    subscribeProgress(TOKEN, subscriber)

    FakeEventSource.opened[0].emit('progress', body)

    expect(subscriber.onProgress).not.toHaveBeenCalled()
  })

  it('자산가치가 비어 있는 점이 섞이면 그 이벤트 전체를 버린다', () => {
    const subscriber = subscriberSpy()
    subscribeProgress(TOKEN, subscriber)

    FakeEventSource.opened[0].emit(
      'progress',
      progressBody({
        points: [
          { date: '2025-03-19', equity: '10010000', cumulativeReturn: '0.001', drawdown: '0' },
          { date: '2025-03-20', cumulativeReturn: '0.002', drawdown: '0' },
        ],
      }),
    )

    expect(subscriber.onProgress).not.toHaveBeenCalled()
  })

  it('finished 이벤트를 받으면 알리고 연결을 닫는다', () => {
    const subscriber = subscriberSpy()
    subscribeProgress(TOKEN, subscriber)

    FakeEventSource.opened[0].emit('finished', 'COMPLETED')

    expect(subscriber.onFinished).toHaveBeenCalledWith('COMPLETED')
    expect(FakeEventSource.opened[0].closed).toBe(true)
  })

  it('연결 오류를 알린다 — 다시 붙는 일은 EventSource 가 한다', () => {
    const subscriber = subscriberSpy()
    subscribeProgress(TOKEN, subscriber)

    FakeEventSource.opened[0].emit('error', '')

    expect(subscriber.onError).toHaveBeenCalledTimes(1)
    expect(FakeEventSource.opened[0].closed).toBe(false)
  })

  it('해제하면 연결을 닫는다', () => {
    const unsubscribe = subscribeProgress(TOKEN, subscriberSpy())

    unsubscribe()

    expect(FakeEventSource.opened[0].closed).toBe(true)
  })
})

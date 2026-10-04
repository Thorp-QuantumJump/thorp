import { act, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useNearViewport } from './useNearViewport'

function Probe() {
  const ref = useRef<HTMLDivElement>(null)
  const near = useNearViewport(ref)
  return <div ref={ref}>{near ? '가까움' : '멂'}</div>
}

/** 화면에 들어왔다고 알릴 수 있는 관찰자 대역. jsdom에는 IntersectionObserver가 없다. */
class FakeObserver {
  static instances: FakeObserver[] = []
  readonly options: IntersectionObserverInit | undefined
  readonly observed: Element[] = []
  disconnected = false

  constructor(private readonly callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
    this.options = options
    FakeObserver.instances.push(this)
  }

  observe(element: Element) {
    this.observed.push(element)
  }

  disconnect() {
    this.disconnected = true
  }

  enter(isIntersecting: boolean) {
    const entries = this.observed.map((target) => ({ target, isIntersecting }) as IntersectionObserverEntry)
    this.callback(entries, this as unknown as IntersectionObserver)
  }
}

describe('useNearViewport', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    FakeObserver.instances = []
  })

  it('IntersectionObserver가 없는 환경에서는 바로 참이다', () => {
    render(<Probe />)

    expect(screen.getByText('가까움')).toBeTruthy()
  })

  it('화면 가까이 오기 전에는 거짓이고, 들어오면 참이 되어 관찰을 그만둔다', () => {
    vi.stubGlobal('IntersectionObserver', FakeObserver)
    render(<Probe />)

    expect(screen.getByText('멂')).toBeTruthy()
    const observer = FakeObserver.instances[0]
    expect(observer.options?.rootMargin).toBe('600px')

    act(() => observer.enter(false))
    expect(screen.getByText('멂')).toBeTruthy()

    act(() => observer.enter(true))
    expect(screen.getByText('가까움')).toBeTruthy()
    expect(observer.disconnected).toBe(true)
  })
})

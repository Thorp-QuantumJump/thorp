import { act, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { allDone, learnerOverview } from '@/test/learningMeFixtures'
import type { ContinueReading } from '@/types/learningMe'
import { FloatingContinue } from './FloatingContinue'

/**
 * 013 T192·T196. 떠 있는 '이어서 읽기'(FR-020, design-notes §3.1, quickstart 11-3). 목록 보기에 없다는 것은
 * `LearningListPage.test.tsx`가 본다.
 */

/** 이 파일 안에서만 쓰는 `IntersectionObserver` 대역. 대상이 화면에 보이는지를 손으로 알린다. */
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = []
  targets: Element[] = []
  constructor(private readonly callback: IntersectionObserverCallback) {
    FakeIntersectionObserver.instances.push(this)
  }
  observe(target: Element) {
    this.targets.push(target)
  }
  disconnect() {
    this.targets = []
  }
  unobserve() {}
  takeRecords() {
    return []
  }
  report(isIntersecting: boolean) {
    const entries = this.targets.map(
      (target) => ({ target, isIntersecting }) as IntersectionObserverEntry,
    )
    this.callback(entries, this as unknown as IntersectionObserver)
  }
}

function report(isIntersecting: boolean) {
  act(() => {
    for (const observer of FakeIntersectionObserver.instances) observer.report(isIntersecting)
  })
}

function Harness({
  continueReading,
  concealed = false,
}: {
  continueReading: ContinueReading
  concealed?: boolean
}) {
  const ref = useRef<HTMLAnchorElement>(null)
  return (
    <MemoryRouter>
      <a ref={ref} href="#panel">
        패널의 이어서 읽기
      </a>
      <FloatingContinue continueReading={continueReading} targetRef={ref} concealed={concealed} />
    </MemoryRouter>
  )
}

describe('FloatingContinue', () => {
  beforeEach(() => {
    FakeIntersectionObserver.instances = []
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('패널의 이어서 읽기가 화면에 보이는 동안에는 없고, 화면 밖으로 나가면 나타난다', () => {
    render(<Harness continueReading={learnerOverview.continueReading} />)
    expect(screen.queryByRole('link', { name: /이어서 읽기 2-1/ })).toBeNull()

    report(false)
    expect(screen.getByRole('link', { name: '이어서 읽기 2-1' }).getAttribute('href')).toBe(
      '/learn/tide-reading',
    )

    report(true)
    expect(screen.queryByRole('link', { name: /이어서 읽기 2-1/ })).toBeNull()
  })

  it('공개된 글을 모두 읽었으면 단추가 없다', () => {
    render(<Harness continueReading={allDone} />)
    report(false)
    // 패널의 연결 하나만 있다.
    expect(screen.getAllByRole('link')).toHaveLength(1)
  })

  it('로그인 상태를 다시 확인하는 동안에는 숨긴다', () => {
    render(<Harness continueReading={learnerOverview.continueReading} concealed />)
    report(false)
    expect(screen.queryByRole('link', { name: /이어서 읽기 2-1/ })).toBeNull()
  })

  it('화면 위치를 잴 수 없으면 그리지 않는다', () => {
    vi.unstubAllGlobals()
    vi.stubGlobal('IntersectionObserver', undefined)
    render(<Harness continueReading={learnerOverview.continueReading} />)
    expect(screen.queryByRole('link', { name: /이어서 읽기 2-1/ })).toBeNull()
  })
})

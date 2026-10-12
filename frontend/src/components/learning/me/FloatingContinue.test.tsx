import { act, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
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

const CSS = 'components/learning/me/FloatingContinue.module.css'
const ACTION_CSS = 'components/learning/me/greenAction.module.css'

function RoutedHarness() {
  const ref = useRef<HTMLAnchorElement>(null)
  return (
    <>
      <a ref={ref} href="#panel">
        패널의 이어서 읽기
      </a>
      <FloatingContinue
        continueReading={learnerOverview.continueReading}
        targetRef={ref}
        concealed={false}
      />
    </>
  )
}

function StateProbe() {
  const location = useLocation()
  const state = location.state as { learningResume?: boolean } | null
  return (
    <p data-testid="state">{`${location.pathname} ${state?.learningResume ? 'resume' : ''}`}</p>
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

  it('로그인 상태를 다시 확인하는 동안에는 내리지 않고 숨겼다가 확인이 끝나면 그대로 보인다(R33)', () => {
    const { rerender } = render(<Harness continueReading={learnerOverview.continueReading} />)
    report(false)
    const link = screen.getByRole('link', { name: '이어서 읽기 2-1' })

    rerender(<Harness continueReading={learnerOverview.continueReading} concealed />)
    expect(screen.getByRole('link', { name: '이어서 읽기 2-1', hidden: true })).toBe(link)
    expect(link.className).toMatch(/concealed/)
    expect(cssDeclarations(CSS, '.concealed')).toMatch(/visibility:\s*hidden/)

    rerender(<Harness continueReading={learnerOverview.continueReading} />)
    expect(screen.getByRole('link', { name: '이어서 읽기 2-1' })).toBe(link)
    expect(link.className).not.toMatch(/concealed/)
  })

  it('읽는 중 글이면 기록된 위치로 옮기도록 learningResume을 싣는다', async () => {
    render(
      <MemoryRouter initialEntries={['/learn']}>
        <Routes>
          <Route path="/learn" element={<RoutedHarness />} />
          <Route path="*" element={<StateProbe />} />
        </Routes>
      </MemoryRouter>,
    )
    report(false)

    await userEvent.click(screen.getByRole('link', { name: '이어서 읽기 2-1' }))
    expect(screen.getByTestId('state').textContent).toBe('/learn/tide-reading resume')
  })

  it('초점 외곽선은 초록이고, 단추가 있는 동안 아래 스크롤 여백을 두며, 좁은 화면에서는 가로 막대다', () => {
    // 초록 단추의 색·그림자·초점은 이어서 읽기 칸과 같은 규칙이다. 떠 있는 단추는 그림자 한 겹만 더한다.
    render(<Harness continueReading={learnerOverview.continueReading} />)
    report(false)
    const link = screen.getByRole('link', { name: '이어서 읽기 2-1' })
    expect(link.className).toMatch(/action/)
    expect(cssDeclarations(ACTION_CSS, '.action:focus-visible')).toMatch(
      /outline:\s*2px solid var\(--thorp-brand-soft\)/,
    )
    expect(cssDeclarations(CSS, '.fab')).toMatch(/--action-lift:/)
    expect(cssDeclarations(CSS, ':global(html):has(.fab)')).toMatch(/scroll-padding-bottom:\s*88px/)
    expect(cssMediaDeclarations(CSS, '(max-width: 719px)', '.fab')).toMatch(/left:\s*16px/)
  })

  it('화면 위치를 잴 수 없으면 그리지 않는다', () => {
    vi.unstubAllGlobals()
    vi.stubGlobal('IntersectionObserver', undefined)
    render(<Harness continueReading={learnerOverview.continueReading} />)
    expect(screen.queryByRole('link', { name: /이어서 읽기 2-1/ })).toBeNull()
  })
})

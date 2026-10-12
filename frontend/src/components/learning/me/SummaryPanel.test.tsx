import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import { allDone, learnerOverview } from '@/test/learningMeFixtures'
import type { LearnerOverview } from '@/types/learningMe'
import { useSettledAuth } from '../article/useSettledAuth'
import { SummaryPanel } from './SummaryPanel'
import { useLearnerOverview } from '@/hooks/useLearnerOverview'

/**
 * 013 T191·T192·T196. 요약 패널과 '나의 학습' 조회 훅(FR-020·FR-030, research R33, quickstart 11-3·11-4).
 */

const meMocks = vi.hoisted(() => ({ fetchLearningMe: vi.fn() }))
vi.mock('@/api/learningMe', () => meMocks)

const failed = {
  status: 'failed',
  kind: 'network',
  code: null,
  message: '연결하지 못했습니다.',
} as const

const signedIn: AuthContextValue = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: authenticatedUser,
}
const otherUser: AuthContextValue = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: { ...authenticatedUser, userId: 8, nickname: '다른사용자' },
}
const checking: AuthContextValue = { ...anonymousAuthContext, status: 'checking', user: null }

let probedState: unknown

function Probe() {
  const location = useLocation()
  probedState = location.state
  return <p>열린 글 {location.pathname}</p>
}

function Harness({ enabled = true }: { enabled?: boolean }) {
  const { settled, concealed } = useSettledAuth()
  const overview = useLearnerOverview({ auth: settled, enabled })
  return <SummaryPanel state={overview.state} concealed={concealed} onRetry={overview.retry} />
}

function tree(auth: AuthContextValue, enabled = true) {
  return (
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/learn']}>
        <Routes>
          <Route path="/learn" element={<Harness enabled={enabled} />} />
          <Route path="*" element={<Probe />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

function loaded(overview: LearnerOverview = learnerOverview) {
  meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: overview })
}

describe('SummaryPanel', () => {
  beforeEach(() => {
    meMocks.fetchLearningMe.mockReset()
    probedState = undefined
  })

  it('레벨과 다음 레벨까지, 읽은 글·익힌 용어(분모 없음), 이어서 읽기, 모두 보기를 보인다', async () => {
    loaded()
    render(tree(signedIn))

    const panel = await screen.findByRole('region', { name: '나의 학습' })
    expect(within(panel).getByText(/레벨 2/)).not.toBeNull()
    expect(within(panel).getByText('새로 닿은 레벨')).not.toBeNull()
    expect(panel.textContent).toContain('다음 레벨까지 50')
    expect(panel.textContent).toContain('읽은 글2편')
    expect(panel.textContent).toContain('익힌 용어3개')
    // 분모를 쓰지 않는다(FR-020).
    expect(panel.textContent).not.toMatch(/2\s*\/\s*4|3\s*\/\s*5/)
    expect(within(panel).getByRole('link', { name: '모두 보기' }).getAttribute('href')).toBe(
      '/learn/me',
    )
    expect(within(panel).getByRole('link', { name: '이어서 읽기' }).getAttribute('href')).toBe(
      '/learn/tide-reading',
    )
    expect(panel.textContent).toContain('62% 읽는 중')
  })

  it('7일 안의 배지는 레벨 갈래를 빼고 새 배지 한 줄과 새 알약으로 보인다', async () => {
    loaded()
    render(tree(signedIn))

    const line = await screen.findByText('새 배지: 1단계 완주')
    expect(line.parentElement?.textContent).toContain('새로 받음')
  })

  it('7일 안의 배지가 없으면 새 배지 줄이 없다', async () => {
    loaded({
      ...learnerOverview,
      level: { ...learnerOverview.level, isNew: false },
      badges: learnerOverview.badges.map((badge) => ({ ...badge, isNew: false })),
    })
    render(tree(signedIn))

    await screen.findByRole('region', { name: '나의 학습' })
    expect(screen.queryByText(/새 배지/)).toBeNull()
    expect(screen.queryByText('새로 닿은 레벨')).toBeNull()
  })

  it("'이어서 읽기'는 읽는 중 글이면 learningResume을 실어 연다", async () => {
    loaded()
    render(tree(signedIn))

    await userEvent.click(await screen.findByRole('link', { name: '이어서 읽기' }))

    expect(await screen.findByText('열린 글 /learn/tide-reading')).not.toBeNull()
    expect(probedState).toEqual({ learningResume: true })
  })

  it('공개된 글을 모두 읽었으면 단추 대신 문장이다', async () => {
    loaded({ ...learnerOverview, continueReading: allDone })
    render(tree(signedIn))

    const panel = await screen.findByRole('region', { name: '나의 학습' })
    expect(panel.textContent).toContain(
      '지금 공개된 글을 모두 읽었습니다. 다음 단계는 준비 중입니다.',
    )
    expect(within(panel).queryByRole('link', { name: '이어서 읽기' })).toBeNull()
  })

  it('받지 못하면 실패 한 줄과 다시 시도만 보이고, 조용히 다시 부르지 않는다(quickstart 11-4)', async () => {
    meMocks.fetchLearningMe.mockResolvedValue(failed)
    render(tree(signedIn))

    expect(await screen.findByText('학습 기록을 불러오지 못했습니다.')).not.toBeNull()
    expect(screen.queryByText(/레벨/)).toBeNull()
    expect(screen.queryByText(/0편|안 읽음/)).toBeNull()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)

    loaded()
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText(/레벨 2/)).not.toBeNull()
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(2)
  })

  it('비로그인이거나 첫 단계 공개 전이면 부르지 않고 그리지 않는다', async () => {
    loaded()
    const { unmount } = render(tree(anonymousAuthContext))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(screen.queryByRole('region', { name: '나의 학습' })).toBeNull()
    unmount()

    render(tree(signedIn, false))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()
  })

  it('재확인 동안 숨겼다가 같은 userId면 다시 부르지 않고, 다른 userId면 버리고 다시 부른다(R33)', async () => {
    loaded()
    const { rerender } = render(tree(signedIn))
    const panel = await screen.findByRole('region', { name: '나의 학습' })
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)

    rerender(tree(checking))
    // 자리를 둔 채 숨긴다. 패널을 내리지 않는다.
    expect(screen.getByRole('region', { name: '나의 학습', hidden: true })).toBe(panel)
    expect(panel.className).toMatch(/concealed/)

    rerender(tree(signedIn))
    expect(screen.getByRole('region', { name: '나의 학습' })).toBe(panel)
    expect(panel.className).not.toMatch(/concealed/)
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)

    let resolveOther: (value: unknown) => void = () => {}
    meMocks.fetchLearningMe.mockReturnValue(new Promise((resolve) => (resolveOther = resolve)))
    rerender(tree(otherUser))
    // 앞 사용자의 기록을 한 번도 그리지 않는다.
    expect(screen.queryByRole('region', { name: '나의 학습' })).toBeNull()
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(2)
    await act(async () =>
      resolveOther({
        status: 'ok',
        data: { ...learnerOverview, counts: { ...learnerOverview.counts, completedArticles: 7 } },
      }),
    )
    expect((await screen.findByRole('region', { name: '나의 학습' })).textContent).toContain(
      '읽은 글7편',
    )
  })

  it('이번 조회에서 생긴 배지·레벨은 초점을 옮기지 않는 상태 알림으로 읽힌다(FR-030)', async () => {
    loaded({
      ...learnerOverview,
      gained: {
        exp: [{ kind: 'STAGE_COMPLETED', points: 50, stageNumber: 2 }],
        expTotal: 50,
        badges: [{ badgeKey: 'stage-swell-and-risk', family: 'STAGE', name: '2단계 완주' }],
        levelBefore: 2,
        levelAfter: 3,
      },
    })
    render(tree(signedIn))

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toContain(
        '새 배지: 2단계 완주. 레벨 3이 되었습니다.',
      ),
    )
    expect(document.activeElement).toBe(document.body)
  })
})

describe('SummaryPanel — 로그인 상태와 비는 자리', () => {
  beforeEach(() => {
    meMocks.fetchLearningMe.mockReset()
  })

  it('error 동안에도 숨기기만 하고 같은 userId로 확인되면 다시 부르지 않는다(R33)', async () => {
    loaded()
    const { rerender } = render(tree(signedIn))
    const panel = await screen.findByRole('region', { name: '나의 학습' })

    rerender(tree({ ...anonymousAuthContext, status: 'error', user: null }))
    expect(panel.className).toMatch(/concealed/)
    rerender(tree(signedIn))
    expect(screen.getByRole('region', { name: '나의 학습' })).toBe(panel)
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)
  })

  it('로그아웃을 시작하면 그 즉시 패널을 버린다(014 FR-009)', async () => {
    loaded()
    const { rerender } = render(tree(signedIn))
    await screen.findByRole('region', { name: '나의 학습' })

    rerender(tree({ ...anonymousAuthContext, status: 'logoutUnconfirmed', user: null }))
    expect(screen.queryByRole('region', { name: '나의 학습', hidden: true })).toBeNull()
  })

  it('확인이 끝나면 숨기기 전에 패널 안에 있던 초점을 되돌린다(FR-019)', async () => {
    loaded()
    const { rerender } = render(tree(signedIn))
    const more = await screen.findByRole('link', { name: '모두 보기' })
    more.focus()

    rerender(tree(checking))
    // 숨긴 요소의 초점은 브라우저가 문서 몸통으로 옮긴다. jsdom은 옮기지 않으므로 같은 일을 한다.
    more.blur()
    rerender(tree(signedIn))
    expect(document.activeElement).toBe(more)
  })

  it("공개된 글이 0편이면 '이어서 읽기' 칸을 두지 않는다(계약 §1)", async () => {
    loaded({
      ...learnerOverview,
      counts: { ...learnerOverview.counts, completedArticles: 0, publishedArticles: 0 },
      continueReading: { ...allDone, moreStagesPreparing: true },
    })
    render(tree(signedIn))

    const panel = await screen.findByRole('region', { name: '나의 학습' })
    expect(panel.textContent).not.toContain('이어서 읽기')
    expect(panel.textContent).not.toContain('모두 읽었습니다')
  })

  it('다음 레벨에 지금 닿을 수 없으면 FR-029 문장을 덧붙인다(FR-031)', async () => {
    loaded({
      ...learnerOverview,
      level: { ...learnerOverview.level, current: 3, visibleMax: 3, unreachableFrom: 4 },
    })
    render(tree(signedIn))

    const panel = await screen.findByRole('region', { name: '나의 학습' })
    expect(panel.textContent).toContain('레벨 4부터는 단계가 더 공개된 뒤 닿을 수 있습니다.')
  })
})

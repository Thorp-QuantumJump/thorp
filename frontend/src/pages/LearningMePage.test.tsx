import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CurriculumResult } from '@/api/learning'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { preReleaseCurriculum, releasedCurriculum } from '@/test/learningFixtures'
import { learnerOverview } from '@/test/learningMeFixtures'
import { LearningMePage } from './LearningMePage'

/**
 * 013 T194·T196. '나의 학습' 화면(FR-020·FR-024·FR-025·FR-029·FR-030, quickstart 10-8·11-5·11-6).
 */

const apiMocks = vi.hoisted(() => ({ fetchCurriculum: vi.fn() }))
const meMocks = vi.hoisted(() => ({ fetchLearningMe: vi.fn() }))
vi.mock('@/api/learning', () => apiMocks)
vi.mock('@/api/learningMe', () => meMocks)
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

const signedIn: AuthContextValue = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: authenticatedUser,
}

function Probe() {
  const location = useLocation()
  return <p>다른 화면 {location.pathname}</p>
}

function renderMe(auth: AuthContextValue = signedIn) {
  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/learn/me']}>
        <Routes>
          <Route path="/learn/me" element={<LearningMePage />} />
          <Route path="*" element={<Probe />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

describe('LearningMePage', () => {
  beforeEach(() => {
    apiMocks.fetchCurriculum.mockReset()
    apiMocks.fetchCurriculum.mockResolvedValue({
      status: 'loaded',
      curriculum: releasedCurriculum,
    } satisfies CurriculumResult)
    meMocks.fetchLearningMe.mockReset()
    meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: learnerOverview })
  })

  it('FR-020 순서대로 레벨 → 숫자 줄 → 단계별 진행 → 이어서 읽기 → 익힌 용어 → 배지 → 변경 기록이다', async () => {
    renderMe()

    const level = await screen.findByRole('heading', { level: 2, name: /레벨 2/ })
    const headings = screen
      .getAllByRole('heading', { level: 2 })
      .map((heading) => heading.textContent ?? '')
    expect(headings.slice(0, 4).map((text) => text.replace(/(누르면|받은 배지).*/, ''))).toEqual([
      level.textContent,
      '단계별 진행',
      '익힌 용어',
      '배지',
    ])
    const text = document.body.textContent ?? ''
    const order = [
      '레벨 2',
      '읽은 글2편',
      '단계별 진행',
      '62% 읽는 중',
      '익힌 용어누르면',
      '받은 배지 5개',
      '변경 기록',
    ]
    const positions = order.map((part) => text.indexOf(part))
    expect(positions.every((position) => position >= 0)).toBe(true)
    expect([...positions].sort((a, b) => a - b)).toEqual(positions)
  })

  it('숫자 줄은 분모 없이 수로만 보이고 지금 공개된 수를 따로 한 문장으로 덧붙인다', async () => {
    renderMe()

    await screen.findByText('지금 공개된 글은 4편입니다.')
    expect(screen.getByText('지금 공개된 용어는 5개입니다.')).not.toBeNull()
    const text = document.body.textContent ?? ''
    expect(text).toContain('읽은 글2편')
    expect(text).toContain('익힌 용어3개')
    expect(text).toContain('받은 배지5개')
    expect(text).not.toMatch(/2\s*\/\s*4편|5\s*\/\s*31/)
  })

  it('레벨 길 뒤에 닿을 수 없는 레벨의 문장이 있고 도착·최고 레벨 표시는 없다(quickstart 10-8)', async () => {
    renderMe()

    expect(
      await screen.findByText('레벨 4부터는 단계가 더 공개된 뒤 닿을 수 있습니다.'),
    ).not.toBeNull()
    expect(document.body.textContent).not.toMatch(/도착|최고 레벨/)
    expect(document.body.textContent).toContain('경험치 250 · 다음 레벨까지 50')
  })

  it('단계별 진행은 공개된 단계의 읽은 편수와 완주, 준비 중 단계를 보인다', async () => {
    renderMe()

    expect(await screen.findByText('완주 · 2편')).not.toBeNull()
    expect(screen.getByText('2편 중 0편 읽음')).not.toBeNull()
    const link = screen.getByRole('link', { name: '1단계 커리큘럼에서 보기' })
    expect(link.getAttribute('href')).toBe('/learn#stage-1')
    expect(screen.getAllByText('준비 중').length).toBeGreaterThanOrEqual(4)
  })

  it('정거장 사이의 실선과 준비 중으로 가는 점선은 강제 색 모드에서도 남는다', () => {
    const css = 'components/learning/me/StageStations.module.css'
    // 기본 모습은 바탕색·그라디언트로 그린 선이라 강제 색 모드에서 지워진다. 그 모드에서 다시 그린다.
    expect(cssDeclarations(css, '.dashed::before')).toMatch(/repeating-linear-gradient/)
    expect(cssMediaDeclarations(css, '(forced-colors: active)', '.station::before')).toMatch(
      /background:\s*CanvasText/,
    )
    const dashed = cssMediaDeclarations(css, '(forced-colors: active)', '.dashed::before')
    expect(dashed).toMatch(/border-top:\s*3px dashed CanvasText/)
    expect(dashed).not.toMatch(/gradient/)
  })

  it('익힌 용어를 누르면 그 글로 간다', async () => {
    renderMe()

    const term = await screen.findByRole('link', { name: /^항구1-1/ })
    expect(term.getAttribute('href')).toBe('/learn/harbor-notes')
    expect(
      screen.getByText('글의 확인 문제를 하나 이상 맞히면 그 글의 용어를 익힌 것으로 셉니다.'),
    ).not.toBeNull()
  })

  it('배지는 받음·아직·준비 중 글자를 보이고, 준비 중은 사유가 같은 것끼리 한 줄, 단계 완주는 낱장이다', async () => {
    renderMe()

    const read = await screen.findByRole('region', { name: '읽은 글' })
    expect(within(read).getByText('받음 2026-11-01')).not.toBeNull()
    expect(
      within(read).getByText(
        '준비 중인 기준: 읽은 글 5편, 10편. 단계가 더 공개된 뒤 받을 수 있습니다.',
      ),
    ).not.toBeNull()
    expect(
      within(read).getByText(
        '읽은 글 27편 · 준비 중 · 여섯 단계가 모두 공개된 뒤 받을 수 있습니다.',
      ),
    ).not.toBeNull()
    // 받은 배지의 기준 문장도 글자로 있다. 화면에서는 이름에 맡기고 화면 낭독기에만 읽힌다(FR-019).
    expect(within(read).getByText('읽은 글 1편 기준 문장이다.').className).toBe('sr-only')

    const correct = screen.getByRole('region', { name: '첫 답 정답' })
    expect(within(correct).getByText('아직 받지 않음, 10개 중 3개')).not.toBeNull()

    const stage = screen.getByRole('region', { name: '단계 완주' })
    expect(within(stage).getAllByRole('listitem')).toHaveLength(3)
    expect(within(stage).getByText('3단계가 공개된 뒤 받을 수 있습니다.')).not.toBeNull()
    expect(within(stage).getByText('준비 중')).not.toBeNull()

    const level = screen.getByRole('region', { name: '레벨' })
    expect(
      within(level).getByText('레벨 4 · 준비 중 · 단계가 더 공개된 뒤 받을 수 있습니다.'),
    ).not.toBeNull()
  })

  it('특별 배지는 준비 중에도 낱장으로 기준과 받을 수 있게 되는 때를 보인다(FR-024)', async () => {
    renderMe()

    const special = await screen.findByRole('region', { name: '특별' })
    expect(within(special).getAllByRole('listitem')).toHaveLength(2)
    const exploration = within(special).getByText('탐험').closest('li')!
    expect(exploration.textContent).toContain('준비 중')
    expect(exploration.textContent).toContain('탐험 기준 문장이다.')
    expect(exploration.textContent).toContain('여섯 단계가 모두 공개된 뒤 받을 수 있습니다.')
  })

  it('이 화면의 메달에는 지나가는 광택이 없다(design-notes §5)', async () => {
    renderMe()

    await screen.findByRole('region', { name: '단계 완주' })
    expect(document.querySelector('[class*="sweep"]')).toBeNull()
  })

  it('로그인이 만료된 동안은 기록 대신 재로그인 안내와 쌓이는 것 세 칸을 보인다(research R33)', async () => {
    renderMe({ ...anonymousAuthContext, status: 'expired', user: null })

    expect(
      await screen.findByText('로그인이 만료되었습니다. 다시 로그인하면 이 화면으로 돌아옵니다.'),
    ).not.toBeNull()
    expect(screen.getByRole('link', { name: '다시 로그인' }).getAttribute('href')).toBe('/login')
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
    expect(screen.getByRole('region', { name: '나의 학습에 쌓이는 것' })).not.toBeNull()
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()
  })

  it("7일 안에 받은 배지와 닿은 레벨에 '새'가 붙고 닫는 단추가 없다", async () => {
    renderMe()

    const stage = await screen.findByRole('region', { name: '단계 완주' })
    expect(within(stage).getByText('새로 받음')).not.toBeNull()
    expect(screen.getByText('새로 닿은 레벨')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /닫기|봤음/ })).toBeNull()
  })

  it('경험치 내역과 학습 초기화 단추가 없다(FR-020, FR-025)', async () => {
    renderMe()

    await screen.findByRole('region', { name: /^배지/ })
    expect(document.body.textContent).not.toMatch(/\+20|\+50|경험치 내역/)
    expect(screen.queryByRole('button', { name: '학습 초기화' })).toBeNull()
  })

  it('기록을 받지 못하면 실패 한 줄과 다시 시도만 보인다', async () => {
    meMocks.fetchLearningMe.mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '',
    })
    renderMe()

    expect(await screen.findByText('학습 기록을 불러오지 못했습니다.')).not.toBeNull()
    expect(screen.queryByText(/레벨/)).toBeNull()
    expect(screen.queryByText('단계별 진행')).toBeNull()

    meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: learnerOverview })
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('단계별 진행')).not.toBeNull()
  })

  it("비로그인은 한 줄·로그인 연결·견본과 '나의 학습에 쌓이는 것' 세 칸이고 숫자·상태가 없다(quickstart 11-6)", async () => {
    renderMe(anonymousAuthContext)

    expect(
      await screen.findByRole('heading', { name: '로그인하면 학습 기록이 남습니다.' }),
    ).not.toBeNull()
    expect(screen.getByRole('link', { name: '로그인' }).getAttribute('href')).toBe('/login')
    const gain = screen.getByRole('region', { name: '나의 학습에 쌓이는 것' })
    expect(within(gain).getAllByRole('listitem')).toHaveLength(3)
    expect(gain.textContent).not.toMatch(/\d/)
    expect(gain.textContent).not.toMatch(/받음|아직 받지 않음|준비 중/)
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()
  })

  it('첫 단계 공개 전에는 배우기 목록으로 보내고 나의 학습을 부르지 않는다', async () => {
    apiMocks.fetchCurriculum.mockResolvedValue({
      status: 'loaded',
      curriculum: preReleaseCurriculum,
    })
    renderMe()

    expect(await screen.findByText('다른 화면 /learn')).not.toBeNull()
    await waitFor(() => expect(meMocks.fetchLearningMe).not.toHaveBeenCalled())
  })
})

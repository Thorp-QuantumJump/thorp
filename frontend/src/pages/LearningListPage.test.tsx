import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CurriculumResult } from '@/api/learning'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import {
  preReleaseCurriculum,
  releasedCurriculum,
  stageOneReleased,
} from '@/test/learningFixtures'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { learnerOverview } from '@/test/learningMeFixtures'
import type { Curriculum } from '@/types/learning'
import { LearningListPage } from './LearningListPage'

/**
 * 013 T028·T043(1판)과 T141(2판). quickstart 4-1·4-2·6-1·8-4·11-1·11-2·11-6·11-7, US1 시나리오 5, US4 시나리오
 * 1·3.
 */

const apiMocks = vi.hoisted(() => ({
  fetchCurriculum: vi.fn(),
  fetchLearningArticle: vi.fn(),
}))

const meMocks = vi.hoisted(() => ({ fetchLearningMe: vi.fn() }))

vi.mock('@/api/learning', () => apiMocks)
vi.mock('@/api/learningMe', () => meMocks)
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

const failed: CurriculumResult = {
  status: 'failed',
  kind: 'api',
  code: 'INTERNAL_ERROR',
  message: '글 목록을 만들지 못했습니다.',
}

const signedInAuth: AuthContextValue = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: authenticatedUser,
}

const progressWords = /학습 완료|수강|진도|완료율|숙련도|설문/
/** 잠금·예고 어휘(FR-031 표시 규칙, quickstart 11-7). '곧바로' 같은 일반 낱말과 섞이지 않게 '곧 '만 본다. */
const lockWords = /잠김|해금|열립니다|공개 예정|Coming soon|NEW|곧 /

function loaded(curriculum: Curriculum) {
  apiMocks.fetchCurriculum.mockResolvedValue({ status: 'loaded', curriculum } satisfies CurriculumResult)
}

function Probe() {
  const location = useLocation()
  return (
    <p>
      다른 화면 {location.pathname}
    </p>
  )
}

/**
 * 2판 배우기 화면은 `useAuth`를 부르므로 실제 앱처럼 인증 제공자 안에서 그린다. 기본은 비로그인이다(013 T104).
 */
function renderList(entry = '/learn', auth: AuthContextValue = anonymousAuthContext) {
  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/learn" element={<LearningListPage />} />
          <Route path="*" element={<Probe />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

// 로그인 사용자의 '나의 학습'(B13). 기본은 받은 모습이고, 실패·지연은 그 검사에서 바꾼다.
beforeEach(() => {
  meMocks.fetchLearningMe.mockReset()
  meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: learnerOverview })
})

describe('LearningListPage — 첫 단계 공개 전', () => {
  beforeEach(() => {
    apiMocks.fetchCurriculum.mockReset()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('서버가 준 순서 그대로 제목·질문과 글 연결을 보인다', async () => {
    loaded(preReleaseCurriculum)

    renderList()

    const list = await screen.findByRole('list')
    const links = within(list).getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/learn/harbor-notes',
      '/learn/tide-reading',
    ])
    expect(within(links[0]).getByText('항구 기록')).not.toBeNull()
    expect(within(links[1]).getByText('물이 빠지는 속도는 왜 들어오는 속도와 다른가?')).not.toBeNull()
    expect(document.body.textContent).not.toMatch(progressWords)
  })

  it('9월 판 목록과 같고 단계·이전 판·준비 중·보기 전환·공개 안내·나의 학습이 없다(FR-031)', async () => {
    loaded(preReleaseCurriculum)

    renderList()

    await screen.findByRole('list')
    expect(screen.queryByRole('group', { name: '보기' })).toBeNull()
    expect(screen.queryByText(/단계/)).toBeNull()
    expect(screen.queryByText('이전 판')).toBeNull()
    expect(screen.queryByText('준비 중')).toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
    expect(screen.queryByText('변경 기록')).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('목록을 불러오는 동안에도 상태를 알리고, 진단 진입은 화면 안에 따로 두지 않는다', async () => {
    apiMocks.fetchCurriculum.mockReturnValue(new Promise(() => {}))

    renderList()

    expect(await screen.findByRole('status')).not.toBeNull()
    expect(screen.queryByRole('link', { name: /바로 전략 진단하기/ })).toBeNull()
  })

  it('게시된 글이 없으면 경고가 아닌 한 문장을 보인다', async () => {
    loaded({ ...preReleaseCurriculum, preReleaseArticles: [] })

    renderList()

    expect(await screen.findByText('아직 게시된 글이 없습니다.')).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('불러오지 못하면 빈 목록과 다르게 경고와 다시 시도를 보인다', async () => {
    apiMocks.fetchCurriculum.mockResolvedValue(failed)

    renderList()

    expect((await screen.findByRole('alert')).textContent).toBe('글 목록을 만들지 못했습니다.')
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toBeNull()
    expect(screen.queryByText('아직 게시된 글이 없습니다.')).toBeNull()
    expect(screen.queryByRole('link', { name: /바로 전략 진단하기/ })).toBeNull()
  })

  it('다시 시도는 누를 때만 한 번 더 부른다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchCurriculum.mockResolvedValueOnce(failed)
    apiMocks.fetchCurriculum.mockResolvedValueOnce({
      status: 'loaded',
      curriculum: { ...preReleaseCurriculum, preReleaseArticles: [] },
    })

    renderList()

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(apiMocks.fetchCurriculum).toHaveBeenCalledTimes(1)

    await user.click(retry)

    expect(await screen.findByText('아직 게시된 글이 없습니다.')).not.toBeNull()
    expect(apiMocks.fetchCurriculum).toHaveBeenCalledTimes(2)
  })

  it('연결 실패처럼 서버 문장이 없는 실패는 고정 문장을 쓴다', async () => {
    apiMocks.fetchCurriculum.mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '서버에 연결할 수 없습니다.',
    } satisfies CurriculumResult)

    renderList()

    expect((await screen.findByRole('alert')).textContent).toBe('배우기 글을 불러오지 못했습니다.')
  })

  it('첫 단계 공개 전에는 목록을 열고 닫는 동안 브라우저 저장소에 아무것도 쓰지 않는다(quickstart 4-1)', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    loaded(preReleaseCurriculum)

    const { unmount } = renderList()
    await screen.findByRole('list')
    unmount()

    expect(setItem).not.toHaveBeenCalled()
  })
})

describe('LearningListPage — 첫 단계 공개 뒤', () => {
  beforeEach(() => {
    apiMocks.fetchCurriculum.mockReset()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('기본은 커리큘럼 보기이고 공개된 단계의 글을 읽는 순서대로 잇는다', async () => {
    loaded(releasedCurriculum)

    renderList()

    const toggle = await screen.findByRole('group', { name: '보기' })
    expect(within(toggle).getByRole('button', { name: '커리큘럼' }).getAttribute('aria-pressed')).toBe('true')
    expect(within(toggle).getByRole('button', { name: '목록' }).getAttribute('aria-pressed')).toBe('false')
    expect(screen.getByText('읽는 순서를 제안합니다. 공개된 글은 어느 것이든 바로 열 수 있습니다.')).not.toBeNull()

    const stageOne = screen.getByRole('list', { name: '1단계 글' })
    const stageTwo = screen.getByRole('list', { name: '2단계 글' })
    expect(within(stageOne).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/learn/harbor-notes',
      '/learn/dock-fees',
    ])
    expect(within(stageTwo).getAllByRole('link')[0].textContent).toContain('2-1')
    expect(within(stageTwo).getAllByRole('link')[0].textContent).toContain('약 6분')
    // 용어가 없는 글에는 용어 수가 없다(FR-023).
    expect(within(stageOne).getAllByRole('link')[0].textContent).toContain('용어 2개')
    expect(within(stageOne).getAllByRole('link')[1].textContent).not.toContain('용어')
  })

  it('공개 경계 한 줄 뒤 준비 중 단계는 번호·이름·수준·설명과 사유만 있고 글 제목·편수·날짜가 없다(SC-017)', async () => {
    loaded(releasedCurriculum)

    renderList()

    expect(await screen.findByText('지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.')).not.toBeNull()
    const stageFive = screen.getByRole('region', { name: /암초 점검/ })
    expect(stageFive.textContent).toContain('5단계 · 초급')
    expect(stageFive.textContent).toContain('준비 중')
    expect(stageFive.textContent).toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    expect(stageFive.textContent).not.toMatch(/\d+편|\d{4}-\d{2}-\d{2}|약 \d+분/)
    expect(within(stageFive).queryAllByRole('link')).toHaveLength(0)
  })

  it('준비 중 단계에 남은 기존 글은 번호 없는 이전 판 쪽지로 열 수 있다', async () => {
    loaded(releasedCurriculum)

    renderList()

    const stageThree = await screen.findByRole('region', { name: /물때표 읽기/ })
    expect(within(stageThree).getByRole('heading', { level: 4 }).textContent).toBe(
      '다시 쓰기 전의 글 2편 · 나의 학습에 기록하지 않습니다',
    )
    const note = within(stageThree).getByRole('link', { name: /닻줄 길이/ })
    expect(note.getAttribute('href')).toBe('/learn/anchor-lines')
    expect(note.textContent).toContain('이전 판')
    expect(note.textContent).not.toMatch(/\d+-\d+/)
  })

  it('비로그인에게는 기록 자리에 견본과 로그인 연결을 두고 글 상태 글자가 없다(quickstart 11-6)', async () => {
    loaded(releasedCurriculum)

    renderList()

    const sample = await screen.findByRole('region', { name: '로그인하면 학습 기록이 남습니다.' })
    expect(within(sample).getByRole('link', { name: '로그인' }).getAttribute('href')).toBe('/login')
    expect(document.body.textContent).not.toMatch(/완료|읽는 중|안 읽음/)
    // 글 노드와 공개된 단계의 표식은 상태 모양이 아니라 중립 모양이다. 점선은 준비 중 단계의 표식에만 있다.
    for (const name of ['1단계 글', '2단계 글']) {
      const nodes = screen.getByRole('list', { name }).querySelectorAll('[data-stamp]')
      expect(nodes.length).toBeGreaterThan(0)
      for (const node of nodes) expect(node.getAttribute('data-stamp')).toBe('neutral')
    }
    const openMarker = screen.getByRole('region', { name: /항구 기초/ }).querySelector('[data-stamp]')
    expect(openMarker?.getAttribute('data-stamp')).toBe('neutral')
  })

  it('로그인 사용자에게는 견본 대신 나의 학습 요약 패널을 둔다', async () => {
    loaded(releasedCurriculum)

    renderList('/learn', signedInAuth)

    expect(await screen.findByRole('region', { name: '나의 학습' })).not.toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
  })

  it('공개 안내는 7일 안의 줄을 한 줄로 합치고 비로그인에게는 기록 문장을 붙이지 않는다', async () => {
    loaded(releasedCurriculum)

    renderList()

    const notice = (await screen.findByText(/2단계 '너울과 위험'을 공개했습니다/)).closest('p')!
    expect(notice.textContent).toContain(
      "1단계에 새 글 1편을 더했습니다. 2단계 '너울과 위험'을 공개했습니다. 새 글 3편과 다시 쓴 글 1편입니다.",
    )
    expect(notice.textContent).not.toContain('지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.')
    expect(within(notice).getByRole('link', { name: '2단계 보기' }).getAttribute('href')).toBe('/learn#stage-2')
    // 페이지를 열 때 이미 있는 내용이라 상태 알림으로 두지 않는다(FR-031).
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('로그인 사용자에게는 1단계가 아닌 단계 공개가 있을 때만 기록 문장을 덧붙인다', async () => {
    loaded(releasedCurriculum)
    const { unmount } = renderList('/learn', signedInAuth)
    const notice = (await screen.findByText(/공개했습니다/)).closest('p')!
    expect(notice.textContent).toContain('지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.')
    unmount()

    loaded({ ...releasedCurriculum, notices: [stageOneReleased] })
    renderList('/learn', signedInAuth)
    const firstOnly = (await screen.findByText(/공개했습니다/)).closest('p')!
    expect(firstOnly.textContent).not.toContain('지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.')
  })

  it('[닫기]는 그 탭에서만 기억한다. 다시 열어도 안 보이고 새 탭에서는 보이며 서버를 부르지 않는다', async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    const { unmount } = renderList()
    await user.click(await screen.findByRole('button', { name: '공개 안내 닫기' }))
    expect(screen.queryByText(/공개했습니다/)).toBeNull()
    expect(apiMocks.fetchCurriculum).toHaveBeenCalledTimes(1)
    unmount()

    // 같은 탭에서 다시 연다(새로 고침·같은 탭 안의 이동).
    const { unmount: unmountAgain } = renderList()
    await screen.findByRole('group', { name: '보기' })
    expect(screen.queryByText(/공개했습니다/)).toBeNull()
    unmountAgain()

    // 새 탭에는 탭 저장소가 없다.
    window.sessionStorage.clear()
    renderList()
    expect(await screen.findByText(/공개했습니다/)).not.toBeNull()
  })

  it('[닫기]를 누르면 안내와 함께 사라진 단추 대신 눌린 보기 단추로 초점을 옮긴다(목업의 닫기 동작)', async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    renderList()
    await user.click(await screen.findByRole('button', { name: '공개 안내 닫기' }))

    expect(document.activeElement).toBe(screen.getByRole('button', { name: '커리큘럼' }))
  })

  it('합친 안내 가운데 닫은 줄은 그 탭에서 다시 보이지 않고, 닫지 않은 줄만 안내로 보인다(research R27)', async () => {
    // 2단계 공개 안내를 닫은 뒤 1단계에 글이 더해진 경우다. 닫은 줄은 다시 보이지 않는다(FR-031).
    window.sessionStorage.setItem(
      'thorp.learning.dismissedNotices',
      JSON.stringify({ version: 1, entryKeys: ['stage:swell-and-risk'] }),
    )
    loaded(releasedCurriculum)

    renderList('/learn', signedInAuth)

    const notice = (await screen.findByText(/더했습니다/)).closest('p')!
    expect(notice.textContent).toContain('1단계에 새 글 1편을 더했습니다.')
    expect(notice.textContent).not.toContain('공개했습니다')
    // 남은 줄이 글 추가뿐이라 로그인 사용자에게도 기록 문장을 붙이지 않는다.
    expect(notice.textContent).not.toContain('지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.')
    expect(within(notice).getByRole('link', { name: '1단계 보기' })).not.toBeNull()
  })

  it('Tab만으로 로그인·단계 보기·변경 기록·[닫기]·보기 전환에 닿는다(FR-019, quickstart 13)', async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    renderList()
    await screen.findByRole('group', { name: '보기' })
    const targets = [
      screen.getByRole('link', { name: '로그인' }),
      screen.getByRole('link', { name: '2단계 보기' }),
      screen.getByRole('link', { name: '변경 기록' }),
      screen.getByRole('button', { name: '공개 안내 닫기' }),
      screen.getByRole('button', { name: '커리큘럼' }),
      screen.getByRole('button', { name: '목록' }),
    ]
    const reached = new Set<Element>()
    for (let i = 0; i < 12 && reached.size < targets.length; i += 1) {
      await user.tab()
      if (document.activeElement && targets.includes(document.activeElement as HTMLElement)) {
        reached.add(document.activeElement)
      }
    }
    expect(reached.size).toBe(targets.length)
  })

  it('목록 보기를 고르면 그 탭 동안 기억하고, 새 탭은 커리큘럼 보기로 시작한다(quickstart 11-1)', async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    const { unmount } = renderList()
    await user.click(await screen.findByRole('button', { name: '목록' }))
    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('rowheader').map((cell) => cell.textContent)).toHaveLength(6)
    unmount()

    const { unmount: unmountAgain } = renderList()
    expect(await screen.findByRole('table')).not.toBeNull()
    expect(screen.getByRole('button', { name: '목록' }).getAttribute('aria-pressed')).toBe('true')
    unmountAgain()

    window.sessionStorage.clear()
    renderList()
    expect(
      (await screen.findByRole('button', { name: '커리큘럼' })).getAttribute('aria-pressed'),
    ).toBe('true')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('학습 기록을 쓰지 않고 화면 상태 키 둘만 쓴다. 로그인 연결을 누른 경우에만 014 키가 더해진다', async () => {
    const user = userEvent.setup()
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    loaded(releasedCurriculum)

    renderList()
    await user.click(await screen.findByRole('button', { name: '목록' }))
    await user.click(screen.getByRole('button', { name: '공개 안내 닫기' }))
    expect(new Set(setItem.mock.calls.map(([key]) => key))).toEqual(
      new Set(['thorp.learning.view', 'thorp.learning.dismissedNotices']),
    )
    // 두 키는 탭 단위다. 감시는 Storage.prototype에 걸었으므로 누가 불렀는지(this)로 저장소를 가른다.
    for (const context of setItem.mock.contexts) expect(context).toBe(window.sessionStorage)
    expect(window.localStorage.getItem('thorp.learning.view')).toBeNull()

    await user.click(screen.getByRole('link', { name: '로그인' }))
    expect(await screen.findByText(/다른 화면 \/login/)).not.toBeNull()
    expect(new Set(setItem.mock.calls.map(([key]) => key))).toEqual(
      new Set(['thorp.learning.view', 'thorp.learning.dismissedNotices', 'thorp.auth.returnTarget']),
    )
  })

  it('주소의 단계 자리(#stage-n)로 열면 그 단계 제목에 초점을 둔다(T203)', async () => {
    loaded(releasedCurriculum)

    renderList('/learn#stage-3')

    const heading = await screen.findByRole('heading', { level: 3, name: /물때표 읽기/ })
    // 단계 자리로 옮기는 일은 그린 뒤의 효과에서 한다. 부하가 큰 실행에서도 그 효과가 끝난 뒤를 본다.
    await waitFor(() => expect(document.activeElement).toBe(heading))
  })

  it('로그인 상태를 확인하는 동안에는 단계 자리로 옮기지 않고, 확인이 끝난 뒤 한 번만 옮긴다', async () => {
    loaded(releasedCurriculum)
    const checking: AuthContextValue = { ...anonymousAuthContext, status: 'checking', user: null }

    const view = renderList('/learn#stage-3', checking)
    const heading = await screen.findByRole('heading', { level: 3, name: /물때표 읽기/ })
    // 확인이 끝나 비로그인 견본이 위에 들어오면 먼저 옮긴 자리가 밀리므로 기다린다.
    expect(document.activeElement).not.toBe(heading)

    view.rerender(
      <AuthContext.Provider value={anonymousAuthContext}>
        <MemoryRouter initialEntries={['/learn#stage-3']}>
          <Routes>
            <Route path="/learn" element={<LearningListPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 3, name: /물때표 읽기/ }))

    // 탭이 다시 보여 014가 다시 확인해도 같은 이동에서 또 옮기지 않는다.
    screen.getByRole('button', { name: '목록' }).focus()
    view.rerender(
      <AuthContext.Provider value={checking}>
        <MemoryRouter initialEntries={['/learn#stage-3']}>
          <Routes>
            <Route path="/learn" element={<LearningListPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    view.rerender(
      <AuthContext.Provider value={anonymousAuthContext}>
        <MemoryRouter initialEntries={['/learn#stage-3']}>
          <Routes>
            <Route path="/learn" element={<LearningListPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '목록' }))
  })

  it('움직임 줄이기 설정이 아니면 옮겨 온 공개 단계의 띠를 잠시 단계 색으로 짚는다', async () => {
    const user = userEvent.setup()
    // jsdom에는 matchMedia가 없다. 움직임 설정만 답하는 대역을 둔다.
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(prefers-reduced-motion: no-preference)',
      media: query,
    }))
    loaded(releasedCurriculum)

    renderList()
    await user.click(await screen.findByRole('link', { name: '2단계 보기' }))

    const band = screen.getByRole('heading', { level: 3, name: /너울과 위험/ }).closest('[data-stage-band]')!
    expect(band.hasAttribute('data-flash')).toBe(true)
  })

  it('움직임 줄이기 설정이면 띠를 짚지 않는다', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      media: query,
    }))
    loaded(releasedCurriculum)

    renderList()
    await user.click(await screen.findByRole('link', { name: '2단계 보기' }))

    const band = screen.getByRole('heading', { level: 3, name: /너울과 위험/ }).closest('[data-stage-band]')!
    expect(band.hasAttribute('data-flash')).toBe(false)
  })

  it("공개 안내의 '2단계 보기'는 2단계 자리로, '변경 기록'은 펼친 변경 기록으로 초점을 옮긴다", async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    renderList()
    await user.click(await screen.findByRole('link', { name: '2단계 보기' }))
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 3, name: /너울과 위험/ }))

    await user.click(screen.getByRole('link', { name: '변경 기록' }))
    const log = document.querySelector('details')!
    expect(log.open).toBe(true)
    expect(document.activeElement).toBe(log.querySelector('summary'))
  })

  it('변경 기록은 날짜와 공개한 단계·더한 글·편수를 최신 순서로 쌓고 기록 문장을 넣지 않는다', async () => {
    loaded(releasedCurriculum)

    renderList('/learn', signedInAuth)

    const log = (await screen.findByText('변경 기록', { selector: 'summary' })).closest('details')!
    expect(within(log).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      "2026-11-19 · 2단계 공개 · 새 글 3편, 다시 쓴 글 1편('물때 읽기')",
      "2026-11-15 · 1단계에 글 추가 · 새 글 1편('부두 이용료는 어디로 갈까?')",
      "2026-11-05 · 1단계 공개 · 새 글 1편, 다시 쓴 글 1편('항구 기록')",
    ].map((line) => line.replace(' · ', '')))
    expect(log.textContent).not.toContain('그대로입니다')
  })

  it('두 보기 어디에도 잠금·예고 어휘와 진도 문구가 없다(quickstart 11-7)', async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    renderList('/learn', signedInAuth)
    await screen.findByRole('group', { name: '보기' })
    expect(document.body.textContent).not.toMatch(lockWords)
    expect(document.body.textContent).not.toMatch(progressWords)

    await user.click(screen.getByRole('button', { name: '목록' }))
    expect(document.body.textContent).not.toMatch(lockWords)
    expect(document.body.innerHTML).not.toMatch(/aria-label="[^"]*(잠김|해금|열립니다|공개 예정)/)
  })

  it('받은 learningReturn을 글 연결에 그대로 싣는다', async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)

    render(
      <AuthContext.Provider value={anonymousAuthContext}>
        <MemoryRouter
          initialEntries={[{ pathname: '/learn', state: { learningReturn: { path: '/strategy', label: '' } } }]}
        >
          <Routes>
            <Route path="/learn" element={<LearningListPage />} />
            <Route path="/learn/:articleId" element={<StateProbe />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    await user.click(within(await screen.findByRole('list', { name: '1단계 글' })).getAllByRole('link')[0])

    expect((await screen.findByTestId('state')).textContent).toBe('/strategy')
  })
})

function StateProbe() {
  const location = useLocation()
  const state = location.state as { learningReturn?: { path: string } } | null
  return <p data-testid="state">{state?.learningReturn?.path ?? ''}</p>
}

/** 이 파일 안에서만 쓰는 `IntersectionObserver` 대역. 요약 패널의 '이어서 읽기'가 화면에 보이는지를 손으로 알린다. */
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
    const entries = this.targets.map((target) => ({ target, isIntersecting }) as IntersectionObserverEntry)
    if (entries.length > 0) this.callback(entries, this as unknown as IntersectionObserver)
  }
}

/**
 * 013 T192·T196·T206. 로그인 사용자의 요약 패널, 글별 상태, 단계별 완료 수, 떠 있는 '이어서 읽기'(FR-020, design-notes
 * §3.1·§3.2·§4, quickstart 11-3·11-4).
 */
describe('LearningListPage — 나의 학습', () => {
  beforeEach(() => {
    apiMocks.fetchCurriculum.mockReset()
    window.sessionStorage.clear()
    FakeIntersectionObserver.instances = []
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('커리큘럼 보기의 글 노드·카드에 글별 상태와 익힌 용어를, 단계 띠에 도장 줄과 완료 수를 채운다', async () => {
    loaded(releasedCurriculum)
    renderList('/learn', signedInAuth)

    await screen.findByRole('region', { name: '나의 학습' })
    const stageOne = screen.getByRole('list', { name: '1단계 글' })
    const stageTwo = screen.getByRole('list', { name: '2단계 글' })
    expect(within(stageOne).getAllByText('완료')).toHaveLength(2)
    expect(within(stageTwo).getByText('62% 읽는 중')).not.toBeNull()
    expect(within(stageTwo).getByText('안 읽음')).not.toBeNull()
    expect(within(stageOne).getByText('용어 2개 중 2개 익힘')).not.toBeNull()
    const harbor = within(stageOne).getByRole('link', { name: /항구 기록/ })
    expect(harbor.querySelector('[data-stamp]')?.getAttribute('data-stamp')).toBe('done')

    // 단계 띠: 다 읽은 1단계는 '완주 · 2편', 2단계는 '2편 중 0편 읽음'
    expect(screen.getByText('완주 · 2편')).not.toBeNull()
    expect(screen.getByText('2편 중 0편 읽음')).not.toBeNull()
    expect(document.querySelectorAll('[data-stage-well]')).toHaveLength(2)
  })

  it("지금 이어 읽을 글의 카드에 빛과 '이어서 읽기 →'를 두고, 누르면 다른 글처럼 맨 위에서 연다(FR-020)", async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)
    render(
      <AuthContext.Provider value={signedInAuth}>
        <MemoryRouter initialEntries={['/learn']}>
          <Routes>
            <Route path="/learn" element={<LearningListPage />} />
            <Route path="*" element={<ResumeProbe />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )

    await screen.findByRole('region', { name: '나의 학습' })
    const stageTwo = screen.getByRole('list', { name: '2단계 글' })
    const current = within(stageTwo).getByRole('link', { name: /물때 읽기/ })
    expect(current.textContent).toContain('이어서 읽기')
    expect(current.querySelector('[data-part="tip"]')).not.toBeNull()
    // 카드 뒤의 빛은 따로 된 층이라 로그인 상태를 다시 확인하는 동안 숨길 수 있다(research R33).
    expect(current.querySelector('span[aria-hidden="true"][data-learner-mark]:empty')).not.toBeNull()
    expect(within(stageTwo).getByRole('link', { name: /너울 읽기/ }).textContent).not.toContain('이어서 읽기')

    // 목록에서 '읽는 중' 글을 열면 맨 위에서 연다. 기록된 위치로 옮기는 것은 글 맨 위의 한 줄이다(FR-020).
    await user.click(current)
    expect((await screen.findByTestId('resume')).textContent?.trim()).toBe('/learn/tide-reading')
  })

  it("단계 완주 배지를 받은 뒤 그 단계에 글이 더해지면 메달은 그대로이고 진행만 새 글을 포함해 센다(US6 시나리오 8)", async () => {
    meMocks.fetchLearningMe.mockResolvedValue({
      status: 'ok',
      data: {
        ...learnerOverview,
        stages: learnerOverview.stages.map((stage) =>
          stage.number === 1 ? { ...stage, completedCount: 2, publishedCount: 3, completed: false } : stage,
        ),
      },
    })
    loaded(releasedCurriculum)
    renderList('/learn', signedInAuth)

    await screen.findByRole('region', { name: '나의 학습' })
    const well = document.querySelector('[data-stage-well="1"]')!
    expect(well.querySelector('[data-medal="stage-earned"]')).not.toBeNull()
    expect(well.textContent).toContain('3편 중 2편 읽음')
    expect(well.textContent).not.toContain('배지까지')
  })

  it('로그인이 만료된 동안은 기록 자리에 재로그인 안내를 두고 나의 학습을 부르지 않는다(research R33)', async () => {
    loaded(releasedCurriculum)
    renderList('/learn', { ...anonymousAuthContext, status: 'expired', user: null })

    expect(
      await screen.findByText('로그인이 만료되었습니다. 다시 로그인하면 이 화면으로 돌아옵니다.'),
    ).not.toBeNull()
    expect(screen.getByRole('link', { name: '다시 로그인' }).getAttribute('href')).toBe('/login')
    expect(screen.queryByRole('region', { name: '나의 학습' })).toBeNull()
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()
  })

  it("'이어서 읽기 →'는 넓은 화면에서 꼬리표 줄 아래 한 줄을 다 쓰고 오른쪽에 붙는다", () => {
    expect(
      cssMediaDeclarations('components/learning/curriculum/ArticleNode.module.css', '(min-width: 1024px)', '.goHint'),
    ).toMatch(/grid-column:\s*1 \/ -1[\s\S]*justify-self:\s*end/)
  })

  it('목록 보기의 노선은 강제 색 모드에서도 실선과 점선이 남는다', () => {
    const css = 'components/learning/curriculum/ListView.module.css'
    const forced = (selector: string) => cssMediaDeclarations(css, '(forced-colors: active)', selector)
    // 기본 노선은 바탕색으로 칠해 강제 색 모드에서 화면 바탕이 된다. 그 모드에서 시스템 색과 테두리 점선으로 다시 그린다.
    expect(forced(":is(.railTop, .railBottom)[data-kind='read']")).toMatch(/background:\s*Highlight/)
    expect(forced(":is(.railTop, .railBottom):is([data-kind='neutral'], [data-kind='unread'])")).toMatch(
      /background:\s*CanvasText/,
    )
    const preparing = forced(":is(.railTop, .railBottom)[data-kind='preparing']")
    expect(preparing).toMatch(/border-left:\s*3px dashed CanvasText/)
    expect(preparing).not.toMatch(/gradient/)
  })

  it("목록 보기는 상태 열과 'n편 중 m편 읽음'·'완주 · n편', 익힌 용어 'm/n'을 보이고 떠 있는 단추가 없다", async () => {
    const user = userEvent.setup()
    loaded(releasedCurriculum)
    renderList('/learn', signedInAuth)

    await screen.findByRole('region', { name: '나의 학습' })
    await user.click(screen.getByRole('button', { name: '목록' }))

    const table = screen.getByRole('table')
    expect(within(table).getByRole('columnheader', { name: '상태' })).not.toBeNull()
    expect(within(table).getAllByText('완료')).toHaveLength(2)
    expect(within(table).getByText('62% 읽는 중')).not.toBeNull()
    expect(within(table).getByText(/완주 · 2편/)).not.toBeNull()
    expect(within(table).getByText(/2편 중 0편 읽음/)).not.toBeNull()
    expect(within(table).getByText('2개 중 2개 익힘')).not.toBeNull()

    act(() => FakeIntersectionObserver.instances.forEach((observer) => observer.report(false)))
    expect(screen.queryByRole('link', { name: /^이어서 읽기 2-1$/ })).toBeNull()
  })

  it("커리큘럼 보기에서는 패널의 '이어서 읽기'가 화면 밖이면 떠 있는 단추가 나타난다", async () => {
    loaded(releasedCurriculum)
    renderList('/learn', signedInAuth)

    await screen.findByRole('region', { name: '나의 학습' })
    expect(screen.queryByRole('link', { name: '이어서 읽기 2-1' })).toBeNull()

    // 떠 있는 단추가 패널의 연결을 지켜보기 시작한 뒤에 화면 밖으로 나갔다고 알린다.
    await waitFor(() => expect(FakeIntersectionObserver.instances.some((observer) => observer.targets.length > 0)).toBe(true))
    act(() => FakeIntersectionObserver.instances.forEach((observer) => observer.report(false)))
    expect((await screen.findByRole('link', { name: '이어서 읽기 2-1' })).getAttribute('href')).toBe('/learn/tide-reading')
  })

  it("기록을 받지 못하면 패널 자리에 실패 한 줄만 두고 글별 상태·단계 완료 수를 비운다(quickstart 11-4)", async () => {
    const user = userEvent.setup()
    meMocks.fetchLearningMe.mockResolvedValue({ status: 'failed', kind: 'network', code: null, message: '' })
    loaded(releasedCurriculum)
    renderList('/learn', signedInAuth)

    expect(await screen.findByText('학습 기록을 불러오지 못했습니다.')).not.toBeNull()
    expect(screen.getAllByText('학습 기록을 불러오지 못했습니다.')).toHaveLength(1)
    expect(document.body.textContent).not.toMatch(/안 읽음|편 중 \d+편 읽음|완주 ·|레벨 1/)
    for (const node of screen.getByRole('list', { name: '1단계 글' }).querySelectorAll('[data-stamp]')) {
      expect(node.getAttribute('data-stamp')).toBe('neutral')
    }
    act(() => FakeIntersectionObserver.instances.forEach((observer) => observer.report(false)))
    expect(screen.queryByRole('link', { name: /^이어서 읽기/ })).toBeNull()

    meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: learnerOverview })
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('완주 · 2편')).not.toBeNull()
    expect(within(screen.getByRole('list', { name: '2단계 글' })).getByText('62% 읽는 중')).not.toBeNull()
  })

  it('요약 패널이 늦게 들어와도 응답을 기다리지 않고 먼저 옮기고, 응답 뒤 같은 이동에서 한 번만 다시 옮긴다(T206)', async () => {
    const original = Element.prototype.scrollIntoView
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    try {
      let resolveMe: (value: unknown) => void = () => {}
      meMocks.fetchLearningMe.mockReturnValue(new Promise((resolve) => (resolveMe = resolve)))
      loaded(releasedCurriculum)
      const view = renderList('/learn#stage-2', signedInAuth)
      const rerenderWith = (auth: AuthContextValue) =>
        view.rerender(
          <AuthContext.Provider value={auth}>
            <MemoryRouter initialEntries={['/learn#stage-2']}>
              <Routes>
                <Route path="/learn" element={<LearningListPage />} />
                <Route path="*" element={<Probe />} />
              </Routes>
            </MemoryRouter>
          </AuthContext.Provider>,
        )

      // 응답이 오기 전에 이미 단계 자리로 옮겨 제목에 초점이 있다. 응답이 늦어도 단계 자리로 가는 것이 늦어지지 않는다.
      const heading = await screen.findByRole('heading', { level: 3, name: /너울과 위험/ })
      await waitFor(() => expect(document.activeElement).toBe(heading))
      expect(scrolled).toHaveBeenCalledTimes(1)
      heading.blur()

      // 패널이 들어와 먼저 옮긴 자리가 밀렸다. 같은 이동에서 한 번 더 옮겨 2단계 제목에 초점이 다시 간다.
      await act(async () => resolveMe({ status: 'ok', data: learnerOverview }))
      expect(await screen.findByRole('region', { name: '나의 학습' })).not.toBeNull()
      await waitFor(() =>
        expect(document.activeElement).toBe(screen.getByRole('heading', { level: 3, name: /너울과 위험/ })),
      )
      expect(scrolled).toHaveBeenCalledTimes(2)

      // 로그인 상태를 다시 확인했다가 돌아와도 같은 이동에서 더 옮기지 않는다.
      rerenderWith({ ...signedInAuth, status: 'checking', user: null })
      rerenderWith(signedInAuth)
      await act(async () => {})
      expect(scrolled).toHaveBeenCalledTimes(2)
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('로그인 상태를 다시 확인하는 동안 글별 상태·단계 완료 수를 접지 않고 자리를 둔 채 숨긴다(T192, research R33)', async () => {
    loaded(releasedCurriculum)
    const view = renderList('/learn', signedInAuth)
    const rerenderWith = (auth: AuthContextValue) =>
      view.rerender(
        <AuthContext.Provider value={auth}>
          <MemoryRouter initialEntries={['/learn']}>
            <Routes>
              <Route path="/learn" element={<LearningListPage />} />
              <Route path="*" element={<Probe />} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>,
      )

    await screen.findByRole('region', { name: '나의 학습' })
    const marks = document.querySelectorAll('[data-learner-mark]').length
    expect(marks).toBeGreaterThan(0)
    expect(document.querySelector('[class*="learnerConcealed"]')).toBeNull()

    rerenderWith({ ...signedInAuth, status: 'checking', user: null })
    // 표시를 내리지 않고(같은 수가 그대로 있다) 감싼 자리가 숨기는 규칙을 받는다. 글자도 자리에 남는다.
    expect(document.querySelectorAll('[data-learner-mark]')).toHaveLength(marks)
    expect(document.querySelectorAll('[class*="learnerConcealed"] [data-learner-mark]').length).toBeGreaterThan(0)
    expect(screen.getByText('완주 · 2편')).not.toBeNull()
    expect(cssDeclarations('pages/LearningListPage.module.css', '.learnerConcealed [data-learner-mark]')).toMatch(
      /visibility:\s*hidden/,
    )
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)

    rerenderWith(signedInAuth)
    expect(document.querySelector('[class*="learnerConcealed"]')).toBeNull()
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)
  })

  it('비로그인에게는 나의 학습을 부르지 않는다', async () => {
    loaded(releasedCurriculum)
    renderList()

    await screen.findByRole('group', { name: '보기' })
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()
  })

  it('첫 단계 공개 전에는 로그인해도 나의 학습을 부르지 않는다', async () => {
    loaded(preReleaseCurriculum)
    renderList('/learn', signedInAuth)

    await screen.findByRole('list')
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: '나의 학습' })).toBeNull()
  })
})

function ResumeProbe() {
  const location = useLocation()
  const state = location.state as { learningResume?: boolean } | null
  return <p data-testid="resume">{`${location.pathname} ${state?.learningResume ? 'resume' : ''}`}</p>
}

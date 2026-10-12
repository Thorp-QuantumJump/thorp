import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LearningArticleResult } from '@/api/learning'
import { apiClient } from '@/api/client'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import { cssDeclarations } from '@/test/cssRule'
import {
  currentArticle,
  harborArticle,
  previousTideArticle,
  tideArticle,
} from '@/test/learningFixtures'
import {
  answerResult,
  completedRecord,
  completionResult,
  openQuiz,
  readingRecord,
  retryResult,
  unreadRecord,
  learnerOverview,
} from '@/test/learningMeFixtures'
import { LearningArticlePage } from './LearningArticlePage'

/**
 * 013 T029·T031·T043(1판)과 T134·T143(2판). US1 시나리오 4, quickstart 3-8 화면 쪽·4-1·4-4·9-1~9-6,
 * FR-019.
 *
 * <p>2판 글 화면은 `useAuth`를 부르므로 `AuthContext.Provider`로 감싸 그린다. 기본은 비로그인이다(T104).
 * 나의 학습 통로는 대역으로 바꾼다. 기본 글 기록은 연 적이 없는 글이다(T190).
 */

/** '나의 학습' 전체를 받지 못한 결과. 완료 카드는 그림과 다음 목표 없이 B12의 글자만 보인다. */
const overviewFailure = { status: 'failed', kind: 'network', code: null, message: '연결하지 못했습니다.' } as const

const apiMocks = vi.hoisted(() => ({
  fetchLearningArticle: vi.fn(),
}))
const glossaryMocks = vi.hoisted(() => ({ fetchGlossaryEntry: vi.fn() }))
const meMocks = vi.hoisted(() => ({
  fetchLearnerArticle: vi.fn(),
  fetchLearningMe: vi.fn(),
  completeArticle: vi.fn(),
  answerQuestion: vi.fn(),
  retryAnswer: vi.fn(),
  recordPosition: vi.fn(),
  sendPositionOnLeave: vi.fn(),
  prepareLeaveRecording: vi.fn(),
}))

vi.mock('@/api/learning', () => apiMocks)
vi.mock('@/api/learningGlossary', () => glossaryMocks)
vi.mock('@/api/learningMe', () => meMocks)

// 글 화면을 내릴 때의 위치 기록은 한 박자 미뤄 나간다(StrictMode 대응). 다음 테스트로 새지 않게 여기서 비운다.
afterEach(async () => {
  cleanup()
  await new Promise((resolve) => setTimeout(resolve, 0))
})
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

beforeAll(() => {
  // 글 안 도움말(Popover)의 위치 계산이 크기 관찰을 쓴다. jsdom에는 없다.
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  }
})

let probedState: unknown = undefined

function Probe({ name }: { name: string }) {
  const location = useLocation()
  probedState = location.state
  return (
    <p>
      {name} {location.pathname}
      {location.search}
      {location.hash}
    </p>
  )
}

function articleTree(
  entry: string | { pathname: string; state: unknown },
  auth: AuthContextValue,
) {
  return (
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/learn/:articleId" element={<LearningArticlePage />} />
          <Route path="/learn" element={<Probe name="주제 목록 화면" />} />
          <Route path="/login" element={<Probe name="로그인 화면" />} />
          <Route path="/diagnosis/sectors" element={<Probe name="섹터 선택 화면" />} />
          <Route path="*" element={<Probe name="돌아온 화면" />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

function renderArticle(
  entry: string | { pathname: string; state: unknown },
  auth: AuthContextValue = anonymousAuthContext,
) {
  const result = render(articleTree(entry, auth))
  /** 같은 화면을 둔 채 로그인 상태만 바꿔 다시 그린다. 014의 재확인을 흉내 낸다. */
  const setAuth = (next: AuthContextValue) => result.rerender(articleTree(entry, next))
  return { ...result, setAuth }
}

const checkingAuth: AuthContextValue = { ...anonymousAuthContext, status: 'checking', user: null }
const signedInAuth: AuthContextValue = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: authenticatedUser,
}

function loaded(article: Extract<LearningArticleResult, { status: 'loaded' }>['article']) {
  apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'loaded', article })
}

/** 비로그인 화면에 보이면 안 되는 학습 기록 문구(FR-020). '로그인하면 학습 기록이 남습니다'는 안내라 뺀다. */
const recordWords = /학습 완료|수강|진도|완료율|읽는 중|경험치|EXP|배지|레벨|완주/

describe('LearningArticlePage', () => {
  beforeEach(() => {
    apiMocks.fetchLearningArticle.mockReset()
    glossaryMocks.fetchGlossaryEntry.mockReset()
    meMocks.fetchLearnerArticle.mockReset()
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: unreadRecord })
    // 완료 카드의 다음 목표(T204)는 따로 확인한다. 여기서는 받지 못한 것으로 두어 B12의 글자만 본다.
    meMocks.fetchLearningMe.mockReset()
    meMocks.fetchLearningMe.mockResolvedValue(overviewFailure)
    probedState = undefined
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('경로의 articleId로 한 번 부르고 제목·질문 하나·본문을 보인다', async () => {
    loaded(tideArticle)

    renderArticle('/learn/tide-reading')

    expect(await screen.findByRole('heading', { level: 1, name: '물때 읽기' })).not.toBeNull()
    expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchLearningArticle.mock.calls[0][0]).toBe('tide-reading')
    expect(screen.getAllByText('물이 빠지는 속도는 왜 들어오는 속도와 다른가?')).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 2, name: '물이 가장 낮았던 때' })).not.toBeNull()
  })

  it('예시 비중을 % 표기로 보이고 투자 권고가 아니라는 문장과 진입 버튼을 둔다', async () => {
    loaded(tideArticle)

    renderArticle('/learn/tide-reading')

    const example = await screen.findByRole('region', { name: '예시' })
    expect(within(example).getByText('알파 부두')).not.toBeNull()
    expect(within(example).getByText('62.5%')).not.toBeNull()
    expect(within(example).getByText('37.5%')).not.toBeNull()
    expect(within(example).getByText('설명을 위한 예시이며 투자 권고가 아닙니다.')).not.toBeNull()
    expect(within(example).getByRole('button', { name: '이 예시로 전략 진단하기' })).not.toBeNull()
  })

  it('대응표에 없는 섹터는 이름 대신 코드로 보이고 진입 버튼 대신 안내를 둔다', async () => {
    loaded(harborArticle)

    renderArticle('/learn/harbor-notes')

    const example = await screen.findByRole('region', { name: '예시' })
    expect(within(example).getByText('GAMMA_DOCK')).not.toBeNull()
    expect(within(example).queryByRole('button')).toBeNull()
    expect(within(example).getByRole('link', { name: '빈 섹터 선택 화면으로 가기' })).not.toBeNull()
  })

  it('9월 판 글의 출처는 url이 있으면 링크로, 없으면 텍스트로 보인다', async () => {
    loaded(tideArticle)

    renderArticle('/learn/tide-reading')

    const sources = await screen.findByRole('region', { name: '출처' })
    expect(within(sources).getByRole('link', { name: '합성 출처 하나' }).getAttribute('href')).toBe(
      'https://example.com/tide',
    )
    expect(within(sources).queryByRole('link', { name: '주소 없는 합성 출처' })).toBeNull()
    expect(within(sources).getByText('주소 없는 합성 출처')).not.toBeNull()
  })

  it('볼 수 없는 글은 경고 없이 한 문장과 목록 연결을 보인다', async () => {
    apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'unavailable' })

    renderArticle('/learn/draft-only')

    expect(await screen.findByText('지금은 볼 수 없는 글입니다.')).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByRole('link', { name: '목록으로 돌아가기' }).getAttribute('href')).toBe('/learn')
  })

  it('불러오지 못하면 경고와 다시 시도를 보이고, 다시 시도는 Tab으로 닿고 누를 때만 부른다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchLearningArticle.mockResolvedValueOnce({
      status: 'failed',
      kind: 'api',
      code: 'INTERNAL_ERROR',
      message: '글을 만들지 못했습니다.',
    } satisfies LearningArticleResult)
    apiMocks.fetchLearningArticle.mockResolvedValueOnce({ status: 'loaded', article: tideArticle })

    renderArticle('/learn/tide-reading')

    expect((await screen.findByRole('alert')).textContent).toBe('글을 만들지 못했습니다.')
    const retry = screen.getByRole('button', { name: '다시 시도' })
    expect(retry.tagName).toBe('BUTTON')
    expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(1)
    // 진단으로 가는 길은 상단 메뉴가 맡는다. 화면 안에 따로 진입 링크를 두지 않는다(US1 시나리오 7).
    expect(screen.queryByRole('link', { name: /바로 전략 진단하기/ })).toBeNull()

    await user.tab()
    expect(document.activeElement).toBe(retry)
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { level: 1, name: '물때 읽기' })).not.toBeNull()
    expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(2)
  })

  it('목록 연결은 받은 learningReturn을 그대로 넘긴다', async () => {
    const user = userEvent.setup()
    const received = { learningReturn: { path: '/strategy', label: '전략 선택' } }
    loaded(tideArticle)

    renderArticle({ pathname: '/learn/tide-reading', state: received })

    await user.click(await screen.findByRole('link', { name: '목록으로 돌아가기' }))

    expect(await screen.findByText(/주제 목록 화면/)).not.toBeNull()
    expect(probedState).toEqual(received)
  })

  it('진단 화면에서 온 글에만 하던 진단으로 돌아가는 연결을 보인다', async () => {
    loaded(tideArticle)

    renderArticle({
      pathname: '/learn/tide-reading',
      state: { learningReturn: { path: '/strategy', label: '전략 선택' } },
    })

    const back = await screen.findByRole('link', { name: /하던 진단으로 돌아가기/ })
    expect(back.getAttribute('href')).toBe('/strategy')
    // 글 화면의 행동 버튼은 예시 진입 하나다(원칙 II).
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      '이 예시로 전략 진단하기',
    ])
  })

  it.each([
    ['/param-search?bundle=x', '자동 탐색으로 돌아가기'],
    ['/market-flow', '시장 흐름으로 돌아가기'],
    ['/regime-search', '비슷했던 때 보기로 돌아가기'],
    ['/regime-link/start', '경제환경이 비슷했던 과거로 돌아가기'],
  ])('%s에서 온 글은 표의 문구로 쿼리까지 그대로 돌아간다', async (path, label) => {
    const user = userEvent.setup()
    loaded(tideArticle)

    renderArticle({ pathname: '/learn/tide-reading', state: { learningReturn: { path } } })

    await user.click(await screen.findByRole('link', { name: new RegExp(label) }))

    expect(await screen.findByText(`돌아온 화면 ${path}`)).not.toBeNull()
  })

  it.each([
    ['배우기 메뉴로 직접', undefined],
    ['외부 주소', { learningReturn: { path: 'https://evil.example/', label: '밖' } }],
    ['로그인 화면', { learningReturn: { path: '/login', label: '로그인' } }],
  ])('%s 들어온 글에는 돌아가기 연결이 없다', async (_, state) => {
    loaded(tideArticle)

    renderArticle(state ? { pathname: '/learn/tide-reading', state } : '/learn/tide-reading')

    await screen.findByRole('heading', { level: 1, name: '물때 읽기' })
    // 화살표로 시작하는 보조 연결이 돌아가기다. '목록으로 돌아가기'는 늘 있는 다른 연결이다.
    const returnLinks = screen
      .getAllByRole('link')
      .filter((link) => link.textContent?.startsWith('←'))
    expect(returnLinks).toHaveLength(0)
  })

  it('첫 단계 공개 전 글은 1판 모습 그대로다', async () => {
    loaded(tideArticle)

    renderArticle('/learn/tide-reading')

    await screen.findByRole('heading', { level: 1, name: '물때 읽기' })
    expect(screen.getByText('LEARN')).not.toBeNull()
    expect(screen.queryByText('이전 판')).toBeNull()
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
    expect(document.body.textContent).not.toMatch(recordWords)
  })

  it('이전 판 글은 머리에 이전 판, 번호 없음, 글 끝에 기록하지 않는다는 한 줄과 목록 연결이다', async () => {
    const user = userEvent.setup()
    const received = { learningReturn: { path: '/strategy', label: '전략 선택' } }
    loaded(previousTideArticle)

    renderArticle({ pathname: '/learn/tide-reading', state: received })

    const title = await screen.findByRole('heading', { level: 1, name: '물때 읽기' })
    const header = title.closest('header')!
    expect(within(header).getByText('이전 판')).not.toBeNull()
    expect(within(header).queryByText(/^\d+-\d+$/)).toBeNull()
    expect(within(header).getByRole('link', { name: '3단계 · 물때표 읽기' })).not.toBeNull()
    // 글 끝에는 한 줄과 목록 연결만 둔다. 9월 판의 글 끝 출처 목록과 목록 돌아가기 연결은 없다(FR-007, FR-031).
    expect(screen.queryByRole('region', { name: '출처' })).toBeNull()
    expect(screen.queryByRole('link', { name: '목록으로 돌아가기' })).toBeNull()
    // 예시는 9월 판 본문의 일부라 그대로 둔다.
    expect(screen.getByRole('button', { name: '이 예시로 전략 진단하기' })).not.toBeNull()
    const notice = screen.getByRole('region', { name: '이전 판 안내' })
    expect(notice.textContent).toContain(
      '이 글은 새 기준으로 다시 쓰기 전의 판입니다. 그래서 읽은 기록과 확인 문제를 남기지 않습니다.',
    )
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()

    await user.click(within(notice).getByRole('link', { name: '배우기 목록으로' }))
    expect(probedState).toEqual(received)
  })

  it('새 기준 글은 경로·번호·예상 읽기 시간 머리와 새 요소를 보이고 글 끝 출처 목록이 없다', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading')

    const title = await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    const header = title.closest('header')!
    const crumb = within(header).getByRole('navigation', { name: '경로' })
    expect(within(crumb).getByRole('link', { name: '배우기' }).getAttribute('href')).toBe('/learn')
    // 단계 연결은 배우기 목록의 그 단계 자리로 간다(T203).
    expect(
      within(crumb).getByRole('link', { name: '2단계 · 너울과 위험' }).getAttribute('href'),
    ).toBe('/learn#stage-2')
    expect(within(header).getByText('2-3')).not.toBeNull()
    expect(within(header).getByText('약 4분')).not.toBeNull()
    expect(screen.getByText('본문 끝')).not.toBeNull()
    expect(screen.getByRole('region', { name: '핵심 요약' })).not.toBeNull()
    expect(screen.getByRole('complementary', { name: '제도 안내' })).not.toBeNull()
    expect(screen.queryByRole('region', { name: '출처' })).toBeNull()
    expect(screen.queryByText('LEARN')).toBeNull()
  })

  it('비로그인 새 기준 글 끝은 확인 문제·다음 글·견본 순서이고 학습 기록 문구가 없다', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading')

    const quiz = await screen.findByRole('region', { name: '확인 문제' })
    const next = screen.getByRole('link', { name: /다음 글/ })
    const sample = screen.getByRole('region', { name: '로그인하면 학습 기록이 남습니다.' })
    expect(quiz.compareDocumentPosition(next) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(next.compareDocumentPosition(sample) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(next.textContent).toContain('2-4 물때 읽기')
    expect(document.body.textContent).not.toMatch(recordWords)
  })

  it('비로그인 글 끝의 견본은 한 줄·로그인 연결과 숫자·상태 모양 없는 그림뿐이다(T202)', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading')

    const sample = await screen.findByRole('region', { name: '로그인하면 학습 기록이 남습니다.' })
    // 보조 기술이 읽는 것은 한 줄과 로그인 연결뿐이다. 그림은 aria-hidden이다(design-notes §3.5).
    expect(sample.textContent).toBe('로그인하면 학습 기록이 남습니다.로그인')
    expect(within(sample).getByRole('link', { name: '로그인' }).getAttribute('href')).toBe('/login')
    const art = sample.querySelector('svg')!
    expect(art.closest('[aria-hidden="true"]')).not.toBeNull()
    // 숫자·글자, 상태를 뜻하는 점선과 글 도장 모양이 없다.
    expect(art.querySelector('text')).toBeNull()
    expect(art.querySelector('[stroke-dasharray]')).toBeNull()
    expect(sample.querySelector('[data-stamp]')).toBeNull()
  })

  it('다음 글 연결은 받은 learningReturn을 그대로 넘긴다', async () => {
    const user = userEvent.setup()
    const received = { learningReturn: { path: '/market-flow', label: '' } }
    loaded(currentArticle)

    renderArticle({ pathname: '/learn/swell-reading', state: received })
    loaded(tideArticle)
    await user.click(await screen.findByRole('link', { name: /다음 글/ }))

    await screen.findByRole('heading', { level: 1, name: '물때 읽기' })
    expect(screen.getByRole('link', { name: /시장 흐름으로 돌아가기/ })).not.toBeNull()
  })

  it('다음 글이 없으면 배우기 목록으로와 다음 단계는 준비 중입니다를 보인다', async () => {
    loaded({ ...currentArticle, next: null, hasUnreleasedStages: true })

    const { unmount } = renderArticle('/learn/swell-reading')

    expect(await screen.findByRole('link', { name: '배우기 목록으로' })).not.toBeNull()
    expect(screen.getByText('다음 단계는 준비 중입니다.')).not.toBeNull()
    unmount()

    loaded({ ...currentArticle, next: null, hasUnreleasedStages: false })
    renderArticle('/learn/swell-reading')

    expect(await screen.findByRole('link', { name: '배우기 목록으로' })).not.toBeNull()
    expect(screen.queryByText('다음 단계는 준비 중입니다.')).toBeNull()
  })

  it('글 끝의 로그인은 단추가 아닌 글자 연결이라 예시 진입 버튼과 겨루지 않는다(constitution II)', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading')

    const sample = await screen.findByRole('region', { name: '로그인하면 학습 기록이 남습니다.' })
    const login = within(sample).getByRole('link', { name: '로그인' })
    // 글 화면의 행동 버튼은 '이 예시로 전략 진단하기' 하나다(plan Constitution Check II). 글 끝 견본은 단추 모양 대신
    // 글자 연결 모양을 쓴다. 이름만 보면 규칙이 지워져도 통과하므로 그 규칙의 선언도 함께 본다.
    expect(login.className).toMatch(/loginLink/)
    const declarations = cssDeclarations('components/learning/me/GuestSample.module.css', '.loginLink')
    expect(declarations).not.toMatch(/background/)
    // 둘레 글자와 색만으로 가르지 않게 밑줄과 굵기를 두고, 누르는 높이는 44px이다(WCAG 1.4.1, design-notes §6).
    expect(declarations).toMatch(/text-decoration:\s*underline/)
    expect(declarations).toMatch(/font-weight:\s*650/)
    expect(declarations).toMatch(/min-height:\s*44px/)
  })

  it('로그인 연결은 지금 글 주소만 014 키에 적고 로그인 화면으로 간다', async () => {
    const user = userEvent.setup()
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    loaded(currentArticle)

    renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('link', { name: '로그인' }))

    expect(await screen.findByText(/로그인 화면/)).not.toBeNull()
    expect(setItem.mock.calls).toEqual([['thorp.auth.returnTarget', '/learn/swell-reading']])
  })

  it('로그인 사용자에게는 비로그인 확인 문제와 로그인 안내를 보이지 않는다', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', signedInAuth)

    expect(await screen.findByRole('link', { name: /다음 글/ })).not.toBeNull()
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
  })

  it('다시 확인하느라 숨긴 동안 빠진 초점을 확인이 끝나면 풀이로 되돌린다', async () => {
    const user = userEvent.setup()
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))
    const explanation = screen.getByText('합성 풀이 하나다.', { exact: false })
    expect(document.activeElement).toBe(explanation)

    setAuth(checkingAuth)
    // 브라우저는 숨긴 요소의 초점을 문서 몸통으로 옮긴다. jsdom은 그러지 않으므로 직접 흉내 낸다.
    explanation.blur()
    expect(document.activeElement).toBe(document.body)

    setAuth(anonymousAuthContext)
    expect(document.activeElement).toBe(explanation)
  })

  it('사용자가 다른 곳을 눌러 옮긴 초점은 확인이 끝나도 되돌리지 않는다', async () => {
    const user = userEvent.setup()
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))
    const explanation = screen.getByText('합성 풀이 하나다.', { exact: false })

    await user.click(screen.getByRole('heading', { level: 1, name: '너울 읽기' }))
    explanation.blur()
    setAuth(checkingAuth)
    setAuth(anonymousAuthContext)

    expect(document.activeElement).not.toBe(explanation)
  })

  it('로그인 상태를 다시 확인하는 동안 확인 문제를 숨기기만 하고, 비로그인으로 돌아오면 고른 답이 그대로다', async () => {
    const user = userEvent.setup()
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()

    // 탭이 다시 보이면 014가 상태를 checking으로 둔다(research R33).
    setAuth(checkingAuth)
    const quiz = screen.getByText('합성 질문 하나다.').closest('section')!
    expect(quiz.parentElement?.className).toMatch(/concealed/)
    // 이름만 보면 규칙이 지워져도 통과하므로, 숨김 규칙이 실제로 자리를 둔 채 감추는지 함께 본다(R33).
    expect(
      cssDeclarations('components/learning/article/ArticleEnd.module.css', '.concealed'),
    ).toMatch(/visibility:\s*hidden/)
    expect(
      screen
        .getByRole('region', { name: '로그인하면 학습 기록이 남습니다.' })
        .parentElement?.className,
    ).toMatch(/concealed/)

    setAuth(anonymousAuthContext)
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(screen.queryByRole('button', { name: /합성 보기 나/ })).toBeNull()
    expect(quiz.parentElement?.className ?? '').not.toMatch(/concealed/)
  })

  it('비로그인에서 로그인으로 바뀌면 비로그인으로 답한 확인 문제를 버리고, 다시 비로그인이 되면 처음부터다', async () => {
    const user = userEvent.setup()
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))

    setAuth(checkingAuth)
    setAuth(signedInAuth)
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()

    setAuth(anonymousAuthContext)
    expect(screen.getByRole('button', { name: /합성 보기 나/ })).not.toBeNull()
    expect(screen.queryByText('합성 풀이 하나다.', { exact: false })).toBeNull()
  })

  it('처음 확인하는 동안에는 확인 문제와 로그인 안내를 그리지 않는다', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', checkingAuth)

    expect(await screen.findByRole('link', { name: /다음 글/ })).not.toBeNull()
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
  })

  it('글 안 도움말의 글 연결은 받은 learningReturn을 그대로 싣는다', async () => {
    const user = userEvent.setup()
    glossaryMocks.fetchGlossaryEntry.mockResolvedValue({
      status: 'ready',
      entry: {
        termId: 'ebb-gap',
        label: '썰물 간격',
        sentence: '합성 뜻 문장이다.',
        articleId: 'tide-reading',
        articleTitle: '물때 읽기',
      },
    })
    loaded(currentArticle)

    renderArticle({
      pathname: '/learn/swell-reading',
      state: { learningReturn: { path: '/market-flow', label: '' } },
    })
    await user.click(await screen.findByRole('button', { name: '썰물 간격 도움말' }))
    loaded(tideArticle)
    await user.click(await screen.findByRole('link', { name: '‘물때 읽기’ 글 읽기' }))

    await screen.findByRole('heading', { level: 1, name: '물때 읽기' })
    expect(screen.getByRole('link', { name: /시장 흐름으로 돌아가기/ })).not.toBeNull()
  })

  it('경로 연결은 받은 learningReturn을 그대로 넘긴다', async () => {
    const user = userEvent.setup()
    const received = { learningReturn: { path: '/strategy', label: '전략 선택' } }
    loaded(currentArticle)

    renderArticle({ pathname: '/learn/swell-reading', state: received })
    const crumb = await screen.findByRole('navigation', { name: '경로' })
    await user.click(within(crumb).getByRole('link', { name: '배우기' }))

    expect(await screen.findByText(/주제 목록 화면/)).not.toBeNull()
    expect(probedState).toEqual(received)
  })

  it('경로의 단계 연결은 목록의 그 단계 자리로 가며 받은 learningReturn을 싣는다(T203)', async () => {
    const user = userEvent.setup()
    const received = { learningReturn: { path: '/strategy', label: '전략 선택' } }
    loaded(currentArticle)

    renderArticle({ pathname: '/learn/swell-reading', state: received })
    const crumb = await screen.findByRole('navigation', { name: '경로' })
    await user.click(within(crumb).getByRole('link', { name: '2단계 · 너울과 위험' }))

    expect((await screen.findByText(/주제 목록 화면/)).textContent).toContain('/learn#stage-2')
    expect(probedState).toEqual(received)
  })

  it('비로그인으로 글을 열고 확인 문제에 답하고 닫는 동안 학습 기록을 브라우저 저장소에 쓰지 않는다', async () => {
    const user = userEvent.setup()
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    loaded(currentArticle)

    const { unmount } = renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('button', { name: '합성 보기 나' }))
    unmount()

    expect(setItem).not.toHaveBeenCalled()
  })
})

/** 013 T143. 예시는 글마다 고른다(FR-003, FR-021, quickstart 9-1). */
describe('LearningArticlePage — 예시', () => {
  beforeEach(() => {
    apiMocks.fetchLearningArticle.mockReset()
    probedState = undefined
  })

  it('예시가 없는 새 기준 글에는 예시 구역·버튼·투자 권고 안내가 없다', async () => {
    loaded(currentArticle)

    renderArticle('/learn/swell-reading')

    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    expect(screen.queryByRole('region', { name: '예시' })).toBeNull()
    expect(screen.queryByRole('button', { name: '이 예시로 전략 진단하기' })).toBeNull()
    expect(screen.queryByText('설명을 위한 예시이며 투자 권고가 아닙니다.')).toBeNull()
  })

  it('예시가 있으면 버튼은 본문 끝 표지 뒤, 글 끝 자리 앞에 있다', async () => {
    loaded({ ...currentArticle, example: tideArticle.example })

    const { container } = renderArticle('/learn/swell-reading')

    const button = await screen.findByRole('button', { name: '이 예시로 전략 진단하기' })
    const end = container.querySelector('[data-article-end]')!
    const quiz = screen.getByRole('region', { name: '확인 문제' })
    expect(end.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(button.compareDocumentPosition(quiz) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('누르면 002 계약 §2 모양 그대로 섹터 선택으로 간다', async () => {
    const user = userEvent.setup()
    loaded({ ...currentArticle, example: tideArticle.example })

    renderArticle('/learn/swell-reading')
    await user.click(await screen.findByRole('button', { name: '이 예시로 전략 진단하기' }))

    expect(await screen.findByText(/섹터 선택 화면/)).not.toBeNull()
    expect(probedState).toEqual({
      sectorSelectionExample: {
        source: 'learning',
        topicId: 'swell-reading',
        selected: [
          { code: 'ALPHA_DOCK', weightTenths: 625 },
          { code: 'BETA_DOCK', weightTenths: 375 },
        ],
      },
    })
  })
})

/**
 * 013 T189·T190. 로그인 사용자의 글 끝 기록(FR-020~FR-022, research R32·R33, quickstart 10-1~10-4·10-10).
 *
 * <p>완료 훅이 `IntersectionObserver`를 쓰므로 대역을 이 파일 안에 둔다. `src/test/setup.ts`에 전역으로 두면
 * `src/hooks/useNearViewport.test.tsx`의 '없는 환경' 경우가 깨진다.
 */
describe('LearningArticlePage — 로그인 기록', () => {
  class FakeIntersectionObserver {
    static instances: FakeIntersectionObserver[] = []
    readonly callback: IntersectionObserverCallback
    disconnected = false

    constructor(callback: IntersectionObserverCallback) {
      this.callback = callback
      FakeIntersectionObserver.instances.push(this)
    }

    observe() {}
    unobserve() {}
    disconnect() {
      this.disconnected = true
    }
    takeRecords(): IntersectionObserverEntry[] {
      return []
    }
  }

  /** 본문 끝 표지가 화면에 들어왔다. 실제 브라우저처럼 연결을 끊은 관찰자는 부르지 않는다. */
  function reachArticleEnd() {
    act(() => {
      for (const observer of FakeIntersectionObserver.instances) {
        if (observer.disconnected) continue
        observer.callback(
          [{ isIntersecting: true } as IntersectionObserverEntry],
          observer as unknown as IntersectionObserver,
        )
      }
    })
  }

  /** 머문 시간 조건을 바로 채우는 글. 본문 끝만 남는다. */
  const quickArticle = { ...currentArticle, minDwellSeconds: 0 }
  const otherUserAuth: AuthContextValue = {
    ...signedInAuth,
    user: { ...authenticatedUser, userId: 8 },
  }
  const recheck = vi.fn<AuthContextValue['recheck']>()
  const expiredAuth: AuthContextValue = {
    ...anonymousAuthContext,
    status: 'expired',
    user: null,
    recheck,
  }
  const errorAuth: AuthContextValue = { ...anonymousAuthContext, status: 'error', user: null }

  /** 본문 높이 1000px. 화면 아래 끝(768px)이 본문의 몇 %에 있는지가 읽은 비율이다(research R32). */
  function readTo(container: HTMLElement, percent: number) {
    const body = container.querySelector('[data-article-end]')!.parentElement!
    vi.spyOn(body, 'getBoundingClientRect').mockReturnValue({
      top: 768 - percent * 10,
      height: 1000,
    } as DOMRect)
    act(() => {
      window.dispatchEvent(new Event('scroll'))
    })
  }
  const logoutAuth: AuthContextValue = {
    ...signedInAuth,
    status: 'logoutUnconfirmed',
  }

  let scrollTo: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    apiMocks.fetchLearningArticle.mockReset()
    for (const mock of Object.values(meMocks)) mock.mockReset()
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: unreadRecord })
    meMocks.fetchLearningMe.mockResolvedValue(overviewFailure)
    recheck.mockReset()
    recheck.mockResolvedValue({ status: 'expired', user: null })
    FakeIntersectionObserver.instances = []
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('완료한 글은 글 기록을 한 번 받아 완료 카드와 저장된 첫 답을 보이고 이어서 읽기 줄이 없다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: completedRecord })
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', signedInAuth)

    const card = await screen.findByRole('region', { name: '완료 · 2026-11-20' })
    expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(1)
    expect(meMocks.fetchLearnerArticle.mock.calls[0][0]).toBe('swell-reading')
    expect(within(card).getByText('2단계 · 4편 중 2편 읽음')).not.toBeNull()
    expect(within(card).getByRole('region', { name: '확인 문제' })).not.toBeNull()
    expect(within(card).getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(within(card).getByRole('link', { name: /다음 글.*물때 읽기/ })).not.toBeNull()
    // 완료 카드 안에 다음 글이 있으므로 글 끝의 다음 글 연결을 겹쳐 두지 않는다.
    expect(screen.getAllByRole('link', { name: /다음 글/ })).toHaveLength(1)
    expect(screen.queryByText(/까지 읽었습니다/)).toBeNull()
    expect(meMocks.completeArticle).not.toHaveBeenCalled()
  })

  it('기록을 받기 전에는 완료 카드·확인 문제 자리를 비우고, 받지 못하면 한 줄과 다시 시도다', async () => {
    const user = userEvent.setup()
    meMocks.fetchLearnerArticle.mockResolvedValueOnce({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '연결 실패',
    })
    meMocks.fetchLearnerArticle.mockResolvedValueOnce({ status: 'ok', data: completedRecord })
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
    expect(screen.queryByText(/안 읽음|레벨 1/)).toBeNull()

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.getByText('학습 기록을 불러오지 못했습니다.')).not.toBeNull()
    await user.click(retry)

    expect(await screen.findByRole('region', { name: '완료 · 2026-11-20' })).not.toBeNull()
    expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(2)
  })

  it('본문 끝과 머문 시간이 차면 완료를 한 번 보내고 완료 카드와 조용한 알림을 보인다', async () => {
    meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    expect(meMocks.completeArticle).not.toHaveBeenCalled()

    reachArticleEnd()

    const card = await screen.findByRole('region', { name: '완료 · 2026-11-20' })
    expect(meMocks.completeArticle).toHaveBeenCalledTimes(1)
    expect(within(card).getByText('2단계 · 완주 · 4편')).not.toBeNull()
    const notice = '2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다.'
    expect(within(card).getByText(notice)).not.toBeNull()
    expect(await within(screen.getByRole('status')).findByText(notice)).not.toBeNull()
    reachArticleEnd()
    expect(meMocks.completeArticle).toHaveBeenCalledTimes(1)
  })

  it("완료 뒤 '나의 학습'을 다시 받아 알림과 카드 끝에 다음 목표를 덧붙인다(T204·T208)", async () => {
    meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
    meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: learnerOverview })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    // 완료 카드가 놓이기 전에는 부르지 않는다.
    expect(meMocks.fetchLearningMe).not.toHaveBeenCalled()

    reachArticleEnd()

    const goal = '다음 레벨까지 50, 다음 배지(모두 맞힘 1편)까지 1편입니다.'
    const notice = `2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다. ${goal}`
    expect(await within(screen.getByRole('status')).findByText(notice)).not.toBeNull()
    const card = screen.getByRole('region', { name: '완료 · 2026-11-20' })
    expect(within(card).getByText(notice)).not.toBeNull()
    expect(within(card).getByText('다음 배지')).not.toBeNull()
  })

  it("다시 연 완료 글도 '나의 학습'을 받아 단계 완주 메달을 배지로 그린다(US6 시나리오 8, T204)", async () => {
    // 2단계 완주 배지를 받은 뒤 그 단계에 글이 더해져, 지금은 4편 중 2편만 읽은 상태다.
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: completedRecord })
    meMocks.fetchLearningMe.mockResolvedValue({
      status: 'ok',
      data: {
        ...learnerOverview,
        badges: learnerOverview.badges.map((item) =>
          item.badgeKey === 'stage-swell-and-risk'
            ? { ...item, status: 'EARNED', earnedAt: '2026-11-18T00:00:00Z', progress: null }
            : item,
        ),
      },
    })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)

    const card = await screen.findByRole('region', { name: '완료 · 2026-11-20' })
    expect(within(card).getByText('2단계 · 4편 중 2편 읽음')).not.toBeNull()
    await waitFor(() => expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(card.querySelector('[data-medal="stage-earned"]')).not.toBeNull())
    expect(card.querySelector('[data-medal="stage-notYet"]')).toBeNull()
    // 이 화면에서 받은 것이 없으므로 받은 것 칸과 알림은 그대로 비어 있다.
    expect(within(card).queryByText('이번에 받은 경험치')).toBeNull()
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it("완료 뒤 '나의 학습' 요청의 정산으로 생긴 배지도 완료 카드와 알림에 더한다(FR-030)", async () => {
    meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
    meMocks.fetchLearningMe.mockResolvedValue({
      status: 'ok',
      data: {
        ...learnerOverview,
        gained: {
          exp: [],
          expTotal: 0,
          badges: [{ badgeKey: 'persistence', family: 'SPECIAL', name: '끈기' }],
          levelBefore: 3,
          levelAfter: 3,
        },
      },
    })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    reachArticleEnd()

    const notice =
      '2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다. 새 배지: 끈기. ' +
      '다음 레벨까지 50, 다음 배지(모두 맞힘 1편)까지 1편입니다.'
    expect(await within(screen.getByRole('status')).findByText(notice)).not.toBeNull()
    const card = screen.getByRole('region', { name: '완료 · 2026-11-20' })
    const badgeColumn = within(card).getByText('새 배지').parentElement!
    expect(badgeColumn.textContent).toContain('끈기')
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)
  })

  /** 운영자의 변경으로 채워져 '나의 학습' 요청의 정산에서 생긴 배지(계약 §4). */
  const settledPersistence = {
    ...learnerOverview,
    gained: {
      exp: [],
      expTotal: 0,
      badges: [{ badgeKey: 'persistence', family: 'SPECIAL', name: '끈기' }],
      levelBefore: 3,
      levelAfter: 3,
    },
  }
  const lateNotice = '새 배지: 끈기. 다음 레벨까지 50, 다음 배지(모두 맞힘 1편)까지 1편입니다.'

  it("경험치만 받은 완료라 덧붙일 알림이 없어도 '나의 학습'의 정산으로 생긴 배지는 알린다(계약 §4)", async () => {
    meMocks.completeArticle.mockResolvedValue({
      status: 'ok',
      data: {
        ...completionResult,
        stageProgress: { ...completionResult.stageProgress, completedCount: 3, stageCompleted: false },
        gained: {
          exp: [{ kind: 'ARTICLE_COMPLETED', points: 20 }],
          expTotal: 20,
          badges: [],
          levelBefore: 2,
          levelAfter: 2,
        },
      },
    })
    meMocks.fetchLearningMe.mockResolvedValue({ status: 'ok', data: settledPersistence })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    reachArticleEnd()

    expect(await within(screen.getByRole('status')).findByText(lateNotice)).not.toBeNull()
    expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1)
  })

  it("'나의 학습'이 기다림(4초)을 넘겨 오면 받은 것만 먼저 읽히고, 그 정산으로 생긴 배지는 따로 한 번 알린다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let resolveMe: (value: unknown) => void = () => {}
      meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
      meMocks.fetchLearningMe.mockReturnValue(new Promise((resolve) => (resolveMe = resolve)))
      loaded(quickArticle)

      renderArticle('/learn/swell-reading', signedInAuth)
      await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
      await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
      reachArticleEnd()
      await waitFor(() => expect(meMocks.fetchLearningMe).toHaveBeenCalledTimes(1))

      await act(async () => {
        vi.advanceTimersByTime(4100)
      })
      const first = '2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다.'
      expect(await within(screen.getByRole('status')).findByText(first)).not.toBeNull()

      await act(async () => resolveMe({ status: 'ok', data: settledPersistence }))
      expect(await within(screen.getByRole('status')).findByText(lateNotice)).not.toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('알림 영역은 완료 전부터 비어 있는 채로 있고, 뒤에 받은 것은 그것만 알린다', async () => {
    const user = userEvent.setup()
    const allWrong = {
      total: 2,
      answered: 2,
      correct: 0,
      allAnswered: true,
      mastered: false,
      masteredVia: null,
      retryAvailable: true,
    }
    meMocks.fetchLearnerArticle.mockResolvedValue({
      status: 'ok',
      data: {
        ...unreadRecord,
        answers: [
          { questionId: 'q1', choiceId: 'c1', correct: false, answeredAt: '2026-11-20T03:11:00Z' },
          { questionId: 'q3', choiceId: 'c5', correct: false, answeredAt: '2026-11-20T03:12:00Z' },
        ],
        quiz: allWrong,
      },
    })
    meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
    meMocks.retryAnswer.mockResolvedValue({ status: 'ok', data: retryResult() })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    // 카드가 놓이기 전부터 알림 영역이 비어 있는 채로 있다. 미리 있어야 바뀐 글자가 읽힌다(FR-030).
    const status = await screen.findByRole('status')
    expect(status.textContent).toBe('')

    reachArticleEnd()
    expect(await within(status).findByText(/2단계 글 4편을 모두 읽었습니다/)).not.toBeNull()

    await user.click(await screen.findByRole('button', { name: '다시 풀기' }))
    const retry = screen.getByRole('heading', { name: '다시 풀기' }).parentElement as HTMLElement
    await user.click(within(retry).getByRole('button', { name: /합성 보기 나/ }))

    // 앞서 알린 단계 완주·배지·레벨은 다시 읽지 않고 방금 받은 끈기 배지만 알린다.
    await waitFor(() => expect(status.textContent).toBe('새 배지: 끈기.'))
    expect(screen.getByText(/새 배지: 2단계 완주\. 새 배지: 끈기\./)).not.toBeNull()
    expect(screen.getByText('· 익힘')).not.toBeNull()
    expect(screen.getByText('익힘이 되었습니다.')).not.toBeNull()
  })

  it('완료가 기록되지 않으면 완료 카드·확인 문제 대신 기록되지 않았습니다와 다시 시도이고 글과 다음 글은 그대로다', async () => {
    const user = userEvent.setup()
    meMocks.completeArticle
      .mockResolvedValueOnce({ status: 'failed', kind: 'network', code: null, message: '실패' })
      .mockResolvedValueOnce({ status: 'ok', data: completionResult })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    await screen.findByRole('link', { name: /다음 글/ })
    reachArticleEnd()

    // 완료는 누르지 않아도 나가므로 실패를 화면 낭독기에 바로 알린다(FR-019).
    expect(within(await screen.findByRole('alert')).getByText('기록되지 않았습니다.')).not.toBeNull()
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: '너울 읽기' })).not.toBeNull()
    expect(screen.getByRole('link', { name: /다음 글/ })).not.toBeNull()

    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('region', { name: '완료 · 2026-11-20' })).not.toBeNull()
    expect(meMocks.completeArticle).toHaveBeenCalledTimes(2)
  })

  it('확인 문제에 답하면 저장 응답 뒤에 결과를 보이고 받은 경험치를 카드에 더한다', async () => {
    const user = userEvent.setup()
    meMocks.fetchLearnerArticle.mockResolvedValue({
      status: 'ok',
      data: { ...completedRecord, answers: [], quiz: openQuiz },
    })
    meMocks.answerQuestion.mockResolvedValue({ status: 'ok', data: answerResult() })
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))

    expect(meMocks.answerQuestion).toHaveBeenCalledWith('swell-reading', 'q1', 'c2')
    expect(await screen.findByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(screen.getByText('첫 답 정답')).not.toBeNull()
    expect(screen.getByText('· 익힘', { exact: false })).not.toBeNull()
  })

  it('읽는 중 글은 맨 위에서 열고 기록된 비율의 이어서 읽기 줄을 보이며, 누르면 그 위치로 옮긴다', async () => {
    const user = userEvent.setup()
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: readingRecord })
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', signedInAuth)

    expect(await screen.findByText('62%까지 읽었습니다')).not.toBeNull()
    // 앞에 그 글 단계 색의 '읽는 중' 도장을 작게 둔다. 진행 호가 기록된 비율만큼 찬다(T209).
    const resumeStamp = screen.getByText('62%까지 읽었습니다').closest('p')!.querySelector('[data-stamp="reading"]')!
    expect(resumeStamp.closest('[aria-hidden="true"]')).not.toBeNull()
    expect(resumeStamp.querySelector('[data-part="progress"]')?.getAttribute('stroke-dasharray')).toBe('62 100')
    expect(scrollTo).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '이어서 읽기' }))
    expect(scrollTo).toHaveBeenCalledTimes(1)
  })

  it('learningResume: true로 열면 받은 글 기록의 위치로 한 번 옮긴다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: readingRecord })
    loaded(currentArticle)

    const { container } = renderArticle(
      { pathname: '/learn/swell-reading', state: { learningResume: true } },
      signedInAuth,
    )
    await screen.findByText('62%까지 읽었습니다')
    const body = container.querySelector('[data-article-end]')!.parentElement!
    vi.spyOn(body, 'getBoundingClientRect').mockReturnValue({ top: 0, height: 1000 } as DOMRect)

    await waitFor(() => expect(scrollTo).toHaveBeenCalledTimes(1))
    // 기록된 62%가 화면 아래 끝에 온다. 실려 온 값이 아니라 받은 기록의 값이다(data-model §15).
    expect(scrollTo.mock.calls[0][0]).toEqual({ top: expect.any(Number) })
  })

  it('learningResume이 true가 아니거나 완료한 글이면 맨 위에서 연다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: readingRecord })
    loaded(currentArticle)

    const first = renderArticle(
      {
        pathname: '/learn/swell-reading',
        state: { learningResume: { articleId: 'swell-reading', percent: 62 } },
      },
      signedInAuth,
    )
    await screen.findByText('62%까지 읽었습니다')
    first.unmount()

    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: completedRecord })
    renderArticle({ pathname: '/learn/swell-reading', state: { learningResume: true } }, signedInAuth)
    await screen.findByRole('region', { name: '완료 · 2026-11-20' })

    expect(scrollTo).not.toHaveBeenCalled()
  })

  it('비로그인이면 learningResume이 있어도 옮기지 않는다', async () => {
    loaded(currentArticle)

    renderArticle({ pathname: '/learn/swell-reading', state: { learningResume: true } })
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })

    expect(scrollTo).not.toHaveBeenCalled()
    expect(meMocks.fetchLearnerArticle).not.toHaveBeenCalled()
  })

  it("읽는 중 글의 맨 위에 '읽은 정도' 막대를 두고, 더 내리면 늘고 완료한 글은 다 찬다", async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: readingRecord })
    loaded(currentArticle)

    const { container } = renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByText('62%까지 읽었습니다')
    const fill = () => container.querySelector<HTMLElement>('[class*="readBarFill"]')

    // 그림이라 화면 낭독기는 읽지 않는다. 글자 정보는 62% 줄이 맡는다(design-notes §6).
    expect(fill()?.parentElement?.getAttribute('aria-hidden')).toBe('true')
    expect(fill()?.style.width).toBe('62%')
    readTo(container, 80)
    expect(fill()?.style.width).toBe('80%')
    readTo(container, 30)
    expect(fill()?.style.width).toBe('80%')
  })

  it("기록을 받지 못했거나 비로그인이면 '읽은 정도' 막대를 그리지 않는다", async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '연결 실패',
    })
    loaded(currentArticle)

    const member = renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByText('학습 기록을 불러오지 못했습니다.')
    expect(member.container.querySelector('[class*="readBar"]')).toBeNull()
    member.unmount()

    const guest = renderArticle('/learn/swell-reading')
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    expect(guest.container.querySelector('[class*="readBar"]')).toBeNull()
  })

  it("재확인 동안 '62%까지 읽었습니다' 줄과 막대를 자리를 둔 채 숨긴다", async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: readingRecord })
    loaded(currentArticle)

    const { container, setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
    const line = (await screen.findByText('62%까지 읽었습니다')).closest('p')!

    setAuth(checkingAuth)
    expect(line.parentElement?.className).toMatch(/concealed/)
    expect(container.querySelector('[class*="readBar"]')?.className).toMatch(/concealed/)
    // 이름만 보면 규칙이 지워져도 통과하므로, 숨김 규칙이 실제로 자리를 둔 채 감추는지 함께 본다(R33).
    expect(cssDeclarations('pages/LearningArticlePage.module.css', '.concealed')).toMatch(
      /visibility:\s*hidden/,
    )

    setAuth(signedInAuth)
    expect(line.parentElement?.className ?? '').not.toMatch(/concealed/)
    expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(1)
  })

  it('checking을 거쳐 같은 사용자로 돌아오면 가장 멀리 읽은 비율을 이어 가 떠날 때 보낸다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: unreadRecord })
    loaded(currentArticle)

    const { container, setAuth, unmount } = renderArticle('/learn/swell-reading', signedInAuth)
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    readTo(container, 62)

    setAuth(checkingAuth)
    readTo(container, 20)
    setAuth(signedInAuth)
    unmount()

    await waitFor(() => expect(meMocks.recordPosition).toHaveBeenCalledTimes(1))
    expect(meMocks.recordPosition).toHaveBeenCalledWith('swell-reading', 62)
  })

  it('error 상태를 거쳐도 기록 자리를 숨기기만 하고, 같은 사용자로 돌아오면 다시 요청하지 않고 그대로 보인다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: completedRecord })
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
    const card = await screen.findByRole('region', { name: '완료 · 2026-11-20' })

    setAuth(errorAuth)
    expect(card.isConnected).toBe(true)
    expect(card.parentElement?.className).toMatch(/concealed/)

    setAuth(signedInAuth)
    expect(card.parentElement?.className ?? '').not.toMatch(/concealed/)
    expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(1)
  })

  it('checking을 거쳐 같은 사용자로 돌아오면 머문 시간을 이어 세고, 그동안 찬 완료는 확인이 끝난 뒤 보낸다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
      loaded(currentArticle)

      const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
      await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
      await screen.findByRole('link', { name: /다음 글/ })
      reachArticleEnd()
      act(() => {
        vi.advanceTimersByTime(60_000)
      })

      setAuth(checkingAuth)
      act(() => {
        vi.advanceTimersByTime(30_000)
      })
      // 확인하는 동안에는 보내지 않는다(R33).
      expect(meMocks.completeArticle).not.toHaveBeenCalled()

      setAuth(signedInAuth)
      await waitFor(() => expect(meMocks.completeArticle).toHaveBeenCalledTimes(1))
    } finally {
      vi.useRealTimers()
    }
  })

  it('다른 사용자로 확인되면 머문 시간을 처음부터 센다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
      loaded(currentArticle)

      const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
      await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
      await screen.findByRole('link', { name: /다음 글/ })
      reachArticleEnd()
      act(() => {
        vi.advanceTimersByTime(60_000)
      })

      setAuth(checkingAuth)
      setAuth(otherUserAuth)
      await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(2))
      reachArticleEnd()
      act(() => {
        vi.advanceTimersByTime(30_000)
      })
      expect(meMocks.completeArticle).not.toHaveBeenCalled()

      act(() => {
        vi.advanceTimersByTime(50_000)
      })
      await waitFor(() => expect(meMocks.completeArticle).toHaveBeenCalledTimes(1))
    } finally {
      vi.useRealTimers()
    }
  })

  it('다른 곳에서 글이 바뀌어 완료가 거절되면 다시 시도 대신 다시 불러오기이고, 화면을 둔 채 글과 기록을 다시 받는다', async () => {
    const user = userEvent.setup()
    meMocks.completeArticle.mockResolvedValue({ status: 'stale' })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    await screen.findByRole('link', { name: /다음 글/ })
    reachArticleEnd()

    expect(
      within(await screen.findByRole('alert')).getByText(
        '이 글이 바뀌었습니다. 글을 다시 불러와 주세요.',
      ),
    ).not.toBeNull()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()

    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))

    await waitFor(() => expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(2))
    // 화면을 내리지 않는다. 불러오는 중 문장 없이 글이 그대로 있다.
    expect(screen.queryByText('글을 불러오는 중입니다.')).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: '너울 읽기' })).not.toBeNull()
  })

  it("문제가 바뀌어 '다시 불러오기'를 눌러도 이번에 받은 경험치와 완료 카드를 잃지 않는다", async () => {
    const user = userEvent.setup()
    meMocks.completeArticle.mockResolvedValue({ status: 'ok', data: completionResult })
    meMocks.answerQuestion.mockResolvedValue({ status: 'questionNotFound' })
    loaded(quickArticle)

    renderArticle('/learn/swell-reading', signedInAuth)
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    await screen.findByRole('link', { name: /다음 글/ })
    reachArticleEnd()
    await screen.findByRole('region', { name: '완료 · 2026-11-20' })

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))
    await user.click(await screen.findByRole('button', { name: '다시 불러오기' }))

    await waitFor(() => expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(2))
    expect(screen.getByRole('region', { name: '완료 · 2026-11-20' })).not.toBeNull()
    expect(screen.getByRole('list', { name: '이번에 받은 경험치' })).not.toBeNull()
    // 다시 받은 기록으로 확인 문제를 처음부터 그린다. 바뀌었다는 줄은 남지 않는다.
    await waitFor(() =>
      expect(screen.queryByText('이 문제가 바뀌었습니다. 글을 다시 불러와 주세요.')).toBeNull(),
    )
  })

  it('재확인 동안 완료 카드와 확인 문제를 숨겼다가 같은 사용자면 다시 요청하지 않고 답과 카드를 그대로 보인다', async () => {
    const user = userEvent.setup()
    meMocks.fetchLearnerArticle.mockResolvedValue({
      status: 'ok',
      data: { ...completedRecord, answers: [], quiz: openQuiz },
    })
    meMocks.answerQuestion.mockResolvedValue({ status: 'ok', data: answerResult() })
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))
    await screen.findByText('합성 풀이 하나다.', { exact: false })
    const card = screen.getByRole('region', { name: '완료 · 2026-11-20' })

    setAuth(checkingAuth)
    expect(card.parentElement?.className).toMatch(/concealed/)

    setAuth(signedInAuth)
    expect(card.isConnected).toBe(true)
    expect(card.parentElement?.className ?? '').not.toMatch(/concealed/)
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(1)
  })

  it('다른 사용자로 확인되면 앞 사용자의 기록을 버리고 다시 받는다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValueOnce({ status: 'ok', data: completedRecord })
    meMocks.fetchLearnerArticle.mockResolvedValueOnce({ status: 'ok', data: unreadRecord })
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('region', { name: '완료 · 2026-11-20' })

    setAuth(checkingAuth)
    setAuth(otherUserAuth)

    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalledTimes(2))
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: '완료 · 2026-11-20' })).toBeNull(),
    )
  })

  it('로그아웃을 시작하면 그 즉시 기록 자리를 없앤다', async () => {
    meMocks.fetchLearnerArticle.mockResolvedValue({ status: 'ok', data: completedRecord })
    loaded(currentArticle)

    const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('region', { name: '완료 · 2026-11-20' })

    setAuth(logoutAuth)

    expect(screen.queryByRole('region', { name: /완료 ·/ })).toBeNull()
    expect(screen.queryByRole('region', { name: '확인 문제' })).toBeNull()
  })

  it('거부된 기록 요청 없이 만료되면 기록되지 않았다고 말하지 않고, 확인 문제를 기록 없이 풀게 하며 재로그인을 안내한다', async () => {
    const user = userEvent.setup()
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    loaded(currentArticle)

    renderArticle('/learn/swell-reading', expiredAuth)

    expect(
      await screen.findByText('로그인이 만료되었습니다. 다시 로그인하면 이 글로 돌아옵니다.'),
    ).not.toBeNull()
    // 실패한 요청이 없으므로 이 문장을 두지 않는다. 이미 기록된 것을 기록되지 않았다고 말하지 않는다(spec Edge Cases).
    expect(screen.queryByText('기록되지 않았습니다.')).toBeNull()
    expect(screen.queryByText('로그인하면 학습 기록이 남습니다.')).toBeNull()
    expect(meMocks.fetchLearnerArticle).not.toHaveBeenCalled()

    // 만료 동안에도 확인 문제는 비로그인처럼 기록 없이 풀 수 있다(FR-012, spec Edge Cases).
    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(meMocks.answerQuestion).not.toHaveBeenCalled()

    await user.click(screen.getByRole('link', { name: '다시 로그인' }))
    expect(setItem.mock.calls).toEqual([['thorp.auth.returnTarget', '/learn/swell-reading']])
  })

  it('기록 요청이 만료로 거부되면 기록되지 않았습니다와 다시 시도를 함께 보이고, 다시 시도는 로그인 상태를 다시 확인한다', async () => {
    const user = userEvent.setup()
    meMocks.completeArticle.mockResolvedValue({ status: 'unauthenticated' })
    loaded(quickArticle)

    const { setAuth } = renderArticle('/learn/swell-reading', signedInAuth)
    await waitFor(() => expect(meMocks.fetchLearnerArticle).toHaveBeenCalled())
    await screen.findByRole('link', { name: /다음 글/ })
    reachArticleEnd()
    await waitFor(() => expect(meMocks.completeArticle).toHaveBeenCalledTimes(1))
    // 401을 받으면 014가 전역 상태를 만료로 바꾼다.
    setAuth(expiredAuth)

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('기록되지 않았습니다.')).not.toBeNull()
    expect(
      screen.getByText('로그인이 만료되었습니다. 다시 로그인하면 이 글로 돌아옵니다.'),
    ).not.toBeNull()

    await user.click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(recheck).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['이전 판', previousTideArticle],
    ['첫 단계 공개 전', tideArticle],
  ])('%s 글은 로그인해도 기록을 요청하지 않는다', async (_, article) => {
    loaded(article)

    const { unmount } = renderArticle('/learn/tide-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1 })
    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })
    unmount()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(meMocks.fetchLearnerArticle).not.toHaveBeenCalled()
    expect(meMocks.completeArticle).not.toHaveBeenCalled()
    expect(meMocks.recordPosition).not.toHaveBeenCalled()
    expect(meMocks.sendPositionOnLeave).not.toHaveBeenCalled()
  })

  it('로그인 사용자의 글은 떠날 때 위치를 보내고, 비로그인에서는 fetch도 apiClient도 부르지 않는다', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const requestSpies = (['get', 'post', 'put', 'delete', 'request'] as const).map((method) =>
      vi.spyOn(apiClient, method),
    )
    loaded(currentArticle)

    const guest = renderArticle('/learn/swell-reading')
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })
    guest.unmount()

    expect(fetchSpy).not.toHaveBeenCalled()
    for (const spy of requestSpies) expect(spy).not.toHaveBeenCalled()
    expect(meMocks.recordPosition).not.toHaveBeenCalled()
    expect(meMocks.sendPositionOnLeave).not.toHaveBeenCalled()

    const member = renderArticle('/learn/swell-reading', signedInAuth)
    await screen.findByRole('heading', { level: 1, name: '너울 읽기' })
    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })
    member.unmount()

    expect(meMocks.sendPositionOnLeave).toHaveBeenCalledTimes(1)
    expect(meMocks.sendPositionOnLeave.mock.calls[0][0]).toBe('swell-reading')
    // 숨김·닫기로 이미 보냈으므로 화면을 내릴 때 다시 보내지 않는다.
    expect(meMocks.recordPosition).not.toHaveBeenCalled()
  })

  it('로그인 사용자도 학습 기록을 브라우저 저장소에 쓰지 않는다', async () => {
    const user = userEvent.setup()
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    meMocks.fetchLearnerArticle.mockResolvedValue({
      status: 'ok',
      data: { ...completedRecord, answers: [], quiz: openQuiz },
    })
    meMocks.answerQuestion.mockResolvedValue({ status: 'ok', data: answerResult() })
    loaded(currentArticle)

    const { unmount } = renderArticle('/learn/swell-reading', signedInAuth)
    await user.click(await screen.findByRole('button', { name: /합성 보기 나/ }))
    await screen.findByText('합성 풀이 하나다.', { exact: false })
    unmount()

    expect(setItem).not.toHaveBeenCalled()
  })
})

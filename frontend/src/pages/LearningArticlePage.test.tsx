import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LearningArticleResult } from '@/api/learning'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import { cssDeclarations } from '@/test/cssRule'
import {
  currentArticle,
  harborArticle,
  previousTideArticle,
  tideArticle,
} from '@/test/learningFixtures'
import { LearningArticlePage } from './LearningArticlePage'

/**
 * 013 T029·T031·T043(1판)과 T134·T143(2판). US1 시나리오 4, quickstart 3-8 화면 쪽·4-1·4-4·9-1~9-6,
 * FR-019.
 *
 * <p>2판 글 화면은 `useAuth`를 부르므로 `AuthContext.Provider`로 감싸 그린다. 기본은 비로그인이다(T104).
 */

const apiMocks = vi.hoisted(() => ({
  fetchLearningArticle: vi.fn(),
}))
const glossaryMocks = vi.hoisted(() => ({ fetchGlossaryEntry: vi.fn() }))

vi.mock('@/api/learning', () => apiMocks)
vi.mock('@/api/learningGlossary', () => glossaryMocks)
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

  it('로그인 연결은 안내 문장과 떨어진 44px 단추 모양이라 색만으로 가르지 않는다', () => {
    const declarations = cssDeclarations('components/learning/me/GuestSample.module.css', '.login')
    expect(declarations).toMatch(/background:/)
    expect(declarations).toMatch(/height:\s*44px/)
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

  it('글을 열고 확인 문제에 답하고 닫는 동안 브라우저 저장소에 쓰지 않는다', async () => {
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

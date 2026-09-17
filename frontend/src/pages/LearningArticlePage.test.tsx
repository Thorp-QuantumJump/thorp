import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LearningArticleResult } from '@/api/learning'
import { harborArticle, tideArticle } from '@/test/learningFixtures'
import { LearningArticlePage } from './LearningArticlePage'

/** 013 T029·T031·T043. US1 시나리오 4, quickstart 3-8 화면 쪽·4-1·4-4, FR-019. */

const apiMocks = vi.hoisted(() => ({
  fetchLearningArticles: vi.fn(),
  fetchLearningArticle: vi.fn(),
}))

vi.mock('@/api/learning', () => apiMocks)
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

let listState: unknown = undefined

function ListProbe() {
  listState = useLocation().state
  return <p>주제 목록 화면</p>
}

function renderArticle(entry: string | { pathname: string; state: unknown }) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/learn/:articleId" element={<LearningArticlePage />} />
        <Route path="/learn" element={<ListProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

const progressWords = /학습 완료|수강|진도|완료율/

describe('LearningArticlePage', () => {
  beforeEach(() => {
    apiMocks.fetchLearningArticle.mockReset()
    listState = undefined
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('경로의 articleId로 한 번 부르고 제목·질문 하나·본문을 보인다', async () => {
    apiMocks.fetchLearningArticle.mockResolvedValue({
      status: 'loaded',
      article: tideArticle,
    } satisfies LearningArticleResult)

    renderArticle('/learn/tide-reading')

    expect(await screen.findByRole('heading', { level: 1, name: '물때 읽기' })).not.toBeNull()
    expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchLearningArticle.mock.calls[0][0]).toBe('tide-reading')
    expect(screen.getAllByText('물이 빠지는 속도는 왜 들어오는 속도와 다른가?')).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 2, name: '물이 가장 낮았던 때' })).not.toBeNull()
    expect(document.body.textContent).not.toMatch(progressWords)
  })

  it('예시 비중을 % 표기로 보이고 투자 권고가 아니라는 문장과 진입 버튼을 둔다', async () => {
    apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'loaded', article: tideArticle })

    renderArticle('/learn/tide-reading')

    const example = await screen.findByRole('region', { name: '예시' })
    expect(within(example).getByText('알파 부두')).not.toBeNull()
    expect(within(example).getByText('62.5%')).not.toBeNull()
    expect(within(example).getByText('37.5%')).not.toBeNull()
    expect(within(example).getByText('설명을 위한 예시이며 투자 권고가 아닙니다.')).not.toBeNull()
    expect(within(example).getByRole('button', { name: '이 예시로 비중 비교하기' })).not.toBeNull()
  })

  it('대응표에 없는 섹터는 이름 대신 코드로 보이고 진입 버튼 대신 안내를 둔다', async () => {
    apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'loaded', article: harborArticle })

    renderArticle('/learn/harbor-notes')

    const example = await screen.findByRole('region', { name: '예시' })
    expect(within(example).getByText('GAMMA_DOCK')).not.toBeNull()
    expect(within(example).queryByRole('button')).toBeNull()
    expect(within(example).getByRole('link', { name: '빈 섹터 선택 화면으로 가기' })).not.toBeNull()
  })

  it('출처에 url이 있으면 링크로, 없으면 텍스트로 보인다', async () => {
    apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'loaded', article: tideArticle })

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
    expect(screen.getByRole('link', { name: '주제 목록으로' }).getAttribute('href')).toBe('/learn')
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
    // 글을 받지 못해도 진단을 시작하는 연결은 남는다(US1 시나리오 7, T081).
    expect(
      screen.getByRole('link', { name: /비중 비교 시작하기/ }).getAttribute('href'),
    ).toBe('/diagnosis/sectors')

    await user.tab()
    expect(document.activeElement).toBe(retry)
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { level: 1, name: '물때 읽기' })).not.toBeNull()
    expect(apiMocks.fetchLearningArticle).toHaveBeenCalledTimes(2)
  })

  it('목록 연결은 받은 navigation state를 그대로 넘긴다', async () => {
    const user = userEvent.setup()
    const received = { learningReturn: { path: '/strategy', label: '전략 선택' } }
    apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'loaded', article: tideArticle })

    renderArticle({ pathname: '/learn/tide-reading', state: received })

    await user.click(await screen.findByRole('link', { name: '주제 목록으로' }))

    expect(await screen.findByText('주제 목록 화면')).not.toBeNull()
    expect(listState).toEqual(received)
  })

  it('글을 열고 닫는 동안 브라우저 저장소에 쓰지 않는다', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    apiMocks.fetchLearningArticle.mockResolvedValue({ status: 'loaded', article: tideArticle })

    const { unmount } = renderArticle('/learn/tide-reading')
    await screen.findByRole('heading', { level: 1, name: '물때 읽기' })
    unmount()

    expect(setItem).not.toHaveBeenCalled()
  })
})

import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LearningListResult } from '@/api/learning'
import { articleSummaries } from '@/test/learningFixtures'
import { renderWithRouter } from '@/test/renderWithRouter'
import { LearningListPage } from './LearningListPage'

/** 013 T028·T043. quickstart 4-1·4-2, US1 시나리오 5, US4 시나리오 1·3. */

const apiMocks = vi.hoisted(() => ({
  fetchLearningArticles: vi.fn(),
  fetchLearningArticle: vi.fn(),
}))

vi.mock('@/api/learning', () => apiMocks)
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

const failed: LearningListResult = {
  status: 'failed',
  kind: 'api',
  code: 'INTERNAL_ERROR',
  message: '글 목록을 만들지 못했습니다.',
}

const progressWords = /학습 완료|수강|진도|완료율|숙련도|설문/

describe('LearningListPage', () => {
  beforeEach(() => {
    apiMocks.fetchLearningArticles.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('서버가 준 순서 그대로 제목·질문과 글 연결을 보인다', async () => {
    apiMocks.fetchLearningArticles.mockResolvedValue({
      status: 'loaded',
      articles: articleSummaries,
    } satisfies LearningListResult)

    renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })

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

  it('목록을 불러오는 동안에도 진단 시작 연결이 있다', async () => {
    apiMocks.fetchLearningArticles.mockReturnValue(new Promise(() => {}))

    renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })

    expect(await screen.findByRole('status')).not.toBeNull()
    expect(
      screen.getByRole('link', { name: /바로 전략 진단하기/ }).getAttribute('href'),
    ).toBe('/diagnosis/sectors')
  })

  it('게시된 글이 없으면 경고가 아닌 한 문장과 진단 시작 연결을 보인다', async () => {
    apiMocks.fetchLearningArticles.mockResolvedValue({ status: 'empty' })

    renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })

    expect(await screen.findByText('아직 게시된 글이 없습니다.')).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
    expect(
      screen.getByRole('link', { name: /바로 전략 진단하기/ }).getAttribute('href'),
    ).toBe('/diagnosis/sectors')
  })

  it('불러오지 못하면 빈 목록과 다르게 경고와 다시 시도를 보이고 진단 연결은 남긴다', async () => {
    apiMocks.fetchLearningArticles.mockResolvedValue(failed)

    renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })

    expect((await screen.findByRole('alert')).textContent).toBe('글 목록을 만들지 못했습니다.')
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toBeNull()
    expect(screen.queryByText('아직 게시된 글이 없습니다.')).toBeNull()
    expect(
      screen.getByRole('link', { name: /바로 전략 진단하기/ }).getAttribute('href'),
    ).toBe('/diagnosis/sectors')
  })

  it('다시 시도는 누를 때만 한 번 더 부른다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchLearningArticles.mockResolvedValueOnce(failed)
    apiMocks.fetchLearningArticles.mockResolvedValueOnce({ status: 'empty' })

    renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(apiMocks.fetchLearningArticles).toHaveBeenCalledTimes(1)

    await user.click(retry)

    expect(await screen.findByText('아직 게시된 글이 없습니다.')).not.toBeNull()
    expect(apiMocks.fetchLearningArticles).toHaveBeenCalledTimes(2)
  })

  it('연결 실패처럼 서버 문장이 없는 실패는 고정 문장을 쓴다', async () => {
    apiMocks.fetchLearningArticles.mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '서버에 연결할 수 없습니다.',
    } satisfies LearningListResult)

    renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })

    expect((await screen.findByRole('alert')).textContent).toBe('배우기 글을 불러오지 못했습니다.')
  })

  it('목록을 열고 닫는 동안 브라우저 저장소에 쓰지 않는다', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    apiMocks.fetchLearningArticles.mockResolvedValue({
      status: 'loaded',
      articles: articleSummaries,
    } satisfies LearningListResult)

    const { unmount } = renderWithRouter(<LearningListPage />, { initialEntries: ['/learn'] })
    await screen.findByRole('list')
    unmount()

    expect(setItem).not.toHaveBeenCalled()
  })
})

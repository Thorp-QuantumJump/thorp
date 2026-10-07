import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { currentArticle, previousTideArticle, tideArticle } from '@/test/learningFixtures'
import { ArticlePreview } from './ArticlePreview'

/**
 * 013 B14 converge. 미리 보기는 독자 화면과 똑같이 그린다(FR-016, 계약 §8). 판마다 독자 화면
 * (`LearningArticlePage`)과 같은 자리를 보이고, 독자 화면이 그릴 수 없는 고치는 중인 초안의 빈자리는 글로 알린다.
 */

const apiMocks = vi.hoisted(() => ({ fetchArticlePreview: vi.fn() }))
vi.mock('@/api/learningAdmin', () => apiMocks)

/** 미리 보기 응답에는 게시 시각이 없다. 픽스처의 공개 글에서 뺀다. */
function withoutPublishedAt<T extends { publishedAt: string }>(article: T): Omit<T, 'publishedAt'> {
  const rest: Partial<T> = { ...article }
  delete rest.publishedAt
  return rest as Omit<T, 'publishedAt'>
}

async function openPreview() {
  render(
    <MemoryRouter>
      <ArticlePreview articleId="tide-reading" dirty={false} onUnauthenticated={() => undefined} />
    </MemoryRouter>,
  )
  await userEvent.setup().click(screen.getByRole('button', { name: '미리 보기 열기' }))
}

beforeEach(() => {
  apiMocks.fetchArticlePreview.mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ArticlePreview', () => {
  it('첫 단계 공개 전 글은 독자 화면처럼 예시의 섹터·비중과 투자 권고가 아니라는 문장, 링크 있는 출처 목록을 보인다', async () => {
    apiMocks.fetchArticlePreview.mockResolvedValue({
      status: 'loaded',
      preview: { article: withoutPublishedAt(tideArticle), incompleteQuestions: [] },
    })
    await openPreview()

    const preview = await screen.findByRole('article', { name: '물때 읽기 미리 보기' })
    const example = within(preview).getByRole('region', { name: '예시' })
    expect(within(example).getByText('알파 부두')).not.toBeNull()
    expect(within(example).getByText('62.5%')).not.toBeNull()
    expect(within(example).getByText('설명을 위한 예시이며 투자 권고가 아닙니다.')).not.toBeNull()

    const sources = within(preview).getByRole('region', { name: '출처' })
    expect(within(sources).getByRole('link', { name: '합성 출처 하나' }).getAttribute('href')).toBe(
      'https://example.com/tide',
    )
    expect(within(sources).getByText('주소 없는 합성 출처')).not.toBeNull()
  })

  it('이전 판 글은 머리에 이전 판 표시를 두고 9월 판 출처 목록은 두지 않는다', async () => {
    apiMocks.fetchArticlePreview.mockResolvedValue({
      status: 'loaded',
      preview: { article: withoutPublishedAt(previousTideArticle), incompleteQuestions: [] },
    })
    await openPreview()

    const preview = await screen.findByRole('article', { name: '물때 읽기 미리 보기' })
    expect(within(preview).getByText('이전 판')).not.toBeNull()
    expect(within(preview).queryByRole('region', { name: '출처' })).toBeNull()
  })

  it('예시 없는 V1 초안도 그리고, 정답 보기가 하나가 아닌 문제는 미리 보기에서 뺐다고 알린다', async () => {
    apiMocks.fetchArticlePreview.mockResolvedValue({
      status: 'loaded',
      preview: {
        article: { ...withoutPublishedAt(tideArticle), example: null },
        incompleteQuestions: [{ questionId: 'q3', prompt: '정답을 아직 고르지 않은 문제' }],
      },
    })
    await openPreview()

    const preview = await screen.findByRole('article', { name: '물때 읽기 미리 보기' })
    expect(within(preview).queryByRole('region', { name: '예시' })).toBeNull()
    const note = within(preview).getByRole('note')
    expect(note.textContent).toContain('정답 보기가 하나가 아닌 문제 1개')
    expect(note.textContent).toContain('q3: 정답을 아직 고르지 않은 문제')
  })

  it('새 기준 글은 확인 문제를 기록 없이 풀 수 있게 보인다', async () => {
    apiMocks.fetchArticlePreview.mockResolvedValue({
      status: 'loaded',
      preview: { article: withoutPublishedAt(currentArticle), incompleteQuestions: [] },
    })
    await openPreview()

    const preview = await screen.findByRole('article', {
      name: `${currentArticle.title} 미리 보기`,
    })
    expect(within(preview).getByText(currentArticle.quiz[0].prompt)).not.toBeNull()
    expect(within(preview).getByText(/새 기준 글로 보입니다/)).not.toBeNull()
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adminDraftKey } from '@/components/learning/admin/adminDraftStorage'
import {
  adminDraftArticle,
  adminStages,
  adminTideArticle,
  adminV2Article,
  publishCheckFailures,
} from '@/test/learningFixtures'
import type { AdminLearningArticle, AdminLearningDraftV2 } from '@/types/learningAdmin'
import { AdminLearningEditPage } from './AdminLearningEditPage'

/** 013 T071·T174. quickstart 3-2·3-6·3-7·3-8·7-1·7-4·7-5, research R12·R26. */

const apiMocks = vi.hoisted(() => ({
  checkAdminAccess: vi.fn(),
  fetchAdminArticle: vi.fn(),
  fetchAdminStages: vi.fn(),
  fetchArticlePreview: vi.fn(),
  saveDraft: vi.fn(),
  publishArticle: vi.fn(),
  unpublishArticle: vi.fn(),
}))

vi.mock('@/api/learningAdmin', () => apiMocks)

// 예시 편집기는 열린 대응표를 따로 부른다. 이 화면의 판정과 섞이지 않게 막아 둔다.
vi.mock('@/api/sectors', () => ({
  fetchSectorCatalog: vi.fn().mockResolvedValue({ status: 'mapNotReady', message: '준비 중' }),
}))

function renderEdit(articleId = 'tide-reading') {
  return render(
    <MemoryRouter initialEntries={[`/admin/learning/${articleId}`]}>
      <Routes>
        <Route path="/admin/learning/:articleId" element={<AdminLearningEditPage />} />
        <Route path="/admin/learning" element={<p>관리 목록</p>} />
        <Route path="/learn" element={<p>배우기 목록</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminLearningEditPage', () => {
  beforeEach(() => {
    apiMocks.checkAdminAccess.mockReset().mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticle.mockReset().mockResolvedValue({
      status: 'loaded',
      article: adminTideArticle,
    })
    apiMocks.fetchAdminStages.mockReset().mockResolvedValue({
      status: 'loaded',
      stages: adminStages,
    })
    apiMocks.fetchArticlePreview.mockReset()
    apiMocks.saveDraft.mockReset()
    apiMocks.publishArticle.mockReset()
    apiMocks.unpublishArticle.mockReset()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('draftChanged 뒤에도 입력이 남고 안내와 새로 불러오기가 있으며 자동으로 다시 보내지 않는다', async () => {
    apiMocks.saveDraft.mockResolvedValue({
      status: 'draftChanged',
      message: '초안이 바뀌었습니다.',
      currentDraftVersion: 9,
    })
    const user = userEvent.setup()
    renderEdit()

    const title = await screen.findByLabelText('제목')
    await user.clear(title)
    await user.type(title, '고친 제목')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(
      await screen.findByText('다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요.'),
    ).not.toBeNull()
    expect(screen.getByRole('button', { name: '새로 불러오기' })).not.toBeNull()
    // 입력이 그대로 남아 있다. 화면이 서버 값으로 되돌리지 않는다.
    expect(screen.getByLabelText('제목')).toHaveProperty('value', '고친 제목')
    // 두 번째 저장 요청이 없다. 새 번호로 자동 재전송하지 않는다(R12).
    expect(apiMocks.saveDraft).toHaveBeenCalledTimes(1)
  })

  it('checkFailed 응답의 항목 셋이 보인다', async () => {
    apiMocks.publishArticle.mockResolvedValue({
      status: 'checkFailed',
      message: '게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다.',
      checkVersion: 'learning-publish-checks-v1',
      failures: publishCheckFailures,
    })
    const user = userEvent.setup()
    renderEdit()

    await user.click(await screen.findByRole('button', { name: '게시' }))

    const alert = await screen.findByRole('alert')
    expect(within(alert).getAllByRole('listitem')).toHaveLength(3)
    for (const failure of publishCheckFailures) {
      expect(within(alert).getByText(failure.message)).not.toBeNull()
    }
  })

  it('unauthenticated 뒤에도 보관본이 남고 재로그인 안내가 있다', async () => {
    apiMocks.saveDraft.mockResolvedValue({ status: 'unauthenticated' })
    const user = userEvent.setup()
    renderEdit()

    const title = await screen.findByLabelText('제목')
    await user.clear(title)
    await user.type(title, '만료 직전 제목')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('heading', { name: '다시 로그인해 주세요' })).not.toBeNull()
    const kept = window.sessionStorage.getItem(adminDraftKey('tide-reading'))
    expect(kept).not.toBeNull()
    expect(kept).toContain('만료 직전 제목')
  })

  it('보관본이 있으면 복원할지 묻고 수락하면 폼에 채워진다', async () => {
    window.sessionStorage.setItem(
      adminDraftKey('tide-reading'),
      JSON.stringify({
        version: 2,
        savedAt: '2026-09-21T02:00:00Z',
        draftVersion: 5,
        draft: { ...adminTideArticle.draft, title: '보관한 제목' },
      }),
    )
    const user = userEvent.setup()
    renderEdit()

    expect(
      await screen.findByRole('heading', { name: '저장하지 못한 편집 내용이 있습니다' }),
    ).not.toBeNull()
    // 묻기 전에는 서버 값이 그대로다. 말없이 덮지 않는다.
    expect(screen.getByLabelText('제목')).toHaveProperty('value', adminTideArticle.draft.title)

    await user.click(screen.getByRole('button', { name: '복원하기' }))
    expect(screen.getByLabelText('제목')).toHaveProperty('value', '보관한 제목')
  })

  it('복원 여부에 답하기 전에는 편집기와 저장·게시가 막힌다', async () => {
    window.sessionStorage.setItem(
      adminDraftKey('tide-reading'),
      JSON.stringify({
        version: 2,
        savedAt: '2026-09-21T02:00:00Z',
        draftVersion: 5,
        draft: { ...adminTideArticle.draft, title: '보관한 제목' },
      }),
    )
    const user = userEvent.setup()
    renderEdit()

    await screen.findByRole('heading', { name: '저장하지 못한 편집 내용이 있습니다' })

    // 입력 요소 하나하나가 아니라 감싼 fieldset이 막는다. 그래서 막혔는지는 동작으로 본다.
    const title = screen.getByLabelText('제목')
    expect(title.closest('fieldset')?.disabled).toBe(true)
    await user.type(title, ' 새 입력')
    expect(title).toHaveProperty('value', adminTideArticle.draft.title)
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(apiMocks.saveDraft).not.toHaveBeenCalled()
    // 먼저 친 글자가 보관본을 덮지 않았다.
    expect(window.sessionStorage.getItem(adminDraftKey('tide-reading'))).toContain('보관한 제목')

    // 버리면 서버 값으로 편집을 시작한다. 보관본도 함께 지운다.
    await user.click(screen.getByRole('button', { name: '버리기' }))
    expect(title.closest('fieldset')?.disabled).toBe(false)
    expect(window.sessionStorage.getItem(adminDraftKey('tide-reading'))).toBeNull()
  })

  it('보관 뒤 서버 초안이 바뀌었으면 알리고, 복원해 저장하면 보관본의 번호를 싣는다', async () => {
    // 보관본은 5번에 기반하고, 그 사이 다른 곳에서 저장해 서버는 6번이다.
    apiMocks.fetchAdminArticle.mockResolvedValue({
      status: 'loaded',
      article: { ...adminTideArticle, draftVersion: 6 },
    })
    window.sessionStorage.setItem(
      adminDraftKey('tide-reading'),
      JSON.stringify({
        version: 2,
        savedAt: '2026-09-21T02:00:00Z',
        draftVersion: 5,
        draft: { ...adminTideArticle.draft, title: '보관한 제목' },
      }),
    )
    apiMocks.saveDraft.mockResolvedValue({
      status: 'draftChanged',
      message: '초안이 바뀌었습니다.',
      currentDraftVersion: 6,
    })
    const user = userEvent.setup()
    renderEdit()

    expect(await screen.findByText(/보관한 뒤 다른 곳에서 초안이 바뀌었습니다/)).not.toBeNull()

    await user.click(screen.getByRole('button', { name: '복원하기' }))
    await user.click(screen.getByRole('button', { name: '저장' }))

    // 서버의 지금 번호(6)로 보내면 다른 곳의 저장을 409 없이 덮는다(R12).
    expect(apiMocks.saveDraft).toHaveBeenCalledTimes(1)
    expect(apiMocks.saveDraft.mock.calls[0][1]).toMatchObject({
      expectedDraftVersion: 5,
      title: '보관한 제목',
    })
    expect(
      await screen.findByText('다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요.'),
    ).not.toBeNull()
  })

  it('저장하지 않은 변경이 있으면 게시가 막히고 저장 성공 뒤 다시 열린다', async () => {
    apiMocks.saveDraft.mockImplementation(async (_articleId: string, request: object) => ({
      status: 'saved',
      article: {
        ...adminTideArticle,
        draftVersion: 6,
        draft: { ...adminTideArticle.draft, ...request },
      },
    }))
    const user = userEvent.setup()
    renderEdit()

    const publish = await screen.findByRole('button', { name: '게시' })
    expect(publish).toHaveProperty('disabled', false)

    await user.type(screen.getByLabelText('제목'), ' 고침')
    expect(screen.getByRole('button', { name: '게시' })).toHaveProperty('disabled', true)
    expect(screen.getByText('저장한 뒤 게시할 수 있습니다.')).not.toBeNull()

    await user.click(screen.getByRole('button', { name: '저장' }))
    await screen.findByText('초안을 저장했습니다.')
    expect(screen.getByRole('button', { name: '게시' })).toHaveProperty('disabled', false)
    expect(apiMocks.publishArticle).not.toHaveBeenCalled()
  })

  it('저장에 성공하면 보관본을 지운다', async () => {
    apiMocks.saveDraft.mockResolvedValue({
      status: 'saved',
      article: { ...adminTideArticle, draftVersion: 6 },
    })
    const user = userEvent.setup()
    renderEdit()

    const title = await screen.findByLabelText('제목')
    await user.type(title, ' 더')
    expect(window.sessionStorage.getItem(adminDraftKey('tide-reading'))).not.toBeNull()

    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('초안을 저장했습니다.')).not.toBeNull()
    expect(window.sessionStorage.getItem(adminDraftKey('tide-reading'))).toBeNull()
  })

  it('DRAFT 글에는 게시 취소 버튼이 없다', async () => {
    apiMocks.fetchAdminArticle.mockResolvedValue({
      status: 'loaded',
      article: adminDraftArticle,
    })
    renderEdit('moor-lines')

    await screen.findByLabelText('제목')
    expect(screen.queryByRole('button', { name: '게시 취소' })).toBeNull()
    expect(screen.getByRole('button', { name: '게시' })).not.toBeNull()
  })

  it('PUBLISHED 글은 확인을 받은 뒤에만 게시를 취소한다', async () => {
    apiMocks.unpublishArticle.mockResolvedValue({
      status: 'unpublished',
      result: { articleId: 'tide-reading', status: 'DRAFT', unpublishedRevisionNo: 3 },
    })
    // 처음 물음은 거절하고, 다시 눌렀을 때는 수락한다.
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true)
    const user = userEvent.setup()
    renderEdit()

    await user.click(await screen.findByRole('button', { name: '게시 취소' }))
    // 거절하면 요청을 보내지 않고 버튼도 그대로 둔다.
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(apiMocks.unpublishArticle).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '게시 취소' }))

    expect(await screen.findByText('3판의 게시를 취소했습니다.')).not.toBeNull()
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(apiMocks.unpublishArticle).toHaveBeenCalledTimes(1)
    // 취소한 뒤에는 그 버튼이 사라진다. 상태가 DRAFT로 바뀌었다.
    expect(screen.queryByRole('button', { name: '게시 취소' })).toBeNull()
  })

  it('(2판) 버전 1 보관본은 복원하지 않고 버렸다고 알린다', async () => {
    window.sessionStorage.setItem(
      adminDraftKey('tide-reading'),
      JSON.stringify({
        version: 1,
        savedAt: '2026-09-21T02:00:00Z',
        draftVersion: 5,
        draft: { title: '옛 보관 제목' },
      }),
    )
    renderEdit()

    expect(await screen.findByText(/복원하지 않고 버렸습니다/)).not.toBeNull()
    expect(screen.queryByRole('heading', { name: '저장하지 못한 편집 내용이 있습니다' })).toBeNull()
    expect(screen.getByLabelText('제목')).toHaveProperty('value', adminTideArticle.draft.title)
    expect(window.sessionStorage.getItem(adminDraftKey('tide-reading'))).toBeNull()
  })

  it('(2판) 편집 문법이 틀린 칸이 있으면 저장 요청을 보내지 않고 위치를 보인다', async () => {
    apiMocks.fetchAdminArticle.mockResolvedValue({ status: 'loaded', article: adminV2Article })
    const user = userEvent.setup()
    renderEdit('channel-depth')

    const paragraph = await screen.findByLabelText(/^2번째 블록 내용/)
    // userEvent는 `{`를 특수 키로 읽으므로 `{{`로 한 글자를 친다.
    await user.type(paragraph, ' {{{{용어:닫지 않음')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(
      await screen.findByText(
        '편집 문법이 틀린 칸이 있어 저장하지 않았습니다. 아래 위치를 고쳐 주세요.',
      ),
    ).not.toBeNull()
    expect(screen.getByText(/본문 2번째 블록\(문단\) · 내용 1줄 \d+칸/)).not.toBeNull()
    expect(apiMocks.saveDraft).not.toHaveBeenCalled()
  })

  it('(2판) 저장 요청은 편집 문법을 서버 초안과 같은 조각으로 되돌려 보낸다', async () => {
    apiMocks.fetchAdminArticle.mockResolvedValue({ status: 'loaded', article: adminV2Article })
    apiMocks.saveDraft.mockResolvedValue({ status: 'saved', article: adminV2Article })
    const user = userEvent.setup()
    renderEdit('channel-depth')

    await user.type(await screen.findByLabelText('제목'), ' 고침')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(apiMocks.saveDraft).toHaveBeenCalledWith('channel-depth', {
      ...adminV2Article.draft,
      title: `${adminV2Article.draft.title} 고침`,
      expectedDraftVersion: 4,
    })
  })

  it('(2판) 게시 성공 뒤 초안을 다시 읽고, 바로 한 저장이 같은 번호와 새 식별자, 비운 transferFrom을 싣는다', async () => {
    const v2Draft = adminV2Article.draft as AdminLearningDraftV2
    const afterPublish: AdminLearningArticle = {
      ...adminV2Article,
      publishedRevisionNo: 3,
      draft: {
        ...v2Draft,
        terms: v2Draft.terms.map((term) => ({ ...term, transferFrom: null })),
        quiz: v2Draft.quiz.map((question) =>
          question.questionId === 'q4' ? { ...question, questionId: 'q7' } : question,
        ),
      },
    }
    apiMocks.fetchAdminArticle
      .mockResolvedValueOnce({ status: 'loaded', article: adminV2Article })
      .mockResolvedValueOnce({ status: 'loaded', article: afterPublish })
    apiMocks.publishArticle.mockResolvedValue({
      status: 'published',
      result: {
        articleId: 'channel-depth',
        status: 'PUBLISHED',
        revisionNo: 3,
        checkVersion: 'learning-publish-checks-v2',
        sectorMapVersion: 'NOT_CHECKED',
        publishedAt: '2026-11-20T01:00:00Z',
        edition: 'CURRENT',
        renamedQuestions: [{ from: 'q4', to: 'q7' }],
        transferredTerms: [
          { termId: 'keel-room', fromArticleId: 'harbor-notes', toArticleId: 'channel-depth' },
        ],
      },
    })
    apiMocks.saveDraft.mockResolvedValue({ status: 'saved', article: afterPublish })
    const user = userEvent.setup()
    renderEdit('channel-depth')

    await user.click(await screen.findByRole('button', { name: '게시' }))

    expect(
      await screen.findByText(/이미 답한 사용자에게도 새 문제로 보입니다: q4 → q7/),
    ).not.toBeNull()
    expect(apiMocks.fetchAdminArticle).toHaveBeenCalledTimes(2)
    expect(await screen.findByText('2번째 문제 (q7)')).not.toBeNull()

    await user.type(screen.getByLabelText(/^1번째 문제 질문/), ' 고침')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(apiMocks.saveDraft).toHaveBeenCalledTimes(1)
    const request = apiMocks.saveDraft.mock.calls[0][1]
    expect(request.expectedDraftVersion).toBe(4)
    expect(request.quiz.map((question: { questionId: string }) => question.questionId)).toEqual([
      'q1',
      'q7',
    ])
    expect(request.quiz[0].prompt).toBe('합성 질문 하나 고침')
    expect(request.terms[0].transferFrom).toBeNull()
  })

  it('(2판) 게시 뒤 초안을 다시 읽지 못하면 게시 안내를 남기고 새로 불러오기를 보인다', async () => {
    apiMocks.fetchAdminArticle
      .mockResolvedValueOnce({ status: 'loaded', article: adminV2Article })
      .mockResolvedValueOnce({
        status: 'failed',
        kind: 'network',
        code: null,
        message: '연결하지 못했습니다.',
      })
    apiMocks.publishArticle.mockResolvedValue({
      status: 'published',
      result: {
        articleId: 'channel-depth',
        status: 'PUBLISHED',
        revisionNo: 3,
        checkVersion: 'learning-publish-checks-v2',
        sectorMapVersion: 'NOT_CHECKED',
        publishedAt: '2026-11-20T01:00:00Z',
        edition: 'CURRENT',
        renamedQuestions: [],
        transferredTerms: [],
      },
    })
    const user = userEvent.setup()
    renderEdit('channel-depth')

    await user.click(await screen.findByRole('button', { name: '게시' }))

    expect(await screen.findByText(/게시한 뒤 초안을 다시 읽지 못했습니다/)).not.toBeNull()
    expect(screen.getByText(/3판\(CURRENT\)으로 게시했습니다/)).not.toBeNull()
    expect(screen.getByRole('button', { name: '새로 불러오기' })).not.toBeNull()
  })

  it('(2판) 다른 글이 쓰는 용어면 이 글로 넘겨받기를 제안하고, 고르면 다음 저장에 transferFrom이 실린다', async () => {
    const v2Draft = adminV2Article.draft as AdminLearningDraftV2
    const withoutTransfer: AdminLearningArticle = {
      ...adminV2Article,
      draft: { ...v2Draft, terms: v2Draft.terms.map((term) => ({ ...term, transferFrom: null })) },
    }
    apiMocks.fetchAdminArticle.mockResolvedValue({ status: 'loaded', article: withoutTransfer })
    apiMocks.saveDraft.mockResolvedValueOnce({
      status: 'termIdTaken',
      message: '다른 글이 쓰는 용어입니다.',
      termId: 'keel-room',
      ownerArticleId: 'harbor-notes',
    })
    const user = userEvent.setup()
    renderEdit('channel-depth')

    await user.click(await screen.findByRole('button', { name: '저장' }))
    await user.click(await screen.findByRole('button', { name: '이 글로 넘겨받기' }))

    expect(screen.getByText(/넘겨받는 중:/)).not.toBeNull()
    apiMocks.saveDraft.mockResolvedValueOnce({ status: 'saved', article: adminV2Article })
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(apiMocks.saveDraft).toHaveBeenCalledTimes(2)
    expect(apiMocks.saveDraft.mock.calls[1][1].terms[0].transferFrom).toBe('harbor-notes')
  })

  it('(2판) 공개 전 단계의 V2 게시 거절은 단계 공개로 안내한다', async () => {
    apiMocks.fetchAdminArticle.mockResolvedValue({ status: 'loaded', article: adminDraftArticle })
    apiMocks.publishArticle.mockResolvedValue({
      status: 'stageNotReleased',
      message: '공개 전 단계의 글은 단계 공개로만 게시합니다.',
    })
    const user = userEvent.setup()
    renderEdit('moor-lines')

    await user.click(await screen.findByRole('button', { name: '게시' }))

    expect(await screen.findByText('공개 전 단계의 글은 단계 공개로만 게시합니다.')).not.toBeNull()
    expect(screen.getByText(/관리 목록의 .단계 공개.로 함께 게시됩니다/)).not.toBeNull()
  })

  it('(2판) 미리 보기는 저장된 초안을 독자 화면 부품으로 그리고 확인 문제를 기록 없이 보인다', async () => {
    apiMocks.fetchAdminArticle.mockResolvedValue({ status: 'loaded', article: adminV2Article })
    apiMocks.fetchArticlePreview.mockResolvedValue({
      status: 'loaded',
      article: {
        articleId: 'channel-depth',
        edition: 'CURRENT',
        title: '미리 보는 제목',
        question: '미리 보는 질문',
        readingOrder: 102,
        stage: { stageKey: 'shore-basics', number: 1, name: '물가 기초' },
        number: null,
        readingMinutes: 5,
        minDwellSeconds: 150,
        blocks: [{ type: 'heading', text: '미리 보는 소제목' }],
        quiz: [
          {
            questionId: 'q1',
            prompt: '미리 보는 문제',
            choices: [
              { choiceId: 'c2', text: '가' },
              { choiceId: 'c3', text: '나' },
            ],
            correctChoiceId: 'c3',
            explanation: '미리 보는 풀이',
          },
        ],
        example: null,
        sources: [],
        next: null,
        stageArticleCount: null,
        hasUnreleasedStages: true,
      },
    })
    const user = userEvent.setup()
    renderEdit('channel-depth')

    await user.click(await screen.findByRole('button', { name: '미리 보기 열기' }))

    const preview = await screen.findByRole('article', { name: '미리 보는 제목 미리 보기' })
    expect(within(preview).getByRole('heading', { name: '미리 보는 소제목' })).not.toBeNull()
    expect(within(preview).getByText('미리 보는 문제')).not.toBeNull()
    expect(within(preview).getByText(/새 기준 글로 보입니다/)).not.toBeNull()
    expect(apiMocks.fetchArticlePreview).toHaveBeenCalledWith('channel-depth', expect.anything())
  })
})

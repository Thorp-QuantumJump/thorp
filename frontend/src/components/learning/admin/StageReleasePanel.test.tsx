import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adminReadyReadiness, adminStages, adminV2Article } from '@/test/learningFixtures'
import { adminDraftKey, loadUnsentDraft, saveUnsentDraft } from './adminDraftStorage'
import { toEditableDraft } from './editableDraft'
import { StageReleasePanel } from './StageReleasePanel'

/**
 * 013 T174. 단계 공개(FR-031, 계약 §10·§11, research R23, quickstart 8-2·8-3).
 *
 * - 미리 검사를 통과하기 전과 확인 창에서 취소했을 때는 공개 요청이 없다
 * - 걸린 항목은 서버 문장 그대로 글마다 보인다
 * - 공개에 성공하면 게시된 글의 탭 보관본이 게시 결과대로 고쳐지고 초안 번호는 그대로다
 */

const apiMocks = vi.hoisted(() => ({ fetchStageReadiness: vi.fn(), releaseStage: vi.fn() }))

vi.mock('@/api/learningAdmin', () => apiMocks)

function renderPanel() {
  const onReleased = vi.fn()
  const onUnauthenticated = vi.fn()
  render(
    <StageReleasePanel
      stages={adminStages}
      onReleased={onReleased}
      onUnauthenticated={onUnauthenticated}
    />,
  )
  return { onReleased, onUnauthenticated }
}

beforeEach(() => {
  apiMocks.fetchStageReadiness
    .mockReset()
    .mockResolvedValue({ status: 'loaded', readiness: adminReadyReadiness })
  apiMocks.releaseStage.mockReset()
  window.sessionStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('StageReleasePanel', () => {
  it('미리 검사를 통과하기 전에는 공개할 수 없고, 확인 창에서 취소하면 요청하지 않는다', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPanel()
    const user = userEvent.setup()

    const release = screen.getByRole('button', { name: '1단계 공개' })
    expect(release).toHaveProperty('disabled', true)

    await user.click(screen.getByRole('button', { name: '1단계 미리 검사' }))
    expect(await screen.findByText(/미리 검사를 모두 통과했습니다\. 글 2편/)).not.toBeNull()
    expect(release).toHaveProperty('disabled', false)

    await user.click(release)

    expect(confirm).toHaveBeenCalledTimes(1)
    // 확인 창에 공개될 글 목록과 되돌릴 수 없다는 사실이 있다.
    expect(confirm.mock.calls[0][0]).toContain('물때 읽기')
    expect(confirm.mock.calls[0][0]).toContain('물길 깊이 재기')
    expect(confirm.mock.calls[0][0]).toContain('되돌릴 수 없습니다')
    expect(apiMocks.releaseStage).not.toHaveBeenCalled()
  })

  it('미리 검사에 걸린 글과 막는 것을 서버 문장 그대로 보이고 공개를 열지 않는다', async () => {
    apiMocks.fetchStageReadiness.mockResolvedValue({
      status: 'loaded',
      readiness: {
        ...adminReadyReadiness,
        ready: false,
        blockers: ['ORDER'],
        articles: [
          {
            articleId: 'channel-depth',
            draftVersion: 4,
            failures: [
              {
                check: 'KEY_SUMMARY',
                location: { part: 'block', index: 0, field: 'lines[1]' },
                message: '글 맨 위에 세 줄 핵심 요약이 있어야 합니다.',
              },
            ],
          },
        ],
      },
    })
    renderPanel()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1단계 미리 검사' }))

    expect(await screen.findByText('앞 번호 단계가 아직 공개되지 않았습니다.')).not.toBeNull()
    const list = screen.getByRole('region', { name: '물길 깊이 재기 (channel-depth)' })
    expect(within(list).getByText('글 맨 위에 세 줄 핵심 요약이 있어야 합니다.')).not.toBeNull()
    expect(within(list).getByText('본문 1번째 블록 · 2번째 줄')).not.toBeNull()
    expect(screen.getByRole('button', { name: '1단계 공개' })).toHaveProperty('disabled', true)
  })

  it('공개 422는 글마다 걸린 항목을 그대로 보이고 아무 글도 게시하지 않았다고 알린다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    apiMocks.releaseStage.mockResolvedValue({
      status: 'checkFailed',
      message: '검사에 걸린 글이 있어 단계를 공개하지 않았습니다.',
      checkVersion: 'learning-publish-checks-v2',
      articles: [
        {
          articleId: 'tide-reading',
          failures: [
            {
              check: 'TERM_TRANSFER',
              location: { part: 'term', termId: 'keel-room' },
              message: '넘겨받기 소유 글이 다릅니다.',
            },
          ],
        },
      ],
    })
    const { onReleased } = renderPanel()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1단계 미리 검사' }))
    await screen.findByText(/미리 검사를 모두 통과했습니다/)
    await user.click(screen.getByRole('button', { name: '1단계 공개' }))

    expect(
      await screen.findByText(/검사에 걸린 글이 있어 단계를 공개하지 않았습니다\. 아무 글도/),
    ).not.toBeNull()
    expect(screen.getByText('넘겨받기 소유 글이 다릅니다.')).not.toBeNull()
    expect(apiMocks.releaseStage).toHaveBeenCalledWith('shore-basics', adminReadyReadiness.articles)
    expect(onReleased).not.toHaveBeenCalled()
    // 낡은 미리 검사로 다시 공개하지 않게 공개 단추를 닫는다.
    expect(screen.getByRole('button', { name: '1단계 공개' })).toHaveProperty('disabled', true)
  })

  it('공개에 성공하면 그 글 보관본의 문제 식별자와 transferFrom을 고치고 초안 번호는 그대로 둔다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const editable = toEditableDraft(adminV2Article.draft)
    saveUnsentDraft('channel-depth', { ...editable, title: '보관한 제목' }, 4)
    apiMocks.releaseStage.mockResolvedValue({
      status: 'released',
      result: {
        stageKey: 'shore-basics',
        releasedAt: '2026-11-19T01:00:00Z',
        published: [
          { articleId: 'tide-reading', revisionNo: 4, renamedQuestions: [] },
          {
            articleId: 'channel-depth',
            revisionNo: 3,
            renamedQuestions: [{ from: 'q4', to: 'q7' }],
          },
        ],
        transferredTerms: [
          { termId: 'keel-room', fromArticleId: 'harbor-notes', toArticleId: 'channel-depth' },
        ],
        logEntryKey: 'stage:shore-basics',
        checkVersion: 'learning-publish-checks-v2',
      },
    })
    const { onReleased } = renderPanel()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1단계 미리 검사' }))
    await screen.findByText(/미리 검사를 모두 통과했습니다/)
    await user.click(screen.getByRole('button', { name: '1단계 공개' }))

    expect(await screen.findByText(/1단계를 공개했습니다/)).not.toBeNull()
    expect(screen.getByText(/q4 → q7/)).not.toBeNull()
    expect(onReleased).toHaveBeenCalledTimes(1)

    const lookup = loadUnsentDraft('channel-depth')
    expect(lookup.status).toBe('found')
    if (lookup.status !== 'found') return
    expect(lookup.unsent.draftVersion).toBe(4)
    expect(lookup.unsent.draft.title).toBe('보관한 제목')
    expect(lookup.unsent.draft.quiz.map((question) => question.questionId)).toEqual(['q1', 'q7'])
    expect(lookup.unsent.draft.format).toBe('V2')
    if (lookup.unsent.draft.format !== 'V2') return
    expect(lookup.unsent.draft.terms[0].transferFrom).toBeNull()
    // 보관본이 없던 글에는 새로 만들지 않는다.
    expect(window.sessionStorage.getItem(adminDraftKey('tide-reading'))).toBeNull()
  })

  it('(converge) 409 STAGE_CHANGED는 서버가 읽은 공개 집합을 보이고 다시 보내지 않는다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    apiMocks.releaseStage.mockResolvedValue({
      status: 'changed',
      message: '단계의 글이 바뀌었습니다.',
      articles: [
        { articleId: 'tide-reading', draftVersion: 6 },
        { articleId: 'channel-depth', draftVersion: 4 },
      ],
    })
    renderPanel()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1단계 미리 검사' }))
    await screen.findByText(/미리 검사를 모두 통과했습니다/)
    await user.click(screen.getByRole('button', { name: '1단계 공개' }))

    const list = await screen.findByRole('list', { name: '서버가 읽은 공개 글' })
    expect(within(list).getByText('물때 읽기 (tide-reading) · 초안 6번')).not.toBeNull()
    expect(apiMocks.releaseStage).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '1단계 공개' })).toHaveProperty('disabled', true)
  })

  it('공개된 단계와 공개 배치만 남은 글을 구분해 보인다', () => {
    renderPanel()

    expect(screen.queryByRole('button', { name: '2단계 공개' })).not.toBeNull()
    expect(screen.getByText(/공개 배치만 이 단계인 글입니다/)).not.toBeNull()
    expect(screen.getByText(/항구 기록 \(harbor-notes\)/)).not.toBeNull()
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adminArticleSummaries, adminStages } from '@/test/learningFixtures'
import { AdminLearningListPage } from './AdminLearningListPage'

/** 013 T072·T173. US5 시나리오 1, 계약 §1·§2·§3·§9. */

const apiMocks = vi.hoisted(() => ({
  checkAdminAccess: vi.fn(),
  fetchAdminArticles: vi.fn(),
  fetchAdminStages: vi.fn(),
  fetchStageReadiness: vi.fn(),
  releaseStage: vi.fn(),
  createArticle: vi.fn(),
}))

vi.mock('@/api/learningAdmin', () => apiMocks)

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/admin/learning']}>
      <Routes>
        <Route path="/admin/learning" element={<AdminLearningListPage />} />
        <Route path="/admin/learning/:articleId" element={<p>편집 화면</p>} />
        <Route path="/learn" element={<p>배우기 목록</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminLearningListPage', () => {
  beforeEach(() => {
    apiMocks.checkAdminAccess.mockReset()
    apiMocks.fetchAdminArticles.mockReset()
    apiMocks.fetchAdminStages.mockReset().mockResolvedValue({
      status: 'loaded',
      stages: adminStages,
    })
    apiMocks.fetchStageReadiness.mockReset()
    apiMocks.releaseStage.mockReset()
    apiMocks.createArticle.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('권한이 없으면 목록·편집 링크·만들기 폼이 없고 배우기 연결만 있다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'denied' })
    renderList()

    expect(await screen.findByRole('heading', { name: '볼 수 없는 화면입니다' })).not.toBeNull()
    expect(screen.getByRole('link', { name: '배우기로 가기' })).not.toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByRole('button', { name: '만들기' })).toBeNull()
    expect(screen.queryAllByRole('link', { name: '편집' })).toHaveLength(0)
    // 목록을 아예 부르지 않는다. 권한 확인이 200일 때만 그린다.
    expect(apiMocks.fetchAdminArticles).not.toHaveBeenCalled()
    expect(apiMocks.fetchAdminStages).not.toHaveBeenCalled()
  })

  it('관리자면 픽스처의 행과 두 표시를 그대로 보인다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({
      status: 'loaded',
      articles: adminArticleSummaries,
    })
    renderList()

    const table = await screen.findByRole('table')
    // 머리글 한 줄 + 픽스처 셋이다.
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(adminArticleSummaries.length + 1)

    // 표시는 행 단위로 본다. 표 머리글에 같은 낱말이 있어 표 전체에서 찾으면 둘이 겹친다.
    expect(within(rows[1]).getByText('게시하지 않은 변경 있음')).not.toBeNull()
    expect(within(rows[3]).getByText('갱신 필요')).not.toBeNull()
    expect(within(table).getAllByRole('link', { name: '편집' })).toHaveLength(
      adminArticleSummaries.length,
    )
  })

  it('needsUpdate가 null이면 확인할 수 없다고 보인다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({
      status: 'loaded',
      articles: adminArticleSummaries,
    })
    renderList()

    expect(await screen.findByText('대응표를 확인할 수 없음')).not.toBeNull()
  })

  it('(2판) 형식·단계·판·확인 필요·넘겨받는 중 열을 행마다 보이고 null을 없음으로 접지 않는다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({
      status: 'loaded',
      articles: adminArticleSummaries,
    })
    renderList()

    const rows = within(await screen.findByRole('table')).getAllByRole('row')
    expect(within(rows[1]).getByText('V1')).not.toBeNull()
    expect(within(rows[1]).getByText('1단계 2번째')).not.toBeNull()
    expect(within(rows[1]).getByText('LEGACY')).not.toBeNull()
    expect(within(rows[1]).getByText('해당 없음')).not.toBeNull()
    expect(within(rows[2]).getByText('확인 필요')).not.toBeNull()
    expect(within(rows[3]).getByText('V2')).not.toBeNull()
    expect(within(rows[3]).getByText('2단계 1번째')).not.toBeNull()
    expect(within(rows[3]).getByText('용어 1개')).not.toBeNull()
  })

  it('(2판) 단계 공개 구역을 단계마다 그린다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({ status: 'loaded', articles: [] })
    renderList()

    expect(await screen.findByRole('heading', { name: '단계 공개' })).not.toBeNull()
    expect(screen.getByRole('button', { name: '1단계 미리 검사' })).not.toBeNull()
    expect(screen.getByRole('button', { name: '2단계 미리 검사' })).not.toBeNull()
  })

  it('만들기가 201이면 그 글의 편집 화면으로 바로 간다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({ status: 'loaded', articles: [] })
    apiMocks.createArticle.mockResolvedValue({
      status: 'created',
      article: { articleId: 'moor-lines' },
    })
    const user = userEvent.setup()
    renderList()

    await user.type(await screen.findByLabelText('식별자'), 'moor-lines')
    await user.type(screen.getByLabelText('제목'), '계류줄 묶기')
    await user.selectOptions(screen.getByLabelText('단계'), 'open-water')
    await user.type(screen.getByLabelText('단계 안 순서'), '9')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    expect(await screen.findByText('편집 화면')).not.toBeNull()
    expect(apiMocks.createArticle).toHaveBeenCalledWith({
      articleId: 'moor-lines',
      title: '계류줄 묶기',
      stageKey: 'open-water',
      stagePosition: 9,
    })
  })

  it('목록을 받는 중 로그인이 풀리면 권한 없음이 아니라 재로그인을 안내한다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({ status: 'unauthenticated' })
    renderList()

    expect(await screen.findByRole('heading', { name: '다시 로그인해 주세요' })).not.toBeNull()
    expect(screen.queryByText('볼 수 없는 화면입니다')).toBeNull()
  })

  it('만들기가 409면 그 자리에 안내가 보인다', async () => {
    apiMocks.checkAdminAccess.mockResolvedValue({ status: 'admin' })
    apiMocks.fetchAdminArticles.mockResolvedValue({ status: 'loaded', articles: [] })
    apiMocks.createArticle.mockResolvedValue({
      status: 'idTaken',
      message: '이미 쓰이고 있는 식별자입니다.',
    })
    const user = userEvent.setup()
    renderList()

    await user.type(await screen.findByLabelText('식별자'), 'tide-reading')
    await user.type(screen.getByLabelText('제목'), '물때 읽기')
    await user.selectOptions(screen.getByLabelText('단계'), 'shore-basics')
    await user.type(screen.getByLabelText('단계 안 순서'), '2')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      '이미 쓰이고 있는 식별자입니다.',
    )
  })
})

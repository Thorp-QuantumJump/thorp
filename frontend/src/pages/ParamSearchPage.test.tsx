import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '@/auth/AuthProvider'
import { authenticatedUser } from '@/test/authFixtures'
import { acceptedFixture, previewFixture } from '@/test/paramSearchFixtures'
import type { AxisValue, ParamSearchRequest } from '@/types/paramSearch'
import { ParamSearchPage } from './ParamSearchPage'

const apiMocks = vi.hoisted(() => ({
  previewParamSearch: vi.fn(),
  createParamSearch: vi.fn(),
  getParamSearch: vi.fn(),
}))

const authMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
}))

vi.mock('@/api/paramSearch', async () => {
  const actual = await vi.importActual<typeof import('@/api/paramSearch')>('@/api/paramSearch')
  return { ...actual, ...apiMocks }
})

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return { ...actual, ...authMocks }
})

/**
 * 서버를 흉내 낸다. **미리보기는 보낸 `space`만 되돌려 준다** — 이것이 이 파일이 지키는 사실이다.
 * 실제 백엔드도 좁혀 보내면 `spaceSummary.axes[].values`에 좁힌 값만 싣는다.
 */
function respondLikeServer(request: ParamSearchRequest) {
  const picked = request.space?.ALPHA_TILT?.tiltWindow
  if (!picked) return Promise.resolve(previewFixture)

  const [tiltAxis, modeAxis] = previewFixture.spaceSummary[0].axes
  const combinations = picked.length * modeAxis.values.length
  return Promise.resolve({
    ...previewFixture,
    spaceSummary: [
      {
        ...previewFixture.spaceSummary[0],
        axes: [{ ...tiltAxis, values: picked as AxisValue[] }, modeAxis],
        combinations,
      },
      previewFixture.spaceSummary[1],
    ],
    workload: {
      ...previewFixture.workload,
      totalCombinations: combinations + 1,
      validCombinations: combinations + 1,
      excludedCombinations: 0,
      exclusionReasons: [],
    },
  })
}

function renderPage(search = '?instruments=TEST.XKRX&strategies=ALPHA_TILT') {
  return render(
    <MemoryRouter initialEntries={[`/param-search${search}`]}>
      <AuthProvider>
        <ParamSearchPage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('ParamSearchPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
    apiMocks.getParamSearch.mockResolvedValue({ ...acceptedFixture, status: 'COMPLETED' })
    apiMocks.previewParamSearch.mockImplementation(respondLikeServer)
    apiMocks.createParamSearch.mockResolvedValue(acceptedFixture)
  })

  it('대상이 없으면 실행 대신 들어온 경로를 안내한다', async () => {
    renderPage('')

    expect(await screen.findByText('탐색 대상이 없습니다')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /설정 조합 자동 비교 실행/ })).toBeNull()
    expect(apiMocks.previewParamSearch).not.toHaveBeenCalled()
  })

  it('값을 껐다가 다시 켤 수 있다 — 좁힌 응답이 등록 범위를 덮지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole('button', { name: /설정 조합 자동 비교 실행/ })
    await user.click(screen.getByRole('button', { name: '탐색 범위 상세 변경' }))

    const twenty = () => screen.getByRole('button', { name: /^20/ })
    await user.click(twenty())

    // 좁힌 응답이 돌아온 뒤에도 끈 값의 자리가 남아 있어야 다시 켤 수 있다.
    await waitFor(() => expect(apiMocks.previewParamSearch).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(twenty().getAttribute('aria-pressed')).toBe('false'))
    expect(screen.getByRole('button', { name: /^5/ }).getAttribute('aria-pressed')).toBe('true')

    await user.click(twenty())

    await waitFor(() => expect(twenty().getAttribute('aria-pressed')).toBe('true'))
  })

  it('좁힌 범위가 예상 작업량에 반영된다', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole('button', { name: /설정 조합 자동 비교 실행/ })
    await user.click(screen.getByRole('button', { name: '탐색 범위 상세 변경' }))
    await user.click(screen.getByRole('button', { name: /^20/ }))

    // 축 후보값 3개 → 2개면 조합은 4개, 축 없는 전략 1개를 더해 5개다.
    await waitFor(() => expect(apiMocks.previewParamSearch).toHaveBeenCalledTimes(2))
    await waitFor(() => {
      expect(screen.getByText('5개')).toBeTruthy()
    })
  })

  it('좁힌 범위를 그대로 실어 접수한다', async () => {
    const user = userEvent.setup()
    renderPage()

    const run = await screen.findByRole('button', { name: /설정 조합 자동 비교 실행/ })
    await user.click(screen.getByRole('button', { name: '탐색 범위 상세 변경' }))
    await user.click(screen.getByRole('button', { name: /^20/ }))
    await waitFor(() => expect(apiMocks.previewParamSearch).toHaveBeenCalledTimes(2))

    await waitFor(() => expect((run as HTMLButtonElement).disabled).toBe(false))
    await user.click(run)

    await waitFor(() => expect(apiMocks.createParamSearch).toHaveBeenCalledTimes(1))
    const [body, requesterId] = apiMocks.createParamSearch.mock.calls[0]
    expect(body.space.ALPHA_TILT.tiltWindow).toEqual([5, 10])
    expect(body.registryVersion).toBe(previewFixture.registryVersion)
    // 요청자 헤더는 접수에만 붙는다. 인증이 아니라 spec 014 전의 임시 통로다.
    expect(requesterId).toBe(authenticatedUser.userId)
  })

  it('접수하면 searchId 를 주소에 남기고 진행 상태를 폴링한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /설정 조합 자동 비교 실행/ }))

    expect(await screen.findByText(acceptedFixture.searchId)).toBeTruthy()
    await waitFor(() => expect(apiMocks.getParamSearch).toHaveBeenCalledWith(acceptedFixture.searchId))
  })

  it('로그인 전에는 실행을 막고 이유를 적는다', async () => {
    authMocks.getCurrentUser.mockRejectedValue(new Error('anonymous'))
    renderPage()

    const run = (await screen.findByRole('button', {
      name: /설정 조합 자동 비교 실행/,
    })) as HTMLButtonElement
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('로그인'))
    expect(run.disabled).toBe(true)
  })
})

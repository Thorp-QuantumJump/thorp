import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ParamSearchError } from '@/api/paramSearch'
import { AuthProvider } from '@/auth/AuthProvider'
import { authenticatedUser } from '@/test/authFixtures'
import {
  acceptedFixture,
  changedPreviewFixture,
  previewFixture,
} from '@/test/paramSearchFixtures'
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

const target = '/param-search?instruments=TEST.XKRX&strategies=ALPHA_TILT'

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[target]}>
      <AuthProvider>
        <ParamSearchPage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

/** 로그인 확인이 끝나 실행이 열릴 때까지 기다린다. */
async function readyRunButton() {
  const run = (await screen.findByRole('button', {
    name: /설정 조합 자동 비교 실행/,
  })) as HTMLButtonElement
  await waitFor(() => expect(run.disabled).toBe(false))
  return run
}

/**
 * 등록표 경계 흐름(T063b, FR-028).
 *
 * <p>두 가지가 조용히 깨질 수 있다. 하나는 <b>409를 받고 자동으로 다시 보내는 것</b> — 사용자는 자기가
 * 확인하지 않은 범위의 결과를 받는다. 다른 하나는 <b>503을 받고 캐시된 요약으로 실행하는 것</b> —
 * 등록 정보를 모르는 채 돌린 탐색이 된다. 둘 다 화면에는 아무 티가 나지 않아서 테스트로 고정한다.
 */
describe('ParamSearchPage 등록표 경계', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authMocks.getCurrentUser.mockResolvedValue(authenticatedUser)
    apiMocks.getParamSearch.mockResolvedValue({ ...acceptedFixture, status: 'COMPLETED' })
  })

  it('409를 받으면 확인 전에는 실행을 다시 보내지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.previewParamSearch
      .mockResolvedValueOnce(previewFixture)
      .mockResolvedValue(changedPreviewFixture)
    apiMocks.createParamSearch.mockRejectedValueOnce(
      new ParamSearchError('등록표 버전이 달라졌습니다.', {
        code: 'REGISTRY_VERSION_CHANGED',
        status: 409,
      }),
    )

    renderPage()
    await user.click(await readyRunButton())

    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('등록표가 바뀌었습니다')

    // 확인을 받기 전이므로 접수는 한 번뿐이다.
    expect(apiMocks.createParamSearch).toHaveBeenCalledTimes(1)

    // 무엇이 바뀌었는지 후보값과 조합 수로 보인다.
    await waitFor(() => {
      expect(dialog.textContent).toContain('5, 10, 20 → 5, 10')
    })
    expect(dialog.textContent).toContain('조합 6개 → 4개')
    expect(dialog.textContent).toContain('TEST-REGISTRY-2')
  })

  it('확인을 누르면 새 등록표 버전으로 한 번 더 보낸다', async () => {
    const user = userEvent.setup()
    apiMocks.previewParamSearch
      .mockResolvedValueOnce(previewFixture)
      .mockResolvedValue(changedPreviewFixture)
    apiMocks.createParamSearch
      .mockRejectedValueOnce(
        new ParamSearchError('등록표 버전이 달라졌습니다.', {
          code: 'REGISTRY_VERSION_CHANGED',
          status: 409,
        }),
      )
      .mockResolvedValue(acceptedFixture)

    renderPage()
    await user.click(await readyRunButton())
    await screen.findByRole('dialog')

    const confirm = await screen.findByRole('button', { name: '확인하고 실행' })
    await waitFor(() => expect((confirm as HTMLButtonElement).disabled).toBe(false))
    await user.click(confirm)

    await waitFor(() => expect(apiMocks.createParamSearch).toHaveBeenCalledTimes(2))
    expect(apiMocks.createParamSearch.mock.calls[1][0].registryVersion).toBe('TEST-REGISTRY-2')

    // 확인이 끝나면 대화상자가 닫히고 진행 상태로 넘어간다. 식별자는 화면에 보이지 않는다.
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await screen.findByRole('heading', { name: '진행 상태' })).toBeTruthy()
    expect(screen.queryByText(acceptedFixture.searchId)).toBeNull()
  })

  it("503은 '잠시 후 다시' 안내로 끝나고 실행을 열지 않는다", async () => {
    apiMocks.previewParamSearch.mockRejectedValue(
      new ParamSearchError('등록 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.', {
        code: 'REGISTRY_UNAVAILABLE',
        status: 503,
      }),
    )

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('잠시 후 다시')
    expect(screen.queryByRole('button', { name: /설정 조합 자동 비교 실행/ })).toBeNull()
    expect(apiMocks.createParamSearch).not.toHaveBeenCalled()
  })

  it('한 번 받아 둔 요약이 있어도 다시 조회가 실패하면 그것으로 실행하지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.previewParamSearch.mockResolvedValueOnce(previewFixture).mockRejectedValue(
      new ParamSearchError('등록 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.', {
        code: 'REGISTRY_UNAVAILABLE',
        status: 503,
      }),
    )

    renderPage()
    await readyRunButton()

    // 범위를 좁히면 확인 내용을 다시 받는다 — 그 조회가 실패하는 상황이다.
    await user.click(screen.getByRole('button', { name: '탐색 범위 상세 변경' }))
    await user.click(screen.getByRole('button', { name: /^20/ }))

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /설정 조합 자동 비교 실행/ })).toBeNull()
    })
    expect((await screen.findByRole('alert')).textContent).toContain('잠시 후 다시')
    expect(apiMocks.createParamSearch).not.toHaveBeenCalled()
  })
})

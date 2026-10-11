import { render, screen } from '@testing-library/react'
import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
} from '@/diagnosis/selectionStorage'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import {
  resultDetailCompleted,
  resultExceptions,
  resultSectorCatalog,
  resultTimeseries,
  resultToken,
  resultWeights,
  syntheticStrategyName,
} from '@/test/resultFixtures'
import { registryFixture } from '@/test/strategyRegistryFixtures'
import App from '@/App'

/**
 * 013 T151. 배우기 밖 화면은 나의 학습을 부르지 않고 보이지 않는다(US4 시나리오 1~3, FR-030, SC-010·SC-016,
 * quickstart 11-2 배우기 밖 알림·11-6).
 *
 * <p>로그인 사용자로 진단(`/diagnosis/sectors`)·전략 설정(`/strategy`)·결과(`/results/:token`)·시장 흐름(`/market-flow`)을
 * 실제 앱 경로로 그린다. 모든 서버 요청은 `apiClient`와 전역 `fetch`에서 받아 적는다. 그 가운데 `/learning/me`가 없고,
 * 경험치·배지·레벨·공개 안내·비로그인 기록 안내 문구가 없으며, 진단 진입이 배우기로 돌려보내지 않는지(학습 완료 조건이
 * 없는지) 본다. 서버가 응답하지 않는 모습과, 진단·전략 설정·결과 화면이 데이터를 받은 모습을 함께 본다. 실패 모습만
 * 보면 오류 화면의 글자만 확인하게 되기 때문이다.
 */

const signedIn: AuthContextValue = {
  ...anonymousAuthContext,
  status: 'authenticated',
  user: authenticatedUser,
}

/** 배우기 밖에서 보이면 안 되는 문구. 경험치·배지·레벨 표시, 공개 안내, 비로그인 기록 안내다. */
const learningWords =
  /경험치|배지|레벨 \d|나의 학습|공개했습니다|지금까지의 읽은 기록|로그인하면 학습 기록이 남습니다|이어서 읽기|읽은 글 \d/

let requested: string[] = []
let originalAdapter: typeof apiClient.defaults.adapter

function PathProbe() {
  const location = useLocation()
  return <output data-testid="pathname">{location.pathname}</output>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={signedIn}>
        <App />
        <PathProbe />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

/** 진단·전략 설정·결과 화면이 첫 화면을 그리는 데 쓰는 응답. 그 밖의 경로는 연결 실패다. */
const LOADED: Record<string, unknown> = {
  '/sectors': resultSectorCatalog,
  '/strategies/registry': registryFixture,
  [`/results/${resultToken}`]: resultDetailCompleted,
  [`/results/${resultToken}/timeseries`]: resultTimeseries,
  [`/results/${resultToken}/weights`]: resultWeights,
  [`/results/${resultToken}/exceptions`]: resultExceptions,
}

function installAdapter(answer: (url: string) => unknown | undefined) {
  const adapter: AxiosAdapter = (config) => {
    const url = String(config.url)
    requested.push(url)
    const data = answer(url.split('?')[0])
    if (data === undefined) {
      return Promise.reject(new AxiosError('offline', AxiosError.ERR_NETWORK, config))
    }
    return Promise.resolve({
      data: { success: true, data, error: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: { ...config, headers: new AxiosHeaders() },
    })
  }
  apiClient.defaults.adapter = adapter
}

async function settle() {
  // 화면이 그려질 때까지 기다린 뒤 조금 더 둔다. 늦게 붙는 부품의 요청도 받아 적는다. 결과 화면처럼 주소만으로 서버를
  // 부르지 않는 화면도 있어 요청 수는 기다리지 않는다. 화면마다 늦게 불러오는 코드라 전체 실행의 부하에서는 오래 걸릴
  // 수 있다.
  await screen.findByRole('main', undefined, { timeout: 30_000 })
  await new Promise((resolve) => setTimeout(resolve, 500))
}

function expectNoLearning(path: string) {
  expect(requested.filter((url) => url.includes('/learning/me'))).toEqual([])
  expect(document.body.textContent ?? '').not.toMatch(learningWords)
  // 진단 진입에 학습 완료 조건이 없다. 배우기로 돌려보내지 않고 그 경로에 머문다.
  expect(screen.getByTestId('pathname').textContent).toBe(path)
}

describe('배우기 밖 화면', () => {
  beforeEach(() => {
    requested = []
    window.sessionStorage.clear()
    originalAdapter = apiClient.defaults.adapter
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        requested.push(String(input))
        return Promise.reject(new TypeError('offline'))
      }),
    )
  })

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter
    vi.unstubAllGlobals()
  })

  it.each(['/diagnosis/sectors', '/strategy', '/results/synthetic-token', '/market-flow'])(
    '서버가 응답하지 않아도 %s는 /learning/me를 부르지 않고 나의 학습 문구가 없다',
    async (path) => {
      installAdapter(() => undefined)
      renderAt(path)
      await settle()
      expectNoLearning(path)
    },
    60_000,
  )

  it('진단 화면은 섹터 목록을 받은 모습에서도 나의 학습을 부르지 않고 그 문구가 없다', async () => {
    installAdapter((url) => LOADED[url])
    renderAt('/diagnosis/sectors')
    await settle()

    expect(requested).toContain('/sectors')
    expect(screen.queryByRole('alert')).toBeNull()
    expectNoLearning('/diagnosis/sectors')
  }, 60_000)

  it('전략 설정 화면은 등록표를 받은 모습에서도 나의 학습을 부르지 않고 그 문구가 없다', async () => {
    // 002가 마친 섹터 선택(계약 형식)이 있어야 전략 설정 화면이 등록표를 부른다.
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION,
        mapVersion: resultSectorCatalog.mapVersion,
        selected: [
          { code: 'HARBOR', weightTenths: 500 },
          { code: 'LIGHTHOUSE', weightTenths: 500 },
        ],
        weightsTouched: true,
      }),
    )
    installAdapter((url) => LOADED[url])
    renderAt('/strategy')
    await settle()

    expect(requested.some((url) => url.startsWith('/strategies/registry'))).toBe(true)
    expectNoLearning('/strategy')
  }, 60_000)

  it('결과 화면은 결과를 받은 모습에서도 나의 학습을 부르지 않고 그 문구가 없다', async () => {
    installAdapter((url) => LOADED[url])
    renderAt(`/results/${resultToken}`)
    await settle()

    expect((await screen.findAllByText(new RegExp(syntheticStrategyName))).length).toBeGreaterThan(
      0,
    )
    expectNoLearning(`/results/${resultToken}`)
  }, 60_000)
})

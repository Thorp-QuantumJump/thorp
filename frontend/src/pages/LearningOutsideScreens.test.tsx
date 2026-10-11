import { render, screen } from '@testing-library/react'
import { AxiosError, type AxiosAdapter } from 'axios'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { anonymousAuthContext, authenticatedUser } from '@/test/authFixtures'
import App from '@/App'

/**
 * 013 T151. 배우기 밖 화면은 나의 학습을 부르지 않고 보이지 않는다(US4 시나리오 1~3, FR-030, SC-010·SC-016,
 * quickstart 11-2 배우기 밖 알림·11-6).
 *
 * <p>로그인 사용자로 진단(`/diagnosis/sectors`)·전략 설정(`/strategy`)·결과(`/results/:token`)·시장 흐름(`/market-flow`)을
 * 실제 앱 경로로 그린다. 모든 서버 요청은 `apiClient`와 전역 `fetch`에서 받아 적고 연결 실패로 돌려준다. 그 가운데
 * `/learning/me`가 없고, 경험치·배지·레벨·공개 안내·비로그인 기록 안내 문구가 없으며, 진단 진입이 배우기로 돌려보내지
 * 않는지(학습 완료 조건이 없는지) 본다.
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

describe('배우기 밖 화면', () => {
  beforeEach(() => {
    requested = []
    window.sessionStorage.clear()
    originalAdapter = apiClient.defaults.adapter
    const offline: AxiosAdapter = (config) => {
      requested.push(String(config.url))
      return Promise.reject(new AxiosError('offline', AxiosError.ERR_NETWORK, config))
    }
    apiClient.defaults.adapter = offline
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
    '%s는 /learning/me를 부르지 않고 나의 학습 문구가 없다',
    async (path) => {
      renderAt(path)

      // 화면이 그려질 때까지 기다린 뒤 조금 더 둔다. 늦게 붙는 부품의 요청도 받아 적는다. 결과 화면처럼 주소만으로
      // 서버를 부르지 않는 화면도 있어 요청 수는 기다리지 않는다.
      // 화면마다 늦게 불러오는 코드라 전체 실행의 부하에서는 오래 걸릴 수 있다.
      await screen.findByRole('main', undefined, { timeout: 30_000 })
      await new Promise((resolve) => setTimeout(resolve, 500))

      expect(requested.filter((url) => url.includes('/learning/me'))).toEqual([])
      expect(document.body.textContent ?? '').not.toMatch(learningWords)
      // 진단 진입에 학습 완료 조건이 없다. 배우기로 돌려보내지 않고 그 경로에 머문다.
      expect(screen.getByTestId('pathname').textContent).toBe(path)
    },
    60_000,
  )
})

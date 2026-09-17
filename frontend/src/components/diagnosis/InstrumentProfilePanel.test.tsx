import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InstrumentProfileResult, SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { SectorSelectionPage } from '@/pages/SectorSelectionPage'
import type { InstrumentProfile, SectorCatalog, SectorSummary } from '@/types/sector'

/**
 * SC-012를 이 검사가 받는다. quickstart에 그 시나리오가 없기 때문이다
 * (tasks.md 「SC-012와 SC-013에는 quickstart 시나리오가 없다」).
 *
 * 패널만 떼어 보지 않고 목록 화면째로 연다. SC-012가 묻는 것이 "목록의 **모든 항목**에서 열리는가"와
 * "여는 동안 화면을 떠나지 않는가"라, 목록과 경로가 함께 있어야 확인할 수 있다.
 */

const apiMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchInstrumentProfile: vi.fn(),
}))

vi.mock('@/api/sectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/sectors')>('@/api/sectors')
  return { ...actual, ...apiMocks }
})

function sector(
  code: string,
  name: string,
  classification: SectorSummary['classification'],
): SectorSummary {
  return {
    code,
    name,
    classification,
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn: '2018-09-12',
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const catalog: SectorCatalog = {
  mapVersion: 'SEM-2026-09-10-001',
  isSample: false,
  sectors: [
    sector('BATTERY', '2차전지', 'THEME'),
    sector('SHIPBUILDING', '조선', 'INDUSTRY'),
    sector('SEMICONDUCTOR', '반도체', 'INDUSTRY'),
  ],
}

const loaded: SectorCatalogResult = { status: 'loaded', catalog }

const profile: InstrumentProfile = {
  instrumentId: '091160.XKRX',
  name: 'KODEX 반도체',
  benchmarkIndex: 'KRX 반도체',
  indexMethodologySummary: 'KRX 반도체 지수를 시가총액 가중으로 따라갑니다.',
  listedOn: '2006-06-27',
  manager: '삼성자산운용',
  holdingsOverlap: {
    asOfCurrentComposition: true,
    topHoldings: [
      { name: '삼성전자', weight: '0.2431' },
      { name: 'SK하이닉스', weight: '0.2107' },
    ],
  },
  avgDailyValue3m: '1240000000',
  sourceAttribution: '한국거래소 통계정보',
  survivorshipNote: '현재 상장되어 있는 상품만 다룹니다.',
  datasetId: 'KRX_DAILY_20260907',
  dataDeadline: '2026-09-07T09:00:00Z',
  isSample: false,
}

const profileLoaded: InstrumentProfileResult = { status: 'loaded', profile }

/** 지금 경로를 화면에 드러내 값으로 확인한다. */
function PathProbe() {
  const location = useLocation()
  return <output data-testid="path">{location.pathname}</output>
}

async function openPage() {
  apiMocks.fetchSectorCatalog.mockResolvedValue(loaded)

  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  const result = render(
    <MemoryRouter initialEntries={['/diagnosis/sectors']}>
      <AuthContext.Provider value={authValue}>
        <PathProbe />
        <Routes>
          <Route path="/diagnosis/sectors" element={<SectorSelectionPage />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  )

  await screen.findAllByRole('listitem')
  return result
}

type User = ReturnType<typeof userEvent.setup>

function toggleFor(sectorName: string): HTMLButtonElement {
  return screen.getByRole('button', { name: `${sectorName}에 담긴 종목 보기` }) as HTMLButtonElement
}

function panelFor(sectorCode: string): HTMLElement | null {
  const card = document.querySelector(`[data-sector-code="${sectorCode}"]`)
  return card?.querySelector('[data-slot="profile-panel"]') ?? null
}

async function openProfile(user: User, sectorName: string, sectorCode: string) {
  await user.click(toggleFor(sectorName))
  await waitFor(() => {
    expect(panelFor(sectorCode)?.querySelector('[data-slot="profile-body"]')).not.toBeNull()
  })
}

function currentPath(): string {
  return screen.getByTestId('path').textContent ?? ''
}

describe('대표 상품 정보 패널', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    apiMocks.fetchInstrumentProfile.mockResolvedValue(profileLoaded)
  })

  afterEach(() => {
    window.sessionStorage.clear()
  })

  it('펼치기 전에는 조회하지 않는다', async () => {
    await openPage()

    // 목록만 보는 사용자는 이 왕복을 치르지 않는다.
    expect(apiMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
    expect(panelFor('SEMICONDUCTOR')).toBeNull()
  })

  it('SC-012. 목록의 모든 항목에서 열린다', async () => {
    const user = userEvent.setup()
    await openPage()

    for (const [name, code] of [
      ['반도체', 'SEMICONDUCTOR'],
      ['조선', 'SHIPBUILDING'],
      ['2차전지', 'BATTERY'],
    ] as const) {
      await openProfile(user, name, code)
      expect(panelFor(code)).not.toBeNull()
    }
  })

  it('SC-012. 여는 동안 화면을 떠나지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()

    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    // 상세를 보려고 다른 화면으로 이동시키지 않는다(FR-010, 원칙 II).
    expect(currentPath()).toBe('/diagnosis/sectors')
    expect(screen.queryByRole('link', { name: /상품|상세/ })).toBeNull()
  })

  it('SC-012. 409에서 빈 자리가 아니라 한 문장과 다시 불러오기가 보인다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'notReady',
      message: '상품 정보 준비 중',
    } satisfies InstrumentProfileResult)
    await openPage()

    await user.click(toggleFor('반도체'))

    const panel = await waitFor(() => {
      const found = panelFor('SEMICONDUCTOR')
      expect(found?.textContent).toContain('준비 중')
      return found as HTMLElement
    })
    expect(within(panel).getByRole('button', { name: '다시 불러오기' })).toBeTruthy()
  })

  it('다시 눌러도 달라지지 않는 상태에는 행동을 두지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_NOT_FOUND',
      message: '없는 섹터',
      retryable: false,
    } satisfies InstrumentProfileResult)
    await openPage()

    await user.click(toggleFor('반도체'))

    const panel = await waitFor(() => {
      const found = panelFor('SEMICONDUCTOR')
      expect(found?.textContent).toContain('찾을 수 없습니다')
      return found as HTMLElement
    })
    expect(within(panel).queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  it('보유 구성을 상위 종목과 나머지로 나눠 보인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const panel = panelFor('SEMICONDUCTOR') as HTMLElement
    const holdings = [...panel.querySelectorAll('[data-slot="holding"]')].map(
      (node) => node.textContent ?? '',
    )

    expect(holdings[0]).toContain('삼성전자')
    expect(holdings[0]).toContain('24.31%')
    expect(holdings[1]).toContain('21.07%')
    // 상위 몇 개만 보이면 사용자는 그것이 전부라고 읽는다. 나머지를 빼놓지 않는다.
    expect(holdings.at(-1)).toContain('그 외')
    expect(holdings.at(-1)).toContain('54.62%')
  })

  it('지금 시점의 구성이면 과거를 이 구성으로 설명하지 않는다 (원칙 V)', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const panel = panelFor('SEMICONDUCTOR') as HTMLElement
    const caveat = panel.querySelector('[data-slot="composition-caveat"]')

    expect(caveat?.textContent).toMatch(/과거 수익을 이\s*종목들로 설명할 수 없습니다/)
  })

  it('과거 구성을 재현한 자료면 그 경고를 붙이지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'loaded',
      profile: {
        ...profile,
        holdingsOverlap: { ...profile.holdingsOverlap, asOfCurrentComposition: false },
      },
    } satisfies InstrumentProfileResult)
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const panel = panelFor('SEMICONDUCTOR') as HTMLElement
    expect(panel.querySelector('[data-slot="composition-caveat"]')).toBeNull()
  })

  it('출처는 목록 화면의 상수가 아니라 응답이 준 값을 쓴다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'loaded',
      profile: { ...profile, sourceAttribution: '다른 출처 표기' },
    } satisfies InstrumentProfileResult)
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const source = panelFor('SEMICONDUCTOR')?.querySelector('[data-slot="profile-source"]')
    expect(source?.textContent).toContain('다른 출처 표기')
    // 어느 시점까지의 자료인지는 보이되 자료 식별자는 날것으로 두지 않는다(FR-011).
    expect(source?.textContent).toContain('2026-09-07')
    expect(panelFor('SEMICONDUCTOR')?.textContent).not.toContain('KRX_DAILY_20260907')
  })

  it('상품 정보의 예시 표시는 자료 버전 기준이며 목록의 예시 표시와 별개다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'loaded',
      profile: { ...profile, isSample: true },
    } satisfies InstrumentProfileResult)
    // 목록 쪽 대응표는 확정된 상태다. 한쪽 값으로 다른 쪽을 짐작하지 않는다.
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    expect(
      panelFor('SEMICONDUCTOR')?.querySelector('[data-slot="profile-sample"]'),
    ).not.toBeNull()
    expect(screen.queryByText(/이 섹터 목록은 아직 확정되지 않은/)).toBeNull()
  })

  it('거래대금을 자릿수 대신 읽을 수 있는 단위로 보인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    expect(panelFor('SEMICONDUCTOR')?.textContent).toContain('12.4억 원')
  })

  it('떨어지는 거래대금에 없는 소수 자리를 붙이지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'loaded',
      profile: { ...profile, avgDailyValue3m: '357400000000' },
    } satisfies InstrumentProfileResult)
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    // `3,574억`이지 `3574.0억`이 아니다. 소수 한 자리까지 잰 값처럼 보이면 안 된다.
    const text = panelFor('SEMICONDUCTOR')?.textContent ?? ''
    expect(text).toContain('3,574억 원')
    expect(text).not.toContain('.0억')
  })

  it('상품 이름 뒤에 조사를 붙이는 문장을 두지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    /*
      받침이 있는지에 따라 은/는이 갈리는데 상품 이름은 서버가 주는 값이라 미리 알 수 없다.
      한쪽으로 고정하면 절반은 틀린 문장이 된다. 사실은 조사 없는 목록으로 보인다.
    */
    const text = panelFor('SEMICONDUCTOR')?.textContent ?? ''
    expect(text).not.toContain('KODEX 반도체은')
    expect(text).not.toContain('KODEX 반도체는')

    const facts = panelFor('SEMICONDUCTOR')?.querySelector('[data-slot="profile-facts"]')
    expect(facts?.textContent).toContain('KRX 반도체')
    expect(facts?.textContent).toContain('삼성자산운용')
  })

  it('키보드만으로 열고 닫을 수 있다 (CHK005, CHK033)', async () => {
    const user = userEvent.setup()
    await openPage()

    const toggle = toggleFor('반도체')
    toggle.focus()
    await user.keyboard('{Enter}')
    await waitFor(() => expect(panelFor('SEMICONDUCTOR')).not.toBeNull())
    expect(toggle.getAttribute('aria-expanded')).toBe('true')

    await user.keyboard('{Escape}')
    expect(panelFor('SEMICONDUCTOR')).toBeNull()
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    // 닫은 뒤 초점을 두고 오면 목록의 처음부터 다시 찾아 내려와야 한다.
    expect(document.activeElement).toBe(toggle)
  })

  it('펼침 버튼이 어느 패널을 여는지 보조 기술에 알린다', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const toggle = toggleFor('반도체')
    const controlled = toggle.getAttribute('aria-controls')
    expect(controlled).toBeTruthy()
    expect(panelFor('SEMICONDUCTOR')?.id).toBe(controlled)
  })

  it('펼치는 것과 섹터를 고르는 것이 서로 섞이지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()

    const checkbox = screen.getByRole('checkbox', { name: '반도체' }) as HTMLInputElement
    expect(checkbox.checked).toBe(false)

    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    // 펼침이 label 안에 있으면 살펴보려다 섹터가 골라진다.
    expect(checkbox.checked).toBe(false)
  })
})

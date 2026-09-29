import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InstrumentProfileResult, SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { SectorSelectionPage } from '@/pages/SectorSelectionPage'
import type { InstrumentProfile, SectorCatalog, SectorSummary } from '@/types/sector'

/**
 * 넓힌 카드가 보이는 상품 정보다. SC-012를 이 검사가 받는다. quickstart에 그 시나리오가 없기
 * 때문이다(tasks.md 「SC-012와 SC-013에는 quickstart 시나리오가 없다」).
 *
 * 카드만 떼어 보지 않고 화면째로 연다. SC-012가 묻는 것이 "목록의 **모든 항목**에서 열리는가"와
 * "여는 동안 화면을 떠나지 않는가"라, 목록과 경로가 함께 있어야 확인할 수 있다.
 */

const apiMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchInstrumentProfile: vi.fn(),
  fetchPriceSeries: vi.fn(),
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

/** 카드 왼쪽 목록의 한 줄이다. 누르면 그 대상을 보면서 담고, 다시 누르면 뺀다. */
function row(sectorCode: string): HTMLButtonElement {
  return document.querySelector(
    `[data-slot="list"] [data-sector-code="${sectorCode}"] [data-slot="pill"]`,
  ) as HTMLButtonElement
}

/** 카드를 넓히고 접는 단추다. 오른쪽 위에 아이콘 하나로 있다. */
function expandToggle(): HTMLButtonElement {
  return document.querySelector('[data-slot="zoom"]') as HTMLButtonElement
}

/** 카드 안을 통째로 본다. 넓힌 카드의 설명과 담긴 종목이 모두 이 안에 있다. */
function cardBody(): HTMLElement {
  return document.querySelector('[data-slot="body"]') as HTMLElement
}

/**
 * 상품 정보가 "아직 줄 것이 없다"고 알리는 자리다.
 *
 * 카드 안에는 가격 흐름도 같은 모양의 안내를 둘 수 있어, 카드 전체에서 단추를 찾으면 어느 쪽
 * 안내를 본 것인지 가릴 수 없다.
 */
function profileNotice(): HTMLElement | null {
  return document.querySelector('[data-slot="profile-notice"]')
}

function expanded(): boolean {
  return (
    document.querySelector('[data-viewing-code]')?.getAttribute('data-expanded') === 'true'
  )
}

/** 상품 정보를 기다리지 않고 카드만 넓힌다. 자료가 없는 상태를 보는 검사가 쓴다. */
async function zoomIn(user: User, sectorCode: string, sectorName: string) {
  await user.click(row(sectorCode))
  await waitFor(() => {
    expect(document.querySelector('[data-slot="detail-name"]')?.textContent).toBe(
      `KODEX ${sectorName}`,
    )
  })
  if (!expanded()) await user.click(expandToggle())
}

/**
 * 그 대상을 넓혀 상품 정보가 다 실린 상태로 만든다.
 *
 * 목록에서 보는 것과 카드를 넓히는 것은 서로 다른 행동이라 두 번 누른다. 이름을 함께 받는
 * 까닭은 카드가 실제로 그 대상으로 바뀌었는지 이름으로 확인하기 위해서다.
 */
async function openProfile(user: User, sectorName: string, sectorCode: string) {
  await zoomIn(user, sectorCode, sectorName)
  await waitFor(() => {
    expect(cardBody().querySelector('[data-slot="profile-body"]')).not.toBeNull()
  })
}

function currentPath(): string {
  return screen.getByTestId('path').textContent ?? ''
}

describe('대표 상품 정보 패널', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 카드는 접힌 채로도 가격을 부른다. 그림 자체는 다른 검사가 본다.
    apiMocks.fetchPriceSeries.mockResolvedValue({
      status: 'notReady',
      message: '준비 중',
    })
    window.sessionStorage.clear()
    apiMocks.fetchInstrumentProfile.mockResolvedValue(profileLoaded)
  })

  afterEach(() => {
    window.sessionStorage.clear()
  })

  it('가격 흐름을 그리지 못하면 출처를 카드 바닥에 따로 남긴다 (원칙 X)', async () => {
    await openPage()

    // 기간 줄이 없으니 출처가 이어 붙을 자리가 없다. 그래도 화면에서 빠지지 않는다.
    const card = document.querySelector('[data-viewing-code]') as HTMLElement
    await waitFor(() => expect(card.querySelector('[data-slot="footer"] [data-slot="source"]')?.textContent).toContain('한국거래소 통계정보'))
    expect(card.querySelector('[data-slot="chart-source"]')).toBeNull()
  })

  it('접어 둔 카드는 상품 정보를 부르지 않는다', async () => {
    await openPage()

    // 목록을 훑기만 하는 사용자는 이 왕복을 치르지 않는다.
    expect(apiMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
    expect(cardBody().querySelector('[data-slot="profile-body"]')).toBeNull()
    expect(cardBody().querySelector('[data-slot="holdings"]')).toBeNull()
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
      expect(cardBody().querySelector('[data-slot="profile-body"]')).not.toBeNull()
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
    /*
      서버 문장과 화면 문장이 겹치지 않는 값을 쓴다. 겹치면 화면이 어느 쪽을 보였는지 이 검사가
      구별하지 못해, 둘 중 무엇이 바뀌어도 통과한다.
    */
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'notReady',
      message: '서버가 보낸 문장',
    } satisfies InstrumentProfileResult)
    const user = userEvent.setup()
    await openPage()
    await zoomIn(user, 'SEMICONDUCTOR', '반도체')

    const notReady = await waitFor(() => {
      const found = profileNotice()
      expect(found?.textContent).toContain('대표 상품 정보가 아직 준비 중입니다')
      return found as HTMLElement
    })
    expect(within(notReady).getByRole('button', { name: '다시 불러오기' })).toBeTruthy()
    // 문구는 화면이 정한다(research.md 결정 6). 서버 문장을 그대로 내보내지 않는다.
    expect(notReady.textContent).not.toContain('서버가 보낸 문장')
  })

  it('없는 섹터에는 행동을 두지 않는다', async () => {
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_NOT_FOUND',
      message: '없는 섹터',
      retryable: false,
    } satisfies InstrumentProfileResult)
    const user = userEvent.setup()
    await openPage()
    await zoomIn(user, 'SEMICONDUCTOR', '반도체')

    const notFound = await waitFor(() => {
      const found = profileNotice()
      expect(found?.textContent).toContain('찾을 수 없습니다')
      return found as HTMLElement
    })
    expect(within(notFound).queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  it('서버 자료가 망가진 경우를 없는 섹터와 같은 문장으로 알리지 않는다', async () => {
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_MAP_UNUSABLE',
      message: '대응표가 망가졌습니다',
      retryable: false,
    } satisfies InstrumentProfileResult)
    const user = userEvent.setup()
    await openPage()
    await zoomIn(user, 'SEMICONDUCTOR', '반도체')

    const broken = await waitFor(() => {
      const found = profileNotice()
      expect(found?.textContent).toContain('서버에 저장된 상품 자료에 문제가 있어')
      return found as HTMLElement
    })
    /*
      목록에 멀쩡히 보이는 섹터를 두고 "찾을 수 없습니다"라고 하면, 사용자는 자기가 잘못 눌렀다고
      읽는다. 둘 다 다시 눌러 달라지지 않지만 해야 할 일이 다르다.
    */
    expect(broken.textContent).not.toContain('찾을 수 없습니다')
    expect(within(broken).queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  it('보유 구성을 상위 종목과 나머지로 나눠 보인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const panel = cardBody() as HTMLElement
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

    const panel = cardBody() as HTMLElement
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

    const panel = cardBody() as HTMLElement
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

    const source = cardBody().querySelector('[data-slot="profile-source"]')
    expect(source?.textContent).toContain('다른 출처 표기')
    // 자료 식별자는 날것으로 두지 않는다(FR-011). 어느 시점까지인지는 사실 표가 말한다.
    expect(source?.textContent).not.toContain('KRX_DAILY')
    expect(
      cardBody().querySelector('[data-slot="profile-facts"]')?.textContent,
    ).toContain('2026-09-07')
    expect(cardBody().textContent).not.toContain('KRX_DAILY_20260907')
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
      cardBody()?.querySelector('[data-slot="profile-sample"]'),
    ).not.toBeNull()
    expect(screen.queryByText(/이 섹터 목록은 아직 확정되지 않은/)).toBeNull()
  })

  it('거래대금을 자릿수 대신 읽을 수 있는 단위로 보인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    expect(cardBody().textContent).toContain('12.4억 원')
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
    const text = cardBody().textContent ?? ''
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
    const text = cardBody().textContent ?? ''
    expect(text).not.toContain('KODEX 반도체은')
    expect(text).not.toContain('KODEX 반도체는')

    const facts = cardBody().querySelector('[data-slot="profile-facts"]')
    expect(facts?.textContent).toContain('KRX 반도체')
    expect(facts?.textContent).toContain('삼성자산운용')
  })

  it('키보드만으로 넓히고 접을 수 있다 (CHK005, CHK033)', async () => {
    const user = userEvent.setup()
    await openPage()

    const toggle = expandToggle()
    toggle.focus()
    await user.keyboard('{Enter}')
    await waitFor(() => expect(expanded()).toBe(true))
    expect(expandToggle().getAttribute('aria-expanded')).toBe('true')

    await user.keyboard('{Enter}')
    expect(expanded()).toBe(false)
    expect(expandToggle().getAttribute('aria-expanded')).toBe('false')
    // 접은 뒤에도 초점이 그 자리에 남아 바로 다시 넓힐 수 있다.
    expect(document.activeElement).toBe(expandToggle())
  })

  it('단추가 무엇을 넓히는지 보조 기술에 알린다', async () => {
    const user = userEvent.setup()
    await openPage()

    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const toggle = expandToggle()
    const controlled = toggle.getAttribute('aria-controls')
    expect(controlled).toBeTruthy()
    expect(cardBody().id).toBe(controlled)
    // 아이콘 하나뿐이라 이름을 직접 준다. 지금 상태에 따라 이름이 달라진다.
    expect(toggle.getAttribute('aria-label')).toBe('포함 종목 접기')
  })

  it('자료 시점을 한국 날짜로 보인다', async () => {
    const user = userEvent.setup()
    /*
      한국 시각으로는 2026-09-07 오전 8시이고 UTC로는 전날이다. 앞 열 글자를 자르면 하루 앞선
      날짜가 나온다.
    */
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'loaded',
      profile: { ...profile, dataDeadline: '2026-09-06T23:00:00Z' },
    } satisfies InstrumentProfileResult)
    await openPage()
    await openProfile(user, '반도체', 'SEMICONDUCTOR')

    const facts = cardBody().querySelector('[data-slot="profile-facts"]')
    expect(facts?.textContent).toContain('2026-09-07')
    expect(facts?.textContent).not.toContain('2026-09-06')
  })

  it('포함 종목을 펼치기만 해서는 투자 대상에 담기지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()

    // 처음 보고 있는 대상을 목록을 누르지 않고 넓힌다. 무엇을 사는지 보는 일이 담는 일이 되면 안 된다.
    await user.click(expandToggle())

    expect(expanded()).toBe(true)
    expect(row('SEMICONDUCTOR').getAttribute('aria-pressed')).toBe('false')
  })
})

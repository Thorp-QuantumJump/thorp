import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { InstrumentProfileResult, SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { SECTOR_SELECTION_STORAGE_KEY } from '@/diagnosis/selectionStorage'
import type { InstrumentProfile, SectorCatalog, SectorSummary } from '@/types/sector'
import { SectorSelectionPage } from './SectorSelectionPage'

/**
 * 투자 대상을 고르는 상호작용을 본다.
 *
 * 이 화면이 가르는 것은 **보는 일과 고르는 일**이다. 목록을 누르는 것은 확인이고, 백테스트
 * 대상에 담는 것은 상세 영역에서 한 번 더 누르는 일이다. 정보를 확인하려고 누른 것이 곧
 * 선택이 되면 사용자는 무엇을 담았는지 모르는 채로 다음 단계에 이른다.
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

/**
 * 표시 순서는 반도체 → 방산 → 자동차 → 조선 → 철강 → 2차전지다. 업종이 먼저이고 그 안에서
 * 한국어 이름 순이며, 테마가 뒤에 온다.
 */
const catalog: SectorCatalog = {
  mapVersion: 'SEM-2026-09-10-001',
  isSample: false,
  sectors: [
    sector('BATTERY', '2차전지', 'THEME'),
    sector('STEEL', '철강', 'INDUSTRY'),
    sector('SHIPBUILDING', '조선', 'INDUSTRY'),
    sector('AUTO', '자동차', 'INDUSTRY'),
    sector('DEFENSE', '방산', 'INDUSTRY'),
    sector('SEMICONDUCTOR', '반도체', 'INDUSTRY'),
  ],
}

const loaded: SectorCatalogResult = { status: 'loaded', catalog }

const profile: InstrumentProfile = {
  instrumentId: 'SEMICONDUCTOR.XKRX',
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

function renderPage() {
  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  return render(
    <MemoryRouter initialEntries={['/diagnosis/sectors']}>
      <AuthContext.Provider value={authValue}>
        <SectorSelectionPage />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

async function openPage() {
  apiMocks.fetchSectorCatalog.mockResolvedValue(loaded)
  const result = renderPage()
  await screen.findAllByRole('listitem')
  return result
}

type User = ReturnType<typeof userEvent.setup>

/** 카드 왼쪽 목록의 한 줄이다. 누르면 그 대상을 보면서 담고, 담긴 줄을 다시 누르면 뺀다. */
function row(code: string): HTMLButtonElement {
  return document.querySelector(
    `[data-slot="list"] [data-sector-code="${code}"] [data-slot="pill"]`,
  ) as HTMLButtonElement
}

function detailName(): string {
  return document.querySelector('[data-slot="detail-name"]')?.textContent ?? ''
}

/** 카드를 넓히고 접는 단추다. 오른쪽 위에 아이콘 하나로 있다. */
function expandButton(): HTMLButtonElement {
  return document.querySelector('[data-slot="zoom"]') as HTMLButtonElement
}

function cardExpanded(): boolean {
  return (
    document.querySelector('[data-viewing-code]')?.getAttribute('data-expanded') === 'true'
  )
}

/** 상세 상태가 바깥 보드와 선택 요약까지 전달되는가. */
function boardExpanded(): boolean {
  return (
    document.querySelector('[data-detail-expanded]')?.getAttribute('data-detail-expanded') ===
    'true'
  )
}

/** 오른쪽 선택 요약에 담긴 코드를 순서대로 모은다. */
function railCodes(): string[] {
  return [...document.querySelectorAll('[data-slot="rail-item"]')].map(
    (node) => node.getAttribute('data-sector-code') ?? '',
  )
}

async function add(user: User, ...codes: string[]) {
  for (const code of codes) await user.click(row(code))
}

describe('투자 대상 고르기', () => {
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

  it('들어오면 표시 순서상 첫 대상을 이미 보고 있다', async () => {
    await openPage()

    // 빈 상세 영역은 사용자에게 "여기서 무엇을 해야 하는가"를 묻게 만든다.
    expect(detailName()).toBe('KODEX 반도체')
    expect(row('SEMICONDUCTOR').getAttribute('aria-current')).toBe('true')
  })

  it('목록을 누르면 그 대상을 보면서 투자 대상에 담는다', async () => {
    const user = userEvent.setup()
    await openPage()

    await user.click(row('AUTO'))

    expect(detailName()).toBe('KODEX 자동차')
    expect(row('AUTO').getAttribute('aria-current')).toBe('true')
    expect(row('SEMICONDUCTOR').getAttribute('aria-current')).toBeNull()
    expect(row('AUTO').getAttribute('aria-pressed')).toBe('true')
    expect(railCodes()).toEqual(['AUTO'])
    expect(screen.getByText('1개 선택됨')).toBeTruthy()
  })

  it('담긴 줄을 다시 누르면 빠지고 보던 대상은 그대로다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'AUTO')

    await user.click(row('AUTO'))

    expect(row('AUTO').getAttribute('aria-pressed')).toBe('false')
    expect(railCodes()).toEqual([])
    expect(detailName()).toBe('KODEX 자동차')
  })

  it('담는 단추를 따로 두지 않는다', async () => {
    await openPage()

    expect(screen.queryByRole('button', { name: /투자 대상에 추가/ })).toBeNull()
  })

  it('선택 요약에서도 뺄 수 있다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    await user.click(screen.getByRole('button', { name: '자동차 빼기' }))

    expect(railCodes()).toEqual(['SEMICONDUCTOR'])
  })

  it('선택 요약은 표시 순서를 지킨다', async () => {
    const user = userEvent.setup()
    await openPage()

    // 고른 차례가 아니라 목록에 보이는 차례다. 두 자리가 다른 순서를 보이면 같은 선택이 다르게 읽힌다.
    await add(user, 'BATTERY', 'SEMICONDUCTOR', 'AUTO')

    expect(railCodes()).toEqual(['SEMICONDUCTOR', 'AUTO', 'BATTERY'])
  })

  it('지금 보고 있는 것과 담아 둔 것을 따로 드러낸다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'AUTO', 'STEEL')

    // 담긴 줄을 다시 눌러 빼면, 보고는 있지만 담지 않은 상태가 된다. 둘은 서로를 뜻하지 않는다.
    await user.click(row('STEEL'))

    expect(row('STEEL').getAttribute('aria-current')).toBe('true')
    expect(row('STEEL').getAttribute('aria-pressed')).toBe('false')
    expect(row('AUTO').getAttribute('aria-current')).toBeNull()
    expect(row('AUTO').getAttribute('aria-pressed')).toBe('true')
  })

  it('아무것도 담지 않았으면 빈 자리 대신 무엇을 해야 하는지 알린다', async () => {
    await openPage()

    expect(screen.getByText(/아직 선택한 투자 대상이 없습니다/)).toBeTruthy()
    expect(railCodes()).toEqual([])
  })

  it('접은 카드는 이름과 가격 흐름만 두고, 펼치면 목록이 준 섹터·기준 지수·상장일을 보인다', async () => {
    const user = userEvent.setup()
    await openPage()

    // 접은 카드는 빠르게 견주는 자리라 설명 줄을 두지 않는다.
    expect(screen.queryByText(/2018-09-12 상장/)).toBeNull()
    await user.click(expandButton())
    expect(await screen.findByText(/2018-09-12 상장/)).toBeTruthy()
    expect(screen.getAllByText(/KRX 반도체/).length).toBeGreaterThan(0)
  })

  it('접은 카드는 상품 정보를 부르지 않는다', async () => {
    await openPage()

    expect(screen.queryByText('삼성자산운용')).toBeNull()
    expect(screen.queryByText('삼성전자')).toBeNull()
    expect(apiMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
  })

  it('카드를 넓히면 담긴 종목과 자세한 설명이 같은 카드에 함께 선다', async () => {
    const user = userEvent.setup()
    await openPage()

    await user.click(expandButton())

    expect(await screen.findByText('삼성전자')).toBeTruthy()
    expect(screen.getByText('삼성자산운용')).toBeTruthy()
    expect(screen.getByText(/3개월 일평균 거래대금/)).toBeTruthy()
    expect(cardExpanded()).toBe(true)
    expect(boardExpanded()).toBe(true)
    expect(document.querySelector('[data-slot="rail-compact-summary"]')?.textContent).toContain(
      '0개 선택2개 더',
    )
    expect(document.querySelector('[data-compact]')?.getAttribute('data-compact')).toBe('true')
    expect(expandButton().getAttribute('aria-label')).toBe('포함 종목 접기')
    // 다른 화면으로 보내지 않는다. 고르던 맥락이 끊기면 어디까지 봤는지 다시 찾아야 한다.
    expect(detailName()).toBe('KODEX 반도체')
  })

  it('넓힌 카드는 목록·설명·담긴 종목을 나란히 둔다', async () => {
    const user = userEvent.setup()
    await openPage()
    await user.click(expandButton())
    await screen.findByText('삼성전자')

    // 세 칸이 같은 격자 안에 형제로 선다. 아래로 이어 붙이면 넓힌 뜻이 없다.
    const body = document.querySelector('[data-slot="body"]') as HTMLElement
    const columns = [...body.children].map((node) => node.getAttribute('data-slot'))
    expect(columns).toEqual(['list', 'main', 'holdings'])
  })

  it('카드를 여닫아도 보던 대상과 담아 둔 것이 그대로다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    await user.click(expandButton())
    await user.click(expandButton())

    expect(detailName()).toBe('KODEX 자동차')
    expect(railCodes()).toEqual(['SEMICONDUCTOR', 'AUTO'])
  })

  it('넓힌 채 다른 대상으로 옮기면 그 크기를 유지한다', async () => {
    const user = userEvent.setup()
    await openPage()
    await user.click(expandButton())

    await user.click(row('AUTO'))

    // 같은 크기로 여럿을 견주는 중이다. 옮길 때마다 접히면 그 일을 방해한다.
    expect(cardExpanded()).toBe(true)
    expect(detailName()).toBe('KODEX 자동차')
  })

  it('보고 있는 대상 하나만 조회한다', async () => {
    const user = userEvent.setup()
    await openPage()
    await user.click(expandButton())

    // 목록에 있는 여섯을 한꺼번에 불러오지 않는다. 사용자가 보는 것은 언제나 하나다.
    await waitFor(() => expect(apiMocks.fetchInstrumentProfile).toHaveBeenCalledTimes(1))
    expect(apiMocks.fetchInstrumentProfile.mock.calls[0][0]).toBe('SEMICONDUCTOR')
  })

  it('화면을 다시 열어도 담아 둔 것이 그대로 남는다', async () => {
    const user = userEvent.setup()
    const first = await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')
    first.unmount()

    await openPage()

    expect(railCodes()).toEqual(['SEMICONDUCTOR', 'AUTO'])
  })

  it('탭 세션이 비어 있으면 아무것도 남지 않는다', async () => {
    await openPage()

    expect(railCodes()).toEqual([])
    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBeNull()
  })

  it('보관된 대상이 지금 목록에 없으면 알리고 대체를 자동으로 고르지 않는다 (006 FR-008)', async () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        mapVersion: 'SEM-2026-09-10-001',
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 500 },
          { code: 'GONE_SECTOR', weightTenths: 500 },
        ],
        weightsTouched: false,
      }),
    )

    await openPage()

    expect(railCodes()).toEqual(['SEMICONDUCTOR'])
    expect(
      screen.getByText(/투자 대상 1개가 지금 목록에 없어 선택에서 빠졌습니다/),
    ).toBeTruthy()
    // 대신 고를 대상을 화면이 정하지 않는다. 사용자가 직접 고른다.
    expect(screen.getByText(/대체할 대상을 직접 골라 주세요/)).toBeTruthy()
  })

  it('비중을 다루는 요소를 이 화면에 두지 않는다', async () => {
    const user = userEvent.setup()
    const { container } = await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')
    await user.click(expandButton())
    await screen.findByText('삼성전자')

    // "무엇을"과 "얼마나"를 가른다. 비중은 다음 단계가 소유한다.
    expect(container.querySelectorAll('input')).toHaveLength(0)
    expect(screen.queryByText(/동일하게 나누기/)).toBeNull()
    expect(screen.queryByText(/합계/)).toBeNull()
    expect(screen.queryByText(/고정하기/)).toBeNull()
  })
})

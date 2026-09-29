import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SectorSelectionPage } from './SectorSelectionPage'
import type { SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { SECTOR_SELECTION_STORAGE_KEY } from '@/diagnosis/selectionStorage'
import type { SectorCatalog, SectorSummary } from '@/types/sector'

/**
 * 다음 단계로 넘기는 값이 사용자가 고른 것과 한 글자도 다르지 않은지 본다(SC-013).
 *
 * **이 검사가 SC-013을 받는다.** quickstart에 SC-013 시나리오가 없어 사람 손 절차로 받을 자리가
 * 없기 때문이다(tasks.md 「SC-012와 SC-013에는 quickstart 시나리오가 없다」).
 *
 * 비중은 이 화면이 정하지 않지만 인계 페이로드의 항목이라 함께 실린다. 값을 정하는 자리는
 * 다음 화면이고, 여기서는 **고르고 빼는 동안 유지된 값이 그대로 실리는지**를 본다.
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
  listedOn = '2018-09-12',
): SectorSummary {
  return {
    code,
    name,
    classification,
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn,
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

/** 표시 순서는 반도체 → 방산 → 자동차 → 조선 → 철강 → 2차전지다. */
const catalog: SectorCatalog = {
  mapVersion: 'SEM-2026-09-10-001',
  isSample: false,
  sectors: [
    sector('BATTERY', '2차전지', 'THEME', '2018-09-12'),
    sector('STEEL', '철강', 'INDUSTRY', '2018-09-12'),
    sector('SHIPBUILDING', '조선', 'INDUSTRY', '2018-09-12'),
    sector('AUTO', '자동차', 'INDUSTRY', '2011-04-12'),
    sector('DEFENSE', '방산', 'INDUSTRY', '2018-09-12'),
    sector('SEMICONDUCTOR', '반도체', 'INDUSTRY', '2006-06-27'),
  ],
}

const loaded: SectorCatalogResult = { status: 'loaded', catalog }

/** 지금 경로를 화면에 드러내 다음 단계로 실제로 이동했는지 값으로 확인한다. */
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
          <Route path="/strategy" element={<p>전략 고르기</p>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  )

  await screen.findAllByRole('listitem')
  return result
}

type User = ReturnType<typeof userEvent.setup>

/** 카드 왼쪽 목록의 한 줄이다. 누르면 그 대상을 보면서 담는다. */
function row(code: string): HTMLButtonElement {
  return document.querySelector(
    `[data-slot="list"] [data-sector-code="${code}"] [data-slot="pill"]`,
  ) as HTMLButtonElement
}

async function add(user: User, ...codes: string[]) {
  for (const code of codes) await user.click(row(code))
}

function proceedButton(): HTMLButtonElement {
  return screen.getByRole('button', { name: 'STEP 02 - 전략 고르기' }) as HTMLButtonElement
}

/** 오른쪽 선택 요약에 담긴 코드를 순서대로 모은다. */
function railCodes(): string[] {
  return [...document.querySelectorAll('[data-slot="rail-item"]')].map(
    (node) => node.getAttribute('data-sector-code') ?? '',
  )
}

type HandoffPayload = {
  version: number
  mapVersion: string
  selected: { code: string; weightTenths: number }[]
  weightsTouched: boolean
}

function storedPayload(): HandoffPayload | null {
  const raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)
  return raw === null ? null : (JSON.parse(raw) as HandoffPayload)
}

function currentPath(): string {
  return screen.getByTestId('path').textContent ?? ''
}

describe('다음 단계 버튼과 003 인계', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 카드는 접힌 채로도 가격을 부른다. 그림 자체는 다른 검사가 본다.
    apiMocks.fetchPriceSeries.mockResolvedValue({
      status: 'notReady',
      message: '준비 중',
    })
    window.sessionStorage.clear()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'notReady',
      message: '준비 중',
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
  })

  it('아무것도 고르지 않았으면 다음 단계 버튼이 눌리지 않는다', async () => {
    await openPage()

    // 버튼을 감추지 않는다. 무엇을 더 해야 넘어갈 수 있는지가 안내와 함께 읽혀야 한다.
    expect(proceedButton().disabled).toBe(true)
    expect(screen.getByText(/아직 선택한 투자 대상이 없습니다/)).toBeTruthy()
  })

  it('하나뿐이면 넘어가지 못하고 몇 개가 필요한지 알린다 (FR-004)', async () => {
    const user = userEvent.setup()
    await openPage()

    await add(user, 'SEMICONDUCTOR')

    expect(proceedButton().disabled).toBe(true)
    expect(screen.getByText(/2개 이상 골라야 합니다/)).toBeTruthy()
  })

  it('두 개 이상 고르면 다음 단계로 넘어간다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    expect(proceedButton().disabled).toBe(false)
    await user.click(proceedButton())

    expect(currentPath()).toBe('/strategy')
  })

  it('SC-013. 고른 것이 표시 순서 그대로, 동일 비중으로 실린다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'AUTO', 'SEMICONDUCTOR', 'SHIPBUILDING')

    await user.click(proceedButton())

    // 잔여분은 표시 순서상 첫 대상에 몰아 합계가 정확히 1000이다.
    expect(storedPayload()?.selected).toEqual([
      { code: 'SEMICONDUCTOR', weightTenths: 334 },
      { code: 'AUTO', weightTenths: 333 },
      { code: 'SHIPBUILDING', weightTenths: 333 },
    ])
  })

  it('SC-013. 값을 만들 당시의 대응표 버전이 함께 실린다 (원칙 VIII)', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    await user.click(proceedButton())

    expect(storedPayload()?.mapVersion).toBe('SEM-2026-09-10-001')
  })

  it('선택 요약에서 뺀 대상은 넘기는 값에서도 빠진다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO', 'SHIPBUILDING')

    await user.click(screen.getByRole('button', { name: '자동차 빼기' }))
    expect(railCodes()).toEqual(['SEMICONDUCTOR', 'SHIPBUILDING'])

    await user.click(proceedButton())
    expect(storedPayload()?.selected.map((item) => item.code)).toEqual([
      'SEMICONDUCTOR',
      'SHIPBUILDING',
    ])
  })

  it('넘기는 값에 섹터 이름과 대표 ETF 이름을 싣지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    await user.click(proceedButton())

    // 003이 `GET /sectors`로 직접 받는다. 여기 실으면 대응표 버전이 바뀔 때 기준이 흐려진다.
    const raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY) ?? ''
    expect(raw).not.toContain('반도체')
    expect(raw).not.toContain('KODEX')
  })

  it('비중을 백분율 소수로 바꾸지 않는다. 004 계약으로의 변환은 003의 몫이다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    await user.click(proceedButton())

    for (const item of storedPayload()?.selected ?? []) {
      expect(Number.isInteger(item.weightTenths)).toBe(true)
    }
    expect(storedPayload()?.selected[0]?.weightTenths).toBe(500)
  })

  it('인계 수단은 보관 항목 하나다. URL에 값을 싣지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    await user.click(proceedButton())

    // 경로에 섹터 코드나 비중이 섞이면 공유된 링크가 '추천 비중'처럼 읽힌다(원칙 III).
    expect(currentPath()).toBe('/strategy')
    expect(currentPath()).not.toContain('SEMICONDUCTOR')
    expect(currentPath()).not.toContain('500')
  })

  it('보관이 막혀 넘길 수 없으면 다음 화면으로 보내지 않고 그 사실을 알린다 (원칙 VI)', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    /*
      저장소가 막힌 환경이다. 이 화면 안에서는 선택이 그대로 남지만 인계할 방법이 없다.

      `window.sessionStorage`의 인스턴스가 아니라 `Storage.prototype`을 바꾼다. jsdom의 저장소는
      인스턴스에 얹은 속성이 읽히지 않아, 인스턴스를 바꾸면 검사가 조용히 통과한다.
    */
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage is blocked')
    })

    await user.click(proceedButton())

    expect(currentPath()).toBe('/diagnosis/sectors')
    expect(screen.getByRole('alert').textContent).toMatch(/넘기지 못했습니다/)
    // 고른 것을 잃은 것은 아니다. 선택 요약은 그대로 있다.
    expect(railCodes()).toEqual(['SEMICONDUCTOR', 'AUTO'])
  })

  it('인계 실패 표시는 사용자가 선택을 고치면 사라진다', async () => {
    const user = userEvent.setup()
    await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO')

    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage is blocked')
    })
    await user.click(proceedButton())
    expect(screen.queryByRole('alert')).not.toBeNull()

    /*
      지난 한 번의 실패를 알리는 문장이 세션 내내 남으면, 지금 상태를 두고 하는 말처럼 읽힌다.
      사용자가 선택을 고치면 거둔다.
    */
    blocked.mockRestore()
    await add(user, 'SHIPBUILDING')

    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('시나리오 11. 화면에 계산식·자료 공급원·서버 설정 입력 항목이 없다 (FR-011, SC-005)', async () => {
    const user = userEvent.setup()
    const { container } = await openPage()
    await add(user, 'SEMICONDUCTOR', 'AUTO', 'SHIPBUILDING')

    /*
      고르는 화면에는 값을 치는 자리가 하나도 없다. 무엇을 담을지는 누르는 것으로 끝나고, 얼마를
      줄지는 다음 화면이 묻는다. 여기에 입력이 하나라도 생기면 SC-005가 세는 "0개"가 깨진다.
    */
    expect(container.querySelectorAll('input')).toHaveLength(0)
    expect(container.querySelectorAll('select')).toHaveLength(0)
    expect(container.querySelectorAll('textarea')).toHaveLength(0)
  })
})

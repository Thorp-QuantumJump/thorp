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
 * 다음 단계로 넘기는 값이 사용자가 마지막으로 입력한 값과 한 글자도 다르지 않은지 본다(SC-013).
 *
 * **이 검사가 SC-013을 받는다.** quickstart에 SC-013 시나리오가 없어 사람 손 절차로 받을 자리가
 * 없기 때문이다(tasks.md 「SC-012와 SC-013에는 quickstart 시나리오가 없다」).
 */

const apiMocks = vi.hoisted(() => ({ fetchSectorCatalog: vi.fn() }))

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

function checkbox(name: string): HTMLInputElement {
  return screen.getByRole('checkbox', { name }) as HTMLInputElement
}

function weightField(name: string): HTMLInputElement {
  return screen.getByRole('spinbutton', { name: `${name} 비중` }) as HTMLInputElement
}

async function select(user: User, ...names: string[]) {
  for (const name of names) await user.click(checkbox(name))
}

/** 붙여넣기처럼 값을 통째로 바꾼 뒤 탭으로 확정한다. */
async function replaceWeight(user: User, name: string, text: string) {
  await user.clear(weightField(name))
  await user.paste(text)
  await user.tab()
}

function proceedButton(): HTMLButtonElement {
  return screen.getByRole('button', { name: '전략 고르러 가기' }) as HTMLButtonElement
}

/** 화면에 보이는 비중을 표시 순서대로 모은다. 표기가 아니라 값을 본다. */
function shownWeights(): number[] {
  return screen.getAllByRole('spinbutton').map((input) => Number((input as HTMLInputElement).value))
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
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
  })

  it('아무것도 고르지 않았으면 다음 단계로 가는 수단 자체가 없다', async () => {
    await openPage()

    expect(screen.queryByRole('button', { name: '전략 고르러 가기' })).toBeNull()
  })

  it('섹터가 하나뿐이면 합계가 100%여도 넘어가지 못한다 (FR-004)', async () => {
    const user = userEvent.setup()
    await openPage()

    await select(user, '반도체')

    // 하나만 골라도 초깃값이 100%라 합계는 맞는다. 막는 것은 개수 조건이다.
    expect(shownWeights()).toEqual([100])
    expect(proceedButton().disabled).toBe(true)
  })

  it('합계가 맞지 않으면 넘어가지 않고 한 문장으로 알린다. 값을 자동으로 고치지 않는다 (FR-007)', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    await replaceWeight(user, '반도체', '40')

    expect(proceedButton().disabled).toBe(true)
    expect(screen.getByText(/합계를 100%로 맞추면/)).toBeTruthy()
    // 안내를 띄우면서 비중을 맞춰 버리면 SC-002 위반이다.
    expect(shownWeights()).toEqual([40, 50])
  })

  it('두 개 이상이고 합계가 100%면 다음 단계로 넘어간다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    expect(proceedButton().disabled).toBe(false)
    await user.click(proceedButton())

    expect(currentPath()).toBe('/strategy')
  })

  it('SC-013. 넘긴 값이 마지막 입력과 한 글자도 다르지 않다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')

    // 33.4 / 33.3 / 33.3에서 시작해 사람 손으로 다른 조합을 만든다.
    await replaceWeight(user, '반도체', '43.4')
    await replaceWeight(user, '자동차', '23.3')

    expect(shownWeights()).toEqual([43.4, 23.3, 33.3])
    await user.click(proceedButton())

    const payload = storedPayload()
    expect(payload?.selected).toEqual([
      { code: 'SEMICONDUCTOR', weightTenths: 434 },
      { code: 'AUTO', weightTenths: 233 },
      { code: 'SHIPBUILDING', weightTenths: 333 },
    ])
  })

  it('SC-013. 값을 만들 당시의 대응표 버전이 함께 실린다 (원칙 VIII)', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    await user.click(proceedButton())

    expect(storedPayload()?.mapVersion).toBe('SEM-2026-09-10-001')
  })

  it('SC-013. 0%인 섹터가 빠지지 않고 합계도 맞춰지지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')

    await replaceWeight(user, '조선', '0')
    await replaceWeight(user, '반도체', '50')
    await replaceWeight(user, '자동차', '50')

    expect(shownWeights()).toEqual([50, 50, 0])
    await user.click(proceedButton())

    const payload = storedPayload()
    // 0%인 섹터가 선택 목록에 그대로 남는다. 003이 선택 개수로 유효성 제약을 계산한다(SC-010).
    expect(payload?.selected.map((item) => item.code)).toEqual([
      'SEMICONDUCTOR',
      'AUTO',
      'SHIPBUILDING',
    ])
    expect(payload?.selected.at(-1)).toEqual({ code: 'SHIPBUILDING', weightTenths: 0 })
  })

  it('넘기는 값에 섹터 이름과 대표 ETF 이름을 싣지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    await user.click(proceedButton())

    // 003이 `GET /sectors`로 직접 받는다. 여기 실으면 대응표 버전이 바뀔 때 기준이 흐려진다.
    const raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY) ?? ''
    expect(raw).not.toContain('반도체')
    expect(raw).not.toContain('KODEX')
  })

  it('비중을 백분율 소수로 바꾸지 않는다. 004 계약으로의 변환은 003의 몫이다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    await user.click(proceedButton())

    for (const item of storedPayload()?.selected ?? []) {
      expect(Number.isInteger(item.weightTenths)).toBe(true)
    }
    expect(storedPayload()?.selected[0]?.weightTenths).toBe(500)
  })

  it('인계 수단은 보관 항목 하나다. URL에 값을 싣지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    await user.click(proceedButton())

    // 경로에 섹터 코드나 비중이 섞이면 공유된 링크가 '추천 비중'처럼 읽힌다(원칙 III).
    expect(currentPath()).toBe('/strategy')
    expect(currentPath()).not.toContain('SEMICONDUCTOR')
    expect(currentPath()).not.toContain('500')
  })

  it('보관이 막혀 넘길 수 없으면 다음 화면으로 보내지 않고 그 사실을 알린다 (원칙 VI)', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    /*
      저장소가 막힌 환경이다. 이 화면 안에서는 값이 그대로 남지만 인계할 방법이 없다.

      `window.sessionStorage`의 인스턴스가 아니라 `Storage.prototype`을 바꾼다. jsdom의 저장소는
      인스턴스에 얹은 속성이 읽히지 않아, 인스턴스를 바꾸면 검사가 조용히 통과한다.
    */
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage is blocked')
    })

    await user.click(proceedButton())

    expect(currentPath()).toBe('/diagnosis/sectors')
    expect(screen.getByRole('alert').textContent).toMatch(/넘기지 못했습니다/)
    // 값을 잃은 것은 아니다. 화면의 입력은 그대로 있다.
    expect(shownWeights()).toEqual([50, 50])
  })

  it('시나리오 11. 화면에 계산식·자료 공급원·서버 설정 입력 항목이 없다 (FR-011, SC-005)', async () => {
    const user = userEvent.setup()
    const { container } = await openPage()
    await select(user, '반도체', '자동차', '조선')

    /*
      이 화면이 두는 입력은 세 가지뿐이다. 섹터를 고르는 체크박스, 비중 슬라이더, 비중 숫자 칸.
      그 밖의 입력이 하나라도 생기면 SC-005가 세는 "0개"가 깨진다.
    */
    const allowed = new Set(['checkbox', 'range', 'number'])
    for (const input of container.querySelectorAll('input')) {
      expect(allowed.has(input.type)).toBe(true)
    }

    // 고를 목록이나 자유 입력을 두지 않는다. 자료 공급원·거리 함수·실험 식별자가 들어올 자리다.
    expect(container.querySelectorAll('select')).toHaveLength(0)
    expect(container.querySelectorAll('textarea')).toHaveLength(0)
  })
})

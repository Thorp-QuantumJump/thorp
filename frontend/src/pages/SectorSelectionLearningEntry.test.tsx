import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SectorSelectionPage } from './SectorSelectionPage'
import type { SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { LEARNING_EXAMPLE_STATE_KEY } from '@/diagnosis/learningExample'
import type { SectorCatalog, SectorSummary } from '@/types/sector'

/**
 * quickstart 시나리오 10이다. 배우기(013)가 밀어 넣는 예시가 기존 입력을 지키는지 본다.
 *
 * 013이 아직 없으므로 navigation state를 직접 실어 확인한다. 페이로드 형태는
 * `contracts/selection-handoff.md` 2절이다.
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

/** 표시 순서는 반도체 → 방산 → 자동차 → 조선 → 철강 → 2차전지다. */
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

type ExampleItem = { code: string; weightTenths: number }

/** 013이 실어 보내는 값이다. */
function exampleState(selected: ExampleItem[]) {
  return {
    [LEARNING_EXAMPLE_STATE_KEY]: {
      source: 'learning',
      topicId: 'equal-weight-basics',
      selected,
    },
  }
}

const 반도체_2차전지_예시 = [
  { code: 'SEMICONDUCTOR', weightTenths: 500 },
  { code: 'BATTERY', weightTenths: 500 },
]

async function openPage(state?: unknown) {
  apiMocks.fetchSectorCatalog.mockResolvedValue(loaded)

  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  const result = render(
    <MemoryRouter initialEntries={[{ pathname: '/diagnosis/sectors', state }]}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route path="/diagnosis/sectors" element={<SectorSelectionPage />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  )

  /*
    역할이 아니라 DOM으로 기다린다. 덮어쓰기 확인이 열려 있으면 그 바깥은 보조 기술에서 숨겨져
    `findAllByRole('listitem')`이 목록을 찾지 못한다. 목록이 그려졌는지만 보면 되는 자리다.
  */
  await waitFor(() => {
    expect(document.querySelectorAll('[data-sector-code]').length).toBeGreaterThan(0)
  })
  return result
}

type User = ReturnType<typeof userEvent.setup>

function checkbox(name: string): HTMLInputElement {
  return screen.getByRole('checkbox', { name }) as HTMLInputElement
}

async function select(user: User, ...names: string[]) {
  for (const name of names) await user.click(checkbox(name))
}

/** 고른 섹터를 표시 순서대로 모은다. */
function selectedNames(): string[] {
  return screen
    .getAllByRole('checkbox')
    .filter((box) => (box as HTMLInputElement).checked)
    .map((box) => box.getAttribute('aria-labelledby'))
    .map((id) => (id ? (document.getElementById(id)?.textContent ?? '') : ''))
}

/** 화면에 보이는 비중을 표시 순서대로 모은다. 표기가 아니라 값을 본다. */
function shownWeights(): number[] {
  return screen.getAllByRole('spinbutton').map((input) => Number((input as HTMLInputElement).value))
}

function overwriteDialog(): HTMLElement | null {
  return screen.queryByRole('alertdialog')
}

describe('배우기 예시 진입과 덮어쓰기 확인', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    window.sessionStorage.clear()
  })

  it('시나리오 10. 아무것도 입력하지 않았으면 묻지 않고 예시가 채워진다 (SC-011)', async () => {
    await openPage(exampleState(반도체_2차전지_예시))

    // SC-011이 세는 "입력이 없는데 확인을 물은 사례"가 0건이어야 한다.
    expect(overwriteDialog()).toBeNull()
    expect(selectedNames()).toEqual(['반도체', '2차전지'])
    expect(shownWeights()).toEqual([50, 50])
  })

  it('시나리오 10. 이미 고른 섹터가 있으면 덮어쓸지 먼저 묻는다', async () => {
    const user = userEvent.setup()
    await enterWithOwnInput(user)

    expect(overwriteDialog()).not.toBeNull()
  })

  it("시나리오 10. '그대로 두기'를 고르면 기존 값이 그대로 남는다", async () => {
    const user = userEvent.setup()
    await enterWithOwnInput(user)

    await user.click(screen.getByText('그대로 두기'))

    expect(overwriteDialog()).toBeNull()
    expect(selectedNames()).toEqual(['조선', '철강'])
    expect(shownWeights()).toEqual([50, 50])
  })

  it("시나리오 10. '예시로 바꾸기'를 고르면 예시 값으로 바뀐다", async () => {
    const user = userEvent.setup()
    await enterWithOwnInput(user)

    await user.click(screen.getByText('예시로 바꾸기'))

    expect(overwriteDialog()).toBeNull()
    expect(selectedNames()).toEqual(['반도체', '2차전지'])
    expect(shownWeights()).toEqual([50, 50])
  })

  it('시나리오 10. 답한 뒤에는 새로고침해도 확인이 다시 뜨지 않는다', async () => {
    const user = userEvent.setup()
    const entered = await enterWithOwnInput(user)
    await user.click(screen.getByText('예시로 바꾸기'))

    // navigation state를 한 번 읽고 지웠으므로, 같은 경로가 다시 떠도 물을 값이 없다.
    entered.unmount()
    await openPage()

    expect(overwriteDialog()).toBeNull()
    // 덮어쓰기로 정한 값은 세션에 남아 새로고침 뒤에도 그대로다.
    expect(selectedNames()).toEqual(['반도체', '2차전지'])
  })

  it('예시 합계가 100%가 아니어도 그대로 채우고 부족·초과 안내로 처리한다', async () => {
    await openPage(
      exampleState([
        { code: 'SEMICONDUCTOR', weightTenths: 300 },
        { code: 'BATTERY', weightTenths: 300 },
      ]),
    )

    // 자동으로 100%에 맞추면 원칙 VI 위반이다.
    expect(shownWeights()).toEqual([30, 30])
    expect(screen.getByText(/합계를 100%로 맞추면/)).toBeTruthy()
  })

  it('현재 목록에 없는 코드는 받지 않고 남은 섹터만 채운다', async () => {
    await openPage(
      exampleState([
        { code: 'SEMICONDUCTOR', weightTenths: 500 },
        { code: 'GONE_SECTOR', weightTenths: 500 },
      ]),
    )

    expect(selectedNames()).toEqual(['반도체'])
  })

  it('비중으로 쓸 수 없는 값이 실려 오면 그 섹터를 받지 않는다', async () => {
    await openPage(
      exampleState([
        { code: 'SEMICONDUCTOR', weightTenths: 500 },
        // 정수가 아니거나 범위를 벗어난 값은 저장소에서 읽는 값과 같은 검사로 걸러진다.
        { code: 'BATTERY', weightTenths: 1500 },
      ] as ExampleItem[]),
    )

    expect(selectedNames()).toEqual(['반도체'])
  })

  it('예시가 실려 있지 않으면 아무것도 채우지 않는다', async () => {
    await openPage()

    expect(overwriteDialog()).toBeNull()
    expect(selectedNames()).toEqual([])
  })
})

/**
 * 사용자가 직접 고른 값이 남은 상태에서 예시와 함께 화면을 다시 연다.
 *
 * 먼저 빈 화면에서 섹터를 골라 `sessionStorage`에 값을 남기고, 그 화면을 걷어낸 뒤 예시를 실어
 * 다시 연다. **화면을 걷어내는 까닭**은 두 화면이 문서에 함께 남으면 체크박스와 비중 칸이 두
 * 벌씩 잡혀 검사가 무엇을 보는지 흐려지기 때문이다.
 *
 * 진단 도중 배우기에 다녀오는 실제 경로와 같다(013 FR-013). 복원은 T012가 만든 세션 보관이 한다.
 */
async function enterWithOwnInput(user: User) {
  const first = await openPage()
  await select(user, '조선', '철강')
  first.unmount()

  return openPage(exampleState(반도체_2차전지_예시))
}

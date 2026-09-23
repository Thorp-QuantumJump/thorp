import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { SECTOR_SELECTION_STORAGE_KEY } from '@/diagnosis/selectionStorage'
import type { SectorCatalog, SectorSummary } from '@/types/sector'
import { SectorSelectionPage } from './SectorSelectionPage'

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

function checkbox(name: string): HTMLInputElement {
  return screen.getByRole('checkbox', { name }) as HTMLInputElement
}

function slider(name: string): HTMLInputElement {
  return screen.getByRole('slider', { name: `${name} 비중` }) as HTMLInputElement
}

function weightField(name: string): HTMLInputElement {
  return screen.getByRole('spinbutton', { name: `${name} 비중` }) as HTMLInputElement
}

/**
 * 화면에 보이는 비중을 표시 순서대로 모은다.
 *
 * 숫자로 읽어 비교하는 까닭은 `25`와 `25.0`이 숫자 칸에서 같은 값이고, React가 값이 같은 숫자
 * 칸의 표기를 굳이 바꾸지 않기 때문이다. 이 검사가 보려는 것은 표기가 아니라 값이다.
 */
function shownWeights(): number[] {
  return screen.getAllByRole('spinbutton').map((input) => Number((input as HTMLInputElement).value))
}

/** 합계와 남은 비중은 한 문장에 함께 있다. 문장 전체를 읽는다. */
function totalText(): string {
  const message = screen
    .getAllByRole('status')
    .find((node) => node.textContent?.includes('합계'))
  if (!message) throw new Error('합계 표시를 찾지 못했다')
  return message.textContent ?? ''
}

type User = ReturnType<typeof userEvent.setup>

async function select(user: User, ...names: string[]) {
  for (const name of names) {
    await user.click(checkbox(name))
  }
}

/**
 * 붙여넣기처럼 값을 통째로 바꾼다. `step`을 우회하는 경로와 같은 길이다.
 *
 * 기본은 탭으로 확정까지 한다. 받지 않은 입력을 확인하는 검사만 확정 전 상태를 본다.
 */
async function replaceWeight(user: User, name: string, text: string, settle = true) {
  await user.clear(weightField(name))
  await user.paste(text)
  if (settle) await user.tab()
}

describe('섹터 선택 화면의 비중 규칙', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    window.sessionStorage.clear()
  })

  it('고르기 전에는 비중 입력 칸도 합계도 없다', async () => {
    await openPage()

    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
    expect(screen.queryByText(/합계/)).toBeNull()
    expect(screen.getByText(/선택하면 카드 안에서 비중을 바로 조절/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '전략 고르러 가기' })).toBeNull()
    // 아직 아무것도 고르지 않았으므로 두 개 이상 안내도 띄우지 않는다.
    expect(screen.queryByText(/두 개 이상/)).toBeNull()
  })

  it('시나리오 1. 섹터 두 개를 고르면 각 섹터에 50.0%가 채워진다', async () => {
    const user = userEvent.setup()
    await openPage()

    await select(user, '반도체', '자동차')

    expect(shownWeights()).toEqual([50, 50])
    expect(totalText()).toContain('100.0')
    expect(screen.getByText(/투자 권고가 아닙니다/)).toBeTruthy()
    expect(screen.queryByText(/선택하면 카드 안에서 비중을 바로 조절/)).toBeNull()
    expect(screen.getByRole('button', { name: '전략 고르러 가기' }).hasAttribute('disabled')).toBe(
      false,
    )
  })

  it('시나리오 2. 섹터 3개를 고르면 33.4 / 33.3 / 33.3이 보이고 예시라는 표시가 함께 보인다', async () => {
    const user = userEvent.setup()
    await openPage()

    await select(user, '자동차', '2차전지', '반도체')

    // 고른 순서와 무관하게 표시 순서대로 잔여분이 첫 번째에 붙는다.
    expect(shownWeights()).toEqual([33.4, 33.3, 33.3])
    expect(screen.getByText(/투자 권고가 아닙니다/)).toBeTruthy()
  })

  it('섹터를 하나만 고르면 두 개 이상 필요하다는 안내를 보인다', async () => {
    const user = userEvent.setup()
    await openPage()

    await select(user, '반도체')

    expect(screen.getByText(/두 개 이상/)).toBeTruthy()
    /*
      M4가 다음 단계 버튼을 만든 뒤에도 하나만 고른 상태로는 넘어가지 못한다. 버튼을 숨기지 않고
      눌리지 않게 두는 까닭은, 무엇을 더 해야 다음으로 갈 수 있는지가 위 안내와 함께 읽혀야 하기
      때문이다. 넘어가는 다른 수단은 두지 않는다.
    */
    expect(
      (screen.getByRole('button', { name: '전략 고르러 가기' }) as HTMLButtonElement).disabled,
    ).toBe(true)
    // 버튼을 우회해 다음 단계로 바로 가는 링크를 두지 않는다. 머리 영역의 메뉴는 이 화면의 것이 아니다.
    expect(
      within(screen.getByRole('main')).queryByRole('link', { name: /전략|다음/ }),
    ).toBeNull()

    await select(user, '자동차')
    expect(screen.queryByText(/두 개 이상/)).toBeNull()
  })

  /*
    아래 두 검사는 팀 결정으로 뜻이 뒤집혔다. 예전에는 한 섹터를 올리면 나머지가 그대로 있어
    초과·부족이 보이는 것이 옳았고(SC-002, FR-007), 지금은 나머지가 비율대로 따라 움직여
    합계가 늘 100%다.
  */
  it('시나리오 3. 한 섹터를 올리면 나머지가 비율대로 내려가 합계가 100%로 남는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')

    await replaceWeight(user, '반도체', '43.4')

    // 33.3 : 33.3 이던 둘이 같은 비율로 56.6 을 나눠 갖는다.
    expect(shownWeights()).toEqual([43.4, 28.3, 28.3])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)
  })

  it('시나리오 3. 한 섹터를 내려도 나머지가 채워 합계가 100%로 남는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    await replaceWeight(user, '자동차', '40')

    expect(shownWeights()).toEqual([60, 40])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)
  })

  it('시나리오 3. 소수 둘째 자리는 반올림되어 들어가지 않고 그 자리에 안내가 보인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')

    await replaceWeight(user, '반도체', '33.45', false)

    expect(screen.getByRole('alert')).toBeTruthy()
    // 334도 335도 들어가지 않는다. 합계도 그대로다.
    expect(totalText()).toContain('100.0')
  })

  it('시나리오 3. 동일하게 나누기를 누르면 합계가 정확히 100%로 돌아온다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')
    await replaceWeight(user, '반도체', '43.4')
    expect(shownWeights()).toEqual([43.4, 28.3, 28.3])

    await user.click(screen.getByRole('button', { name: '동일하게 나누기' }))

    expect(shownWeights()).toEqual([33.4, 33.3, 33.3])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)
  })

  it('시나리오 4. 고친 뒤 섹터를 더하고 빼는 여섯 단계를 그대로 밟는다', async () => {
    const user = userEvent.setup()
    await openPage()

    // 1. 섹터 3개 선택 (아직 안 고침)
    await select(user, '반도체', '자동차', '조선')
    expect(shownWeights()).toEqual([33.4, 33.3, 33.3])

    // 2. 섹터 1개 추가 (아직 안 고침) → 전체가 25.0 × 4로 다시 채워진다
    await select(user, '방산')
    expect(shownWeights()).toEqual([25, 25, 25, 25])

    // 3. 한 섹터를 40.0으로 수정 → 나머지 셋이 25.0 씩에서 20.0 씩으로 내려간다. 합계 100.0
    await replaceWeight(user, '자동차', '40')
    expect(shownWeights()).toEqual([20, 20, 40, 20])
    expect(totalText()).toContain('100.0')

    // 4. 섹터 1개 추가 → 기존 네 개의 값이 그대로이고 새 섹터만 0.0. 합계는 그대로 100.0
    await select(user, '철강')
    expect(shownWeights()).toEqual([20, 20, 40, 20, 0])

    // 5. 섹터 1개 해제 → 남은 넷이 비율대로 늘어 100%를 되찾는다. 잔여 1 은 표시 순서 첫 자리로.
    await user.click(checkbox('자동차'))
    expect(shownWeights()).toEqual([33.4, 33.3, 33.3, 0])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)

    // 6. '동일하게 나누기' → 전체가 동일 비중
    await user.click(screen.getByRole('button', { name: '동일하게 나누기' }))
    expect(shownWeights()).toEqual([25, 25, 25, 25])

    // 이후 섹터를 더하면 다시 전체가 동일 비중으로 채워진다(research.md 결정 10).
    await select(user, '자동차')
    expect(shownWeights()).toEqual([20, 20, 20, 20, 20])
  })

  it('시나리오 5. 0%를 입력해도 값이 남고 그 섹터가 목록에서 빠지지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')

    await replaceWeight(user, '조선', '0')

    expect(Number(weightField('조선').value)).toBe(0)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(checkbox('조선').checked).toBe(true)

    // 보관된 값에도 0%인 섹터가 그대로 남는다.
    const stored = JSON.parse(
      window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY) ?? '{}',
    ) as { selected: { code: string; weightTenths: number }[] }
    expect(stored.selected).toContainEqual({ code: 'SHIPBUILDING', weightTenths: 0 })
  })

  it('시나리오 8. 화면을 다시 열어도 고른 섹터와 비중이 그대로 남는다', async () => {
    const user = userEvent.setup()
    const { unmount } = await openPage()
    await select(user, '반도체', '자동차')
    await replaceWeight(user, '반도체', '60')
    await replaceWeight(user, '자동차', '40')
    unmount()

    await openPage()

    expect(checkbox('반도체').checked).toBe(true)
    expect(checkbox('자동차').checked).toBe(true)
    expect(shownWeights()).toEqual([60, 40])
    expect(totalText()).toContain('100.0')
  })

  it('시나리오 8. 탭 세션이 비어 있으면 아무것도 남지 않는다', async () => {
    // 새 탭은 빈 `sessionStorage`에서 시작한다. 같은 조건을 만들어 확인한다.
    window.sessionStorage.clear()
    await openPage()

    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
  })

  it('보관된 섹터가 지금 목록에 없으면 006 문장으로 알리고 대체 섹터를 자동으로 고르지 않는다(006 FR-008)', async () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        mapVersion: 'SEM-2026-09-10-001',
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 500 },
          { code: 'GONE', weightTenths: 500 },
        ],
        weightsTouched: true,
      }),
    )

    await openPage()

    // 조용히 버리지 않는다. 문장은 006이 정했다(T031): 대체 섹터는 사용자가 아래 목록에서 직접 고른다.
    expect(
      screen.getByText('이전에 골랐던 섹터 1개가 지금 목록에 없어 선택에서 빠졌습니다. 아래 목록에서 대체 섹터를 직접 골라 주세요.'),
    ).toBeTruthy()
    expect(checkbox('반도체').checked).toBe(true)
    // 빠진 자리를 다른 섹터로 채우지 않는다 — 자동으로 바꾸면 사용자가 모르는 조건으로 분석하게 된다.
    expect(checkbox('2차전지').checked).toBe(false)
    expect(checkbox('자동차').checked).toBe(false)
  })

  it('슬라이더를 움직이면 나머지가 비율대로 따라 움직인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')

    // 팀 결정으로 "하나를 올리면 나머지가 줄어드는" 방식을 쓴다(원칙 VI·SC-002 와 어긋난다).
    fireEvent.change(slider('반도체'), { target: { value: '600' } })

    expect(shownWeights()).toEqual([60, 20, 20])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)

    // 반대로 내리면 나머지가 같은 비율로 늘어난다.
    fireEvent.change(slider('반도체'), { target: { value: '100' } })

    expect(shownWeights()).toEqual([10, 45, 45])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)
  })

  it('슬라이더와 숫자 칸이 같은 값을 가리킨다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    fireEvent.change(slider('반도체'), { target: { value: '625' } })
    expect(Number(weightField('반도체').value)).toBe(62.5)

    await replaceWeight(user, '자동차', '37.5')
    expect(slider('자동차').value).toBe('375')
  })

  it('슬라이더로 만든 값도 0.1%p 단위 정수로 보관된다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    fireEvent.change(slider('반도체'), { target: { value: '625' } })
    fireEvent.change(slider('자동차'), { target: { value: '375' } })

    const stored = JSON.parse(
      window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY) ?? '{}',
    ) as { selected: { code: string; weightTenths: number }[] }
    // 슬라이더 때문에 반올림이 생기면 안 된다. 합계 판정도 정수 비교 그대로다.
    expect(stored.selected).toEqual([
      { code: 'SEMICONDUCTOR', weightTenths: 625 },
      { code: 'AUTO', weightTenths: 375 },
    ])
    expect(totalText()).toContain('100.0')
    expect(totalText()).not.toMatch(/부족|초과/)
  })

  it('합계를 숫자만이 아니라 막대로도 보인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    const bar = () => document.querySelector('[data-slot="weight-total-bar"]')
    expect(bar()?.getAttribute('data-state')).toBe('balanced')

    /*
      재배분이 합계를 100%로 붙들고 있어 슬라이더로는 short·over 가 나오지 않는다. 두 상태는
      보관값 복원과 003 인계처럼 밖에서 들어온 값에만 남는다.
    */
    fireEvent.change(slider('반도체'), { target: { value: '100' } })
    expect(bar()?.getAttribute('data-state')).toBe('balanced')

    fireEvent.change(slider('반도체'), { target: { value: '900' } })
    expect(bar()?.getAttribute('data-state')).toBe('balanced')

    // 같은 사실을 문장이 이미 읽어 주므로 막대는 보조 기술에 두 번 읽히지 않는다.
    expect(bar()?.getAttribute('aria-hidden')).toBe('true')
  })

  it('고른 항목과 고르지 않은 항목이 화면에서 구분된다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체')

    const items = screen.getAllByRole('listitem')
    const selectedItem = items.find((item) => item.getAttribute('data-sector-code') === 'SEMICONDUCTOR')
    const otherItem = items.find((item) => item.getAttribute('data-sector-code') === 'AUTO')

    expect(selectedItem?.getAttribute('data-selected')).toBe('true')
    expect(otherItem?.getAttribute('data-selected')).toBe('false')
  })

  it('합계 변화가 보조 기술에 전달되는 자리에 놓인다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    const statuses = screen.getAllByRole('status')
    const total = statuses.find((node) => node.textContent?.includes('합계'))
    expect(total).toBeTruthy()
    expect(total?.getAttribute('aria-live')).toBe('polite')
  })

  it('비중을 건드리는 버튼은 동일하게 나누기 하나뿐이다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')
    await replaceWeight(user, '반도체', '60')

    /*
      값을 바꾸는 수단은 사용자가 스스로 누르는 '동일하게 나누기' 하나뿐이다. 누르기 전에는 값이
      바뀌지 않는다.

      목록 항목 안의 버튼은 세지 않는다. M5의 대표 상품 정보 펼침은 항목마다 하나씩 있고 비중을
      건드리지 않으므로, 그것까지 세면 이 검사가 무엇을 지키는지 흐려진다. M4가 더한 '전략 고르러
      가기'는 값을 손대지 않고 넘기기만 하므로 함께 둔다. 섹터마다 붙는 '고정하기'도 값을 바꾸지
      않고 재배분에서 빼 두기만 하므로 같은 까닭으로 세지 않는다 — 아래에서 따로 확인한다.
    */
    const buttons = within(screen.getByRole('main'))
      .getAllByRole('button')
      .filter(
        (button) =>
          button.closest('[data-slot="profile"]') === null &&
          button.getAttribute('data-slot') !== 'weight-lock',
      )
    expect(buttons.map((button) => button.textContent)).toEqual([
      '동일하게 나누기',
      '전략 고르러 가기',
    ])
    // 재배분은 입력을 받는 순간 일어나고, 그 밖에 값을 바꾸는 버튼은 늘어나지 않았다.
    expect(shownWeights()).toEqual([60, 40])

    // 고정을 걸어도 값은 그대로다. 고정은 다음 재배분에서 빼 두겠다는 표시일 뿐이다.
    await user.click(screen.getAllByRole('button', { name: /비중 고정/ })[0])
    expect(shownWeights()).toEqual([60, 40])
  })

  it('고정한 섹터는 다른 섹터를 움직여도 값이 그대로다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차', '조선')
    expect(shownWeights()).toEqual([33.4, 33.3, 33.3])

    // 자동차를 고정해 두고 반도체를 올리면, 줄어드는 것은 조선뿐이다.
    await user.click(screen.getByRole('button', { name: '자동차 비중 고정' }))
    await replaceWeight(user, '반도체', '50')

    expect(shownWeights()).toEqual([50, 33.3, 16.7])
    expect(totalText()).toContain('100.0')

    // 풀면 다시 재배분에 들어온다.
    await user.click(screen.getByRole('button', { name: '자동차 비중 고정' }))
    await replaceWeight(user, '반도체', '60')

    expect(shownWeights()).toEqual([60, 26.6, 13.4])
    expect(totalText()).toContain('100.0')
  })

  it('계산식·자료 공급원·거리 함수·실험 식별자·서버 설정 입력 항목을 두지 않는다', async () => {
    const user = userEvent.setup()
    await openPage()
    await select(user, '반도체', '자동차')

    const main = screen.getByRole('main')
    // 이 화면의 입력은 섹터 체크박스와 비중 칸뿐이다(FR-011).
    expect(within(main).queryAllByRole('combobox')).toHaveLength(0)
    expect(within(main).queryAllByRole('textbox')).toHaveLength(0)
    expect(within(main).queryAllByRole('radio')).toHaveLength(0)
    expect(within(main).getAllByRole('spinbutton')).toHaveLength(2)
  })
})

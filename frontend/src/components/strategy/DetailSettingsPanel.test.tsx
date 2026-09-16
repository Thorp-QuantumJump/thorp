import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useStrategySettings } from '@/hooks/useStrategySettings'
import {
  plainStrategy,
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
} from '@/test/strategyRegistryFixtures'
import type { StrategyView } from '@/types/strategyRegistry'
import { DetailSettingsPanel } from './DetailSettingsPanel'
import type { TargetWeightRow } from './ReadOnlyTargetWeights'
import { SettingsStatusBadge } from './SettingsStatusBadge'

/**
 * 상세 설정 패널(T056)을 실제 설정 훅과 함께 돌린다(FR-003·FR-006·FR-008·FR-012, quickstart 5-1·6).
 *
 * <p><b>섹터 수를 여섯으로 둔다.</b> 픽스처의 규칙은 "허용 이탈 폭 × 섹터 수 < 100이면 위반"이라,
 * 기본값 20에서 섹터 여섯이면 120으로 통과하고 15로 바꾸면 90으로 위반한다. 기본 설정이 이미 위반인
 * 조건에서는 "바꾸면 생기고 복원하면 사라진다"를 볼 수 없다.
 */

const UNIVERSE_SIZE = 6

const weights: TargetWeightRow[] = [
  { sectorId: 'SEC-SYN-A', name: '합성 섹터 가', weightTenths: 600 },
  { sectorId: 'SEC-SYN-B', name: '합성 섹터 나', weightTenths: 400 },
]

function Harness({ strategy }: { strategy: StrategyView }) {
  const settings = useStrategySettings(registryFixture, UNIVERSE_SIZE)
  const { ready, select } = settings

  useEffect(() => {
    if (ready) select(strategy.strategyId)
  }, [ready, select, strategy.strategyId])

  if (!settings.selectedStrategy || !settings.values) return null
  const id = settings.selectedStrategy.strategyId

  return (
    <>
      <SettingsStatusBadge isDefault={settings.isDefault(id)} />
      <DetailSettingsPanel
        strategy={settings.selectedStrategy}
        values={settings.values}
        universeSize={UNIVERSE_SIZE}
        targetWeights={weights}
        onChange={(axisName, value) => settings.setValue(id, axisName, value)}
        onResetAxis={(axisName) => settings.resetAxis(id, axisName)}
        onResetAll={() => settings.resetAll(id)}
      />
    </>
  )
}

async function renderOpened(strategy: StrategyView) {
  const user = userEvent.setup()
  render(
    <MemoryRouter>
      <Harness strategy={strategy} />
    </MemoryRouter>,
  )
  const toggle = await screen.findByRole('button', { name: '상세 설정' })
  expect(toggle.getAttribute('aria-expanded')).toBe('false')
  await user.click(toggle)
  expect(toggle.getAttribute('aria-expanded')).toBe('true')
  return user
}

let fetchSpy: ReturnType<typeof vi.spyOn>
let xhrSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  window.sessionStorage.clear()
  fetchSpy = vi.spyOn(globalThis, 'fetch')
  xhrSpy = vi.spyOn(XMLHttpRequest.prototype, 'open')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('DetailSettingsPanel', () => {
  it('접힌 채로 시작해 설정 항목을 그리지 않는다', async () => {
    render(
      <MemoryRouter>
        <Harness strategy={selectableStrategy} />
      </MemoryRouter>,
    )
    await screen.findByRole('button', { name: '상세 설정' })

    for (const axis of selectableStrategy.axes) {
      expect(screen.queryByText(axis.label)).toBeNull()
    }
  })

  it('선택한 전략의 축만 그리고 다른 전략의 축은 그리지 않는다', async () => {
    await renderOpened(selectableStrategy)

    for (const axis of selectableStrategy.axes) {
      expect(screen.getByText(axis.label)).not.toBeNull()
    }
    for (const other of [unselectableStrategy, plainStrategy]) {
      for (const axis of other.axes) expect(screen.queryByText(axis.label)).toBeNull()
    }
    // 기본값은 추천이 아니라는 문장(FR-004)
    expect(screen.getByText(/편의용 출발점이며 최적값이나 투자 권고가 아닙니다/)).not.toBeNull()
  })

  it('사용자 비중 전략은 비중을 읽기 전용으로 보이고 앞 단계로 가는 연결을 둔다', async () => {
    await renderOpened(unselectableStrategy)

    const table = screen.getByRole('table')
    expect(within(table).getByText('합성 섹터 가')).not.toBeNull()
    expect(within(table).queryByRole('textbox')).toBeNull()
    expect(within(table).queryByRole('slider')).toBeNull()

    const link = screen.getByRole('link', { name: '섹터·비중 단계로 돌아가세요' })
    expect(link.getAttribute('href')).toBe('/diagnosis/sectors')
    expect(screen.queryByText(/계산 결과이며 입력한 비중과 다릅니다/)).toBeNull()
  })

  it('자동 배분 전략은 출력 비중이 계산 결과라는 설명을 보이고 비중 표를 그리지 않는다', async () => {
    await renderOpened(selectableStrategy)

    expect(screen.getByText('출력 비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다.')).not.toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('동일 비중 전략은 비중 표도 자동 배분 설명도 보이지 않는다', async () => {
    await renderOpened(plainStrategy)

    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText(/계산 결과이며 입력한 비중과 다릅니다/)).toBeNull()
  })

  it('규칙을 어기는 값은 즉시 등록표 문구로 알리고, 모두 복원하면 사라지며 서버에 묻지 않는다', async () => {
    const user = await renderOpened(selectableStrategy)
    const rule = selectableStrategy.validationRules[0]
    const band = screen.getByRole('textbox', { name: /허용 이탈 폭/ })

    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('status').textContent).toContain('기본 설정')

    await user.clear(band)
    await user.type(band, '15')

    // 15 × 6 = 90 < 100 — 범위 안이지만 규칙 위반이다(quickstart 5-1).
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toBe(rule.detail)
    expect(screen.getByRole('status').textContent).toContain('설정을 바꿈')

    await user.click(screen.getByRole('button', { name: '모두 기본값 복원' }))

    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('status').textContent).toContain('기본 설정')
    expect((band as HTMLInputElement).value).toBe(String(20))

    expect(fetchSpy).not.toHaveBeenCalled()
    expect(xhrSpy).not.toHaveBeenCalled()
  })

  it('범위 밖 값은 상태에 들어가지 않아 기본 설정 표시가 그대로다', async () => {
    const user = await renderOpened(selectableStrategy)
    const band = screen.getByRole('textbox', { name: /허용 이탈 폭/ })

    await user.clear(band)
    await user.type(band, '55')

    expect(screen.getByText(/허용 범위 밖입니다/)).not.toBeNull()
    expect(screen.getByRole('status').textContent).toContain('기본 설정')
  })

  it('패널을 접어도 규칙 위반 안내는 남는다', async () => {
    const user = await renderOpened(selectableStrategy)
    const band = screen.getByRole('textbox', { name: /허용 이탈 폭/ })

    await user.clear(band)
    await user.type(band, '15')
    await user.click(screen.getByRole('button', { name: '상세 설정' }))

    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('alert').textContent).toBe(selectableStrategy.validationRules[0].detail)
  })

  it('목록형 항목을 바꾸면 기본 설정 표시가 바뀌고 항목별 복원으로 돌아온다', async () => {
    const user = await renderOpened(selectableStrategy)

    await user.click(screen.getByRole('radio', { name: '90거래일' }))
    expect(screen.getByRole('status').textContent).toContain('설정을 바꿈')

    await user.click(screen.getByRole('button', { name: '표류 관측 창 기본값 복원' }))
    expect(screen.getByRole('status').textContent).toContain('기본 설정')
    expect((screen.getByRole('radio', { name: '45거래일' }) as HTMLInputElement).checked).toBe(true)
  })
})

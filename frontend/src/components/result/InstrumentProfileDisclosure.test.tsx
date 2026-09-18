import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SectorCatalogState } from '@/hooks/useSectorCatalogState'
import {
  resultDetailCompleted,
  resultInstrumentProfile,
  resultSectorCatalog,
} from '@/test/resultFixtures'
import { InstrumentProfileDisclosure } from './InstrumentProfileDisclosure'

/** 005 T069. FR-007·R-10: 001 대표 상품 정보를 상세 패널 안에서 그대로 펼친다. */

const apiMocks = vi.hoisted(() => ({ fetchInstrumentProfile: vi.fn() }))

vi.mock('@/api/sectors', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sectors')>()
  return { ...actual, ...apiMocks }
})

const items = resultDetailCompleted.config.items
const loaded: SectorCatalogState = { status: 'loaded', catalog: resultSectorCatalog }

describe('InstrumentProfileDisclosure', () => {
  beforeEach(() => {
    apiMocks.fetchInstrumentProfile.mockReset()
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'loaded',
      profile: resultInstrumentProfile,
    })
  })

  it('섹터 후보마다 접힌 항목을 두고, 열기 전에는 조회하지 않는다', () => {
    render(<InstrumentProfileDisclosure items={items} catalog={loaded} />)

    const toggles = screen.getAllByRole('button')
    expect(toggles.map((toggle) => toggle.textContent)).toEqual([
      '항구 · 합성 항구 ETF',
      '등대 · 합성 등대 ETF',
      '조선소 · 합성 조선소 ETF',
    ])
    expect(toggles.every((toggle) => toggle.getAttribute('aria-expanded') === 'false')).toBe(true)
    expect(apiMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
  })

  it('연 항목만 조회하고 001 응답 문구를 그대로 보인다', async () => {
    const user = userEvent.setup()
    render(<InstrumentProfileDisclosure items={items} catalog={loaded} />)

    await user.click(screen.getByRole('button', { name: '항구 · 합성 항구 ETF' }))

    expect(await screen.findByText(resultInstrumentProfile.indexMethodologySummary)).not.toBeNull()
    expect(screen.getByText(resultInstrumentProfile.survivorshipNote)).not.toBeNull()
    expect(screen.getByText(/시험용 출처 표기/)).not.toBeNull()
    expect(screen.getByText('갈매기 조선')).not.toBeNull()

    expect(apiMocks.fetchInstrumentProfile).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchInstrumentProfile.mock.calls[0][0]).toBe('HARBOR')
    // 다른 섹터는 접힌 채다.
    expect(
      screen.getByRole('button', { name: '등대 · 합성 등대 ETF' }).getAttribute('aria-expanded'),
    ).toBe('false')
  })

  it('조회에 실패하면 문장을 지어내지 않고 불러오지 못했다고 알린다', async () => {
    apiMocks.fetchInstrumentProfile.mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '연결 실패',
      retryable: true,
    })
    const user = userEvent.setup()
    render(<InstrumentProfileDisclosure items={items} catalog={loaded} />)

    await user.click(screen.getByRole('button', { name: '등대 · 합성 등대 ETF' }))

    expect(await screen.findByText(/불러오지 못했습니다/)).not.toBeNull()
    expect(screen.queryByText(resultInstrumentProfile.survivorshipNote)).toBeNull()
  })

  it('Esc로 닫으면 초점이 여는 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    render(<InstrumentProfileDisclosure items={items} catalog={loaded} />)
    const toggle = screen.getByRole('button', { name: '항구 · 합성 항구 ETF' })

    await user.click(toggle)
    await screen.findByText(resultInstrumentProfile.survivorshipNote)
    await user.keyboard('{Escape}')

    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(toggle)
  })

  it('지금 대표 ETF가 결과가 쓴 종목과 다르면 다른 상품의 정보를 펼치지 않는다', () => {
    const changed = {
      ...resultSectorCatalog,
      sectors: resultSectorCatalog.sectors.map((sector) =>
        sector.code === 'HARBOR'
          ? {
              ...sector,
              representativeEtf: { ...sector.representativeEtf, instrumentId: '900099.XKRX', name: '새 항구 ETF' },
            }
          : sector,
      ),
    }

    render(<InstrumentProfileDisclosure items={items} catalog={{ status: 'loaded', catalog: changed }} />)

    expect(screen.queryByRole('button', { name: /항구/ })).toBeNull()
    expect(screen.getByText(/이 결과가 쓴 상품\(900001\.XKRX\)과 지금의 대표 ETF\(새 항구 ETF\)가 달라/)).not.toBeNull()
  })

  it('지금 목록에 없는 섹터는 코드와 함께 볼 수 없다고 알린다', () => {
    const without = {
      ...resultSectorCatalog,
      sectors: resultSectorCatalog.sectors.filter((sector) => sector.code !== 'DOCKYARD'),
    }

    render(<InstrumentProfileDisclosure items={items} catalog={{ status: 'loaded', catalog: without }} />)

    expect(screen.getByText('DOCKYARD')).not.toBeNull()
    expect(screen.getByText(/지금 섹터 목록에 없는 섹터라/)).not.toBeNull()
  })

  it('섹터 목록을 받지 못하면 사유를 보이고, 달라질 수 있을 때만 다시 불러오기를 준다', async () => {
    const retry = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <InstrumentProfileDisclosure
        items={items}
        catalog={{ status: 'unavailable', message: '섹터 목록을 불러오지 못했습니다.', retry }}
      />,
    )

    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    expect(retry).toHaveBeenCalledTimes(1)

    rerender(
      <InstrumentProfileDisclosure
        items={items}
        catalog={{ status: 'unavailable', message: '섹터 목록이 아직 준비되지 않았습니다.' }}
      />,
    )
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })
})

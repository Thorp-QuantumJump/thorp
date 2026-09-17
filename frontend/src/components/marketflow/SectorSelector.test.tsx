import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchSectorCatalog } from '@/api/sectors'
import { MARKET_FLOW_SECTOR_KEY } from '@/marketflow/sectorLink'
import type { SectorSummary } from '@/types/sector'
import { SectorSelector } from './SectorSelector'

vi.mock('@/api/sectors', () => ({ fetchSectorCatalog: vi.fn() }))

function sector(code: string, name: string): SectorSummary {
  return {
    code,
    name,
    classification: 'INDUSTRY',
    representativeEtf: {
      instrumentId: `${code}-ETF`,
      name: `${name} ETF`,
      listedOn: '2015-03-02',
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const sectors = [sector('SEMICONDUCTOR', '반도체'), sector('BANK', '은행')]

function mockCatalog(sectorList: SectorSummary[] = sectors) {
  vi.mocked(fetchSectorCatalog).mockResolvedValue({
    status: 'loaded',
    catalog: { mapVersion: 'map-v1', isSample: false, sectors: sectorList },
  })
}

describe('SectorSelector', () => {
  afterEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
  })

  it('고른 섹터를 알리고 세션에 남긴다', async () => {
    mockCatalog()
    const onSelectionChange = vi.fn()
    render(<SectorSelector onSelectionChange={onSelectionChange} />)

    fireEvent.click(await screen.findByRole('button', { name: '반도체' }))

    expect(onSelectionChange).toHaveBeenLastCalledWith('SEMICONDUCTOR')
    expect(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY)).toBe('SEMICONDUCTOR')
  })

  it('같은 섹터를 다시 누르면 선택이 풀린다', async () => {
    mockCatalog()
    const onSelectionChange = vi.fn()
    render(<SectorSelector onSelectionChange={onSelectionChange} />)
    const chip = await screen.findByRole('button', { name: '반도체' })

    fireEvent.click(chip)
    fireEvent.click(chip)

    expect(onSelectionChange).toHaveBeenLastCalledWith(null)
    expect(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY)).toBeNull()
  })

  // FR-016: 새로고침과 다른 메뉴 왕복에는 남는다. 리마운트가 그 상황이다.
  it('세션에 남은 선택을 다시 열 때 복원한다', async () => {
    mockCatalog()
    window.sessionStorage.setItem(MARKET_FLOW_SECTOR_KEY, 'BANK')
    const onSelectionChange = vi.fn()
    render(<SectorSelector onSelectionChange={onSelectionChange} />)

    const chip = await screen.findByRole('button', { name: '은행' })
    expect(chip.getAttribute('aria-pressed')).toBe('true')
    await waitFor(() => expect(onSelectionChange).toHaveBeenCalledWith('BANK'))
  })

  // FR-016: 탭을 닫으면 풀린다. sessionStorage가 빈 상태가 그 상황이다.
  it('세션이 비어 있으면 선택 없이 시작한다', async () => {
    mockCatalog()
    const onSelectionChange = vi.fn()
    render(<SectorSelector onSelectionChange={onSelectionChange} />)

    await screen.findByRole('button', { name: '반도체' })
    expect(onSelectionChange).toHaveBeenLastCalledWith(null)
  })

  /** 원칙 VI: 조용히 버리지 않는다. 사용자는 자기 선택이 사라진 이유를 들을 자격이 있다. */
  it('목록에 없는 코드가 남아 있으면 선택을 풀고 그 사실을 알린다', async () => {
    mockCatalog()
    window.sessionStorage.setItem(MARKET_FLOW_SECTOR_KEY, 'SHIPBUILDING')
    const onSelectionChange = vi.fn()
    render(<SectorSelector onSelectionChange={onSelectionChange} />)

    expect(await screen.findByText(/SHIPBUILDING.*목록에 없어/)).toBeTruthy()
    expect(onSelectionChange).toHaveBeenLastCalledWith(null)
    expect(window.sessionStorage.getItem(MARKET_FLOW_SECTOR_KEY)).toBeNull()
  })

  it('목록을 못 얻으면 이 영역만 사유를 보이고 다시 부를 수 있다', async () => {
    vi.mocked(fetchSectorCatalog).mockResolvedValue({
      status: 'failed', kind: 'network', code: null,
      message: '서버에 연결할 수 없습니다.', retryable: true,
    })
    render(<SectorSelector onSelectionChange={vi.fn()} />)

    expect(await screen.findByText('서버에 연결할 수 없습니다.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /목록 다시 불러오기/ }))
    await waitFor(() => expect(fetchSectorCatalog).toHaveBeenCalledTimes(2))
  })

  /** 다시 눌러도 달라지지 않는 상태에는 행동을 두지 않는다(002 research 결정 6). */
  it('다시 불러도 달라지지 않는 사유에는 다시 부르기를 두지 않는다', async () => {
    vi.mocked(fetchSectorCatalog).mockResolvedValue({
      status: 'mapNotReady', message: '섹터 대응표가 아직 준비되지 않았습니다.',
    })
    render(<SectorSelector onSelectionChange={vi.fn()} />)

    expect(await screen.findByText('섹터 대응표가 아직 준비되지 않았습니다.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /다시 불러오기/ })).toBeNull()
  })

  it('부모가 인라인 함수를 넘겨도 목록을 한 번만 부른다', async () => {
    mockCatalog()
    const { rerender } = render(<SectorSelector onSelectionChange={() => {}} />)
    await screen.findByRole('button', { name: '반도체' })

    rerender(<SectorSelector onSelectionChange={() => {}} />)
    rerender(<SectorSelector onSelectionChange={() => {}} />)

    expect(fetchSectorCatalog).toHaveBeenCalledTimes(1)
  })
})

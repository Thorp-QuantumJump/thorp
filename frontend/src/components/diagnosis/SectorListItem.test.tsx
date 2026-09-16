import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SectorSummary } from '@/types/sector'
import { SectorListItem } from './SectorListItem'

const semiconductor: SectorSummary = {
  code: 'SEMICONDUCTOR',
  name: '반도체',
  classification: 'INDUSTRY',
  representativeEtf: {
    instrumentId: '091160.XKRX',
    name: 'KODEX 반도체',
    listedOn: '2006-06-27',
    benchmarkIndex: 'KRX 반도체',
  },
}

const battery: SectorSummary = {
  code: 'BATTERY',
  name: '2차전지',
  classification: 'THEME',
  representativeEtf: {
    instrumentId: '305720.XKRX',
    name: 'KODEX 2차전지산업',
    listedOn: '2018-09-12',
    benchmarkIndex: 'FnGuide 2차전지산업',
  },
}

type RenderOptions = {
  selected?: boolean
  onSelectedChange?: (selected: boolean) => void
  weight?: { valueTenths: number; onCommit: (weightTenths: number) => void }
}

function renderItem(sector: SectorSummary, options: RenderOptions = {}) {
  return render(
    <ul>
      <SectorListItem
        sector={sector}
        selected={options.selected ?? false}
        onSelectedChange={options.onSelectedChange ?? (() => {})}
        weight={options.weight}
      />
    </ul>,
  )
}

describe('SectorListItem', () => {
  it('섹터 이름·대표 ETF 이름·업종 구분을 함께 보인다', () => {
    renderItem(semiconductor)
    const item = screen.getByRole('listitem')

    expect(within(item).getByText('반도체')).toBeTruthy()
    expect(within(item).getByText('KODEX 반도체')).toBeTruthy()
    expect(within(item).getByText('업종')).toBeTruthy()
  })

  it('테마 섹터는 테마로 구분해 보인다', () => {
    renderItem(battery)
    const item = screen.getByRole('listitem')

    expect(within(item).getByText('2차전지')).toBeTruthy()
    expect(within(item).getByText('KODEX 2차전지산업')).toBeTruthy()
    expect(within(item).getByText('테마')).toBeTruthy()
  })

  it('계산식·자료 공급원·실험 식별자·서버 설정을 보이지 않는다', () => {
    renderItem(semiconductor)
    const item = screen.getByRole('listitem')

    for (const hidden of ['091160.XKRX', 'KRX 반도체', '2006-06-27']) {
      expect(within(item).queryByText(hidden)).toBeNull()
    }
  })

  it('섹터 이름으로 읽히는 체크박스 하나로 고른다', async () => {
    // M2는 이 자리에 "고르는 수단을 두지 않는다"를 고정해 두었다. M3이 체크박스를 더하면서
    // 그 검사를 함께 뒤집는다. 대표 상품 정보 펼침 버튼은 아직 없으므로 버튼은 여전히 0개다.
    const onSelectedChange = vi.fn()
    renderItem(semiconductor, { onSelectedChange })
    const item = screen.getByRole('listitem')

    const checkbox = within(item).getByRole('checkbox', { name: '반도체' })
    expect(within(item).queryAllByRole('checkbox')).toHaveLength(1)
    expect(within(item).queryAllByRole('button')).toHaveLength(0)

    await userEvent.click(checkbox)
    expect(onSelectedChange).toHaveBeenCalledWith(true)
  })

  it('고른 상태를 체크박스가 그대로 보인다', async () => {
    const onSelectedChange = vi.fn()
    renderItem(semiconductor, { selected: true, onSelectedChange })
    const checkbox = screen.getByRole('checkbox', { name: '반도체' })

    expect((checkbox as HTMLInputElement).checked).toBe(true)

    await userEvent.click(checkbox)
    expect(onSelectedChange).toHaveBeenCalledWith(false)
  })

  it('고르지 않은 항목에는 비중 입력 칸이 없다', () => {
    renderItem(semiconductor)

    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
  })

  it('카드 안 어디를 눌러도 고르고 풀 수 있다', async () => {
    const onSelectedChange = vi.fn()
    renderItem(semiconductor, { onSelectedChange })

    // 대표 ETF 이름처럼 체크박스에서 먼 자리를 눌러도 고르는 동작이 된다.
    await userEvent.click(screen.getByText('KODEX 반도체'))
    expect(onSelectedChange).toHaveBeenLastCalledWith(true)

    await userEvent.click(screen.getByText('업종'))
    expect(onSelectedChange).toHaveBeenCalledTimes(2)
  })

  it('비중을 만지는 자리는 고르는 영역 밖이다', async () => {
    const onSelectedChange = vi.fn()
    renderItem(semiconductor, {
      selected: true,
      onSelectedChange,
      weight: { valueTenths: 500, onCommit: vi.fn() },
    })

    // 슬라이더를 끌거나 숫자를 고치려다 섹터가 풀리면 안 된다.
    await userEvent.click(screen.getByRole('slider', { name: '반도체 비중' }))
    await userEvent.click(screen.getByRole('spinbutton', { name: '반도체 비중' }))
    await userEvent.click(screen.getByText('반도체 비중'))

    expect(onSelectedChange).not.toHaveBeenCalled()
  })

  it('고른 항목에는 같은 자리에 비중 입력 칸이 나타난다', () => {
    renderItem(semiconductor, {
      selected: true,
      weight: { valueTenths: 334, onCommit: vi.fn() },
    })
    const item = screen.getByRole('listitem')

    // 다른 화면으로 나누지 않는다. 고른 그 항목 안에 있다(FR-005).
    const field = within(item).getByRole('spinbutton', { name: '반도체 비중' })
    expect((field as HTMLInputElement).value).toBe('33.4')
  })
})

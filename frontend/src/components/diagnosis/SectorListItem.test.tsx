import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
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

function renderItem(sector: SectorSummary) {
  return render(
    <ul>
      <SectorListItem sector={sector} />
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

  it('이 시점에는 고르는 수단을 두지 않는다', () => {
    renderItem(semiconductor)
    const item = screen.getByRole('listitem')

    // 체크박스는 M3이, 대표 상품 정보 펼침은 M5가 더한다.
    expect(within(item).queryAllByRole('checkbox')).toHaveLength(0)
    expect(within(item).queryAllByRole('button')).toHaveLength(0)
  })
})

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ListingWindowNotice } from './ListingWindowNotice'
import { describeListingWindow } from '@/diagnosis/listingWindow'
import type { SectorSummary } from '@/types/sector'

function sector(code: string, name: string, listedOn: string): SectorSummary {
  return {
    code,
    name,
    classification: 'INDUSTRY',
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn,
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const 반도체 = sector('SEMICONDUCTOR', '반도체', '2006-06-27')
const 조선 = sector('SHIPBUILDING', '조선', '2018-09-12')
const 철강 = sector('STEEL', '철강', '2006-06-27')

const 오늘 = '2026-09-17'

function renderFor(sectors: SectorSummary[], today = 오늘) {
  return render(<ListingWindowNotice listingWindow={describeListingWindow(sectors, today)} />)
}

function notice(): HTMLElement | null {
  return document.querySelector('[data-slot="listing-window-notice"]')
}

describe('상장일 차이 안내', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('상장일이 같으면 보이지 않는다 (SC-003)', () => {
    renderFor([반도체, 철강])
    expect(notice()).toBeNull()
  })

  it('상장일이 다르면 보인다 (SC-003)', () => {
    renderFor([반도체, 조선])
    expect(notice()).not.toBeNull()
  })

  it('언제부터 비교할 수 있는지와 어느 섹터 때문인지를 한 문장에 함께 담는다', () => {
    renderFor([반도체, 조선])

    const text = notice()?.textContent ?? ''
    expect(text).toContain('2018-09-12')
    expect(text).toContain('조선')
  })

  it('상장일만 반영한 값이며 전략 쪽에서 더 늦어질 수 있다는 사실을 함께 담는다 (FR-008)', () => {
    renderFor([반도체, 조선])

    const text = notice()?.textContent ?? ''
    expect(text).toContain('상장일만 반영')
    // 003이 알릴 워밍업 몫을 이 화면이 최종값처럼 말하지 않는다.
    expect(text).toMatch(/더 늦어질 수 있습니다/)
  })

  it('안내는 한 문단 하나다. 접어 두고 펼치는 구조를 두지 않는다 (원칙 II)', () => {
    const { container } = renderFor([반도체, 조선])

    expect(container.querySelectorAll('[data-slot="listing-window-notice"]')).toHaveLength(1)
    expect(container.querySelector('details')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('데이터 입력이나 자료 공급원 선택을 요구하지 않는다 (SC-005)', () => {
    const { container } = renderFor([반도체, 조선])

    expect(container.querySelectorAll('input, select, textarea')).toHaveLength(0)
  })

  it('공통으로 비교할 기간이 남지 않으면 시작일을 약속하는 문장을 만들지 않는다', () => {
    const 미상장 = sector('FUTURE', '미래', '2027-01-04')
    renderFor([반도체, 미상장])

    const text = notice()?.textContent ?? ''
    expect(text).toContain('비교할 수 있는 기간이 없습니다')
    expect(text).not.toMatch(/부터 함께 비교할 수 있습니다/)
  })

  it('안내를 만드는 데 서버를 부르지 않는다 (시나리오 6)', () => {
    // `GET /sectors`가 이미 준 상장일만 쓴다. 여기서 왕복이 하나라도 생기면 실패한다.
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    renderFor([반도체, 조선])

    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

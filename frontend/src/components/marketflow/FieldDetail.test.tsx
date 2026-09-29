import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { FieldDetailResult } from '@/types/marketFlow'
import { FieldDetail } from './FieldDetail'

/** 진입점이 링크라 라우터 안에서 그린다. */
function renderDetail(detail: FieldDetailResult) {
  return render(
    <MemoryRouter>
      <FieldDetail detail={detail} />
    </MemoryRouter>,
  )
}

/** 기준월만 바꿔 가며 쓰기 위한 최소 상세다. */
function detailOf(referenceMonth: string | null): FieldDetailResult {
  return {
    fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'READY',
    narrative: '변화', narrativeSubjects: [], referenceMonth,
    referenceMonthHeldBackBy: [], lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false,
    multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [],
  }
}

describe('FieldDetail', () => {
  it('역사 비교 표시는 서버가 준 안내가 있을 때만 달고, 원문을 함께 읽힌다 (009 FR-012)', () => {
    const notice = '최신 개정자료를 이용한 역사 비교입니다. 당시 발표값을 재현한 값이 아닙니다.'
    const { unmount } = render(
      <MemoryRouter>
        <FieldDetail detail={detailOf('2026-08')} historyNotice={notice} />
      </MemoryRouter>,
    )
    const label = screen.getByTitle(notice)
    expect(label.textContent).toContain('최신 개정자료 기준')
    expect(label.textContent).toContain(notice)
    unmount()

    renderDetail(detailOf('2026-08'))
    expect(screen.queryByText(/최신 개정자료 기준/)).toBeNull()
  })

  it('기준월 뒤 발표 값은 3개월 경로 밖의 별도 안내로 보여 준다', () => {
    renderDetail({ fieldId: 'labor', displayName: '노동', displayOrder: 4, status: 'READY', narrative: '변화', narrativeSubjects: [], referenceMonth: '2026-08', referenceMonthHeldBackBy: [], lastUpdatedAt: '2026-09-15T21:10:00Z', isExampleData: false, multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.', indicators: [{ indicatorId: 'unemployment', name: '실업률', unit: '%', value: 2.9, path: [{ month: '2026-06', value: 2.8 }, { month: '2026-07', value: 2.8 }, { month: '2026-08', value: 2.9 }], referenceMonth: '2026-08', releaseDate: '2026-09-10', source: '경제활동인구조사', agency: '통계청', afterReferenceMonthRelease: { referenceMonth: '2026-09', value: 3.1, unit: '%' } }] })
    const path = screen.getByRole('list', { name: '실업률 3개월 경로' })
    expect(within(path).queryByText('3.1 %')).toBeNull()
    expect(screen.getByText(/기준월 뒤 발표 · 2026-09 3.1 %/)).toBeTruthy()
  })
  describe("'비슷했던 때 보기'로 넘기는 값 (T027, FR-009)", () => {
    it('분야와 그 분야의 기준월을 실어 둔다', () => {
      renderDetail(detailOf('2026-08'))

      const link = screen.getByRole('link', { name: /비슷했던 때 보기/ })
      expect(JSON.parse(link.getAttribute('data-handoff') ?? 'null')).toEqual({
        fieldId: 'labor',
        referenceMonth: '2026-08',
      })
    })

    it('그 분야와 기준월을 주소에 실어 검색으로 간다', () => {
      renderDetail(detailOf('2026-08'))

      // 주소로 넘기는 이유는 새로고침에도 남아야 해서다. 상태로 넘기면 같은 화면을 다시 열 수 없다.
      expect(screen.getByRole('link', { name: /비슷했던 때 보기/ }).getAttribute('href')).toBe(
        '/regime-search?field=labor&month=2026-08',
      )
    })

    it('기준월이 없으면 넘길 값을 만들지 않고 그 사유를 보인다', () => {
      renderDetail(detailOf(null))

      // 링크가 아니라 눌리지 않는 버튼이다. 기준월이 없으면 검색이 무엇을 찾을지 정해지지 않는다.
      expect(screen.queryByRole('link', { name: /비슷했던 때 보기/ })).toBeNull()
      const button = screen.getByRole('button', { name: /비슷했던 때 보기/ })
      expect(button).toHaveProperty('disabled', true)
      expect(button.getAttribute('data-handoff')).toBeNull()
      expect(screen.getByText(/기준월이 아직 정해지지 않아/)).toBeTruthy()
    })

    it('열린 진입점과 막힌 진입점이 같은 모양을 쓴다', () => {
      // CSS 는 시험이 보지 못한다. 최소한 두 상태가 같은 클래스를 쓰는 것은 고정해 둔다 —
      // 한쪽만 바꾸면 눌리는 링크에 '못 누름' 표시가 남는 식으로 어긋난다.
      const open = renderDetail(detailOf('2026-08'))
      const openClass = screen.getByRole('link', { name: /비슷했던 때 보기/ }).className
      open.unmount()

      renderDetail(detailOf(null))

      expect(screen.getByRole('button', { name: /비슷했던 때 보기/ }).className).toBe(openClass)
    })

    it("'준비 중' 표기가 남아 있지 않다", () => {
      renderDetail(detailOf('2026-08'))

      expect(screen.queryByText('준비 중')).toBeNull()
    })
  })
})

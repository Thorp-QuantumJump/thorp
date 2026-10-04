import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { FieldDetailResult } from '@/types/marketFlow'
import { FieldDetail } from './FieldDetail'
import type { IndicatorPast } from './pastOverlay'

/** 비교 줄에 링크가 있어 라우터 안에서 그린다. */
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
  it('무역수지의 현재값과 그래프 관측값을 같은 단위로 축약한다', () => {
    const detail = detailOf('2026-08')
    detail.narrative = '수출 변화는 4.346167%입니다.'
    detail.indicators = [{
      indicatorId: 'trade_balance', name: '무역수지', unit: '미국 달러', value: 34_785_482_585,
      path: [{ month: '2026-06', value: -34_785_482_585 }, { month: '2026-07', value: null }, { month: '2026-08', value: 34_785_482_585 }],
      referenceMonth: '2026-08', releaseDate: null, source: '관세청', agency: '관세청', afterReferenceMonthRelease: null,
    }]
    renderDetail(detail)
    expect(screen.getByText('수출 변화는 4.3%입니다.')).toBeTruthy()
    expect(screen.getByRole('img', { name: '무역수지 3개월 선 그래프' }).textContent).not.toContain('34,785,482,585')
    const path = screen.getByRole('list', { name: '무역수지 3개월 경로' })
    expect(path.textContent).toContain('약 -347억 9천만 미국 달러')
    expect(path.textContent).toContain('자료 없음')
    expect(detail.indicators[0].value).toBe(34_785_482_585)
  })

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
  describe('지금과 비슷했던 과거 겹쳐 보기 (009 FR-019)', () => {
    function unemploymentDetail(): FieldDetailResult {
      const detail = detailOf('2026-08')
      detail.indicators = [{
        indicatorId: 'unemployment', name: '실업률', unit: '%', value: 2.9,
        path: [{ month: '2026-06', value: 2.8 }, { month: '2026-07', value: 2.8 }, { month: '2026-08', value: 2.9 }],
        referenceMonth: '2026-08', releaseDate: null, source: '경제활동인구조사', agency: '통계청', afterReferenceMonthRelease: null,
      }]
      return detail
    }
    const past: IndicatorPast = {
      path: { months: ['2010-01', '2010-02', '2010-03'], values: [3.5, 3.4, 3.6] },
      verdict: { kind: 'similar', text: '실업률은 2.9%로 당시 3.6%와 비슷합니다.' },
      band: [2, 4],
    }

    it('비교 줄은 분야 머리와 지표 칸 사이에 선다', () => {
      render(
        <MemoryRouter>
          <FieldDetail detail={unemploymentDetail()} compare={<p>비교 줄</p>} />
        </MemoryRouter>,
      )
      const head = screen.getByRole('heading', { level: 2, name: '노동' })
      const compare = screen.getByText('비교 줄')
      const indicator = screen.getByRole('heading', { level: 3, name: '실업률' })
      expect(head.compareDocumentPosition(compare) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(compare.compareDocumentPosition(indicator) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('예전 비슷했던 때 보기 버튼은 두지 않는다(FR-009은 비교 줄의 모두 보기가 맡는다)', () => {
      renderDetail(unemploymentDetail())
      expect(screen.queryByRole('link', { name: /비슷했던 때 보기/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /비슷했던 때 보기/ })).toBeNull()
    })

    it('고른 과거 구간의 당시 경로를 점선으로 겹치고, 문장은 그 지표 칸에 붙인다', () => {
      const { container } = render(
        <MemoryRouter>
          <FieldDetail detail={unemploymentDetail()} pasts={{ unemployment: past }} />
        </MemoryRouter>,
      )
      expect(screen.getByText('비슷한 점')).toBeTruthy()
      expect(screen.getByText('실업률은 2.9%로 당시 3.6%와 비슷합니다.')).toBeTruthy()
      expect(container.querySelector('path[data-past]')).not.toBeNull()
      // 보조기기는 그래프를 보지 않아도 달마다 맞댄 당시 값을 읽는다.
      const list = screen.getByRole('list', { name: '실업률 3개월 경로' })
      expect(list.textContent).toContain('t 2026-08 2.9 % · 당시 2010-03 3.6 %')
      // 보통 범위 띠는 켰을 때만 그린다.
      expect(container.querySelector('rect[data-band]')).toBeNull()
    })

    it('보통 범위를 켜면 띠를 그리고 세로축 설명도 그 범위를 포함한다', () => {
      const { container } = render(
        <MemoryRouter>
          <FieldDetail detail={unemploymentDetail()} pasts={{ unemployment: past }} showBand />
        </MemoryRouter>,
      )
      expect(container.querySelector('rect[data-band]')).not.toBeNull()
      expect(container.textContent).toContain('세로축이 0을 포함합니다.')
    })

    it('문장이 다루지 않는 지표에는 지금 값과 당시 값만 적는다', () => {
      renderDetailWith({ unemployment: { ...past, verdict: null } })
      expect(screen.queryByText('비슷한 점')).toBeNull()
      expect(screen.getByText(/지금 2\.9%/).textContent).toContain('당시 3.6%')
    })

    it('010이 비교하지 않은 달은 비워 두고 잇지 않는다', () => {
      const { container } = renderDetailWith({
        unemployment: { ...past, path: { months: [null, '2010-02', '2010-03'], values: [null, 3.4, 3.6] } },
      })
      const d = container.querySelector('path[data-past]')?.getAttribute('d') ?? ''
      expect(d.match(/M/g)).toHaveLength(1)
      const first = screen.getByRole('list', { name: '실업률 3개월 경로' }).querySelector('li')
      expect(first?.textContent).not.toContain('당시')
    })

    it('지금 값이 없으면 단위를 붙이지 않고, % 밖의 단위는 한 칸 띄운다', () => {
      const detail = unemploymentDetail()
      detail.indicators[0] = { ...detail.indicators[0], value: null }
      render(
        <MemoryRouter>
          <FieldDetail detail={detail} pasts={{ unemployment: { ...past, verdict: null } }} />
        </MemoryRouter>,
      )
      expect(screen.getByText(/지금 자료 없음 ·/).textContent).toBe('지금 자료 없음 · 당시 3.6%')

      const usd = unemploymentDetail()
      usd.indicators[0] = { ...usd.indicators[0], unit: '미국 달러', value: 34_785_482_585 }
      render(
        <MemoryRouter>
          <FieldDetail detail={usd} pasts={{ unemployment: { ...past, verdict: null, path: { months: [null, null, '2010-03'], values: [null, null, 20_000_000_000] } } }} />
        </MemoryRouter>,
      )
      expect(screen.getByText(/지금 약 347억/).textContent).toBe('지금 약 347억 9천만 미국 달러 · 당시 약 200억 미국 달러')
    })

    it('010 문장의 큰 금액도 이 칸의 다른 숫자처럼 줄여 적는다', () => {
      renderDetailWith({ unemployment: { ...past, verdict: { kind: 'different', text: '실업률은 지금이 34785482585미국 달러로 당시보다 큽니다.' } } })
      expect(screen.getByText('실업률은 지금이 약 347억 9천만 미국 달러로 당시보다 큽니다.')).toBeTruthy()
    })

    it('지금 값이 모두 없으면 당시 값이 있어도 관측값 없음을 적는다', () => {
      const detail = unemploymentDetail()
      detail.indicators[0] = { ...detail.indicators[0], value: null, path: [{ month: '2026-06', value: null }, { month: '2026-07', value: null }, { month: '2026-08', value: null }] }
      render(
        <MemoryRouter>
          <FieldDetail detail={detail} pasts={{ unemployment: past }} showBand />
        </MemoryRouter>,
      )
      expect(screen.getByText('관측값 없음')).toBeTruthy()
    })

    it('과거 구간을 고른 동안에는 당시 경로를 받는 중이어도 그래프 높이를 그대로 둔다', () => {
      const { container } = render(
        <MemoryRouter>
          <FieldDetail detail={unemploymentDetail()} comparing />
        </MemoryRouter>,
      )
      expect(container.querySelector('svg')?.getAttribute('viewBox')).toBe('0 0 640 240')
    })

    function renderDetailWith(pasts: Record<string, IndicatorPast>) {
      return render(
        <MemoryRouter>
          <FieldDetail detail={unemploymentDetail()} pasts={pasts} />
        </MemoryRouter>,
      )
    }
  })
})

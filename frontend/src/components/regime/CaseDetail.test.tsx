import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { regimeDetail, regimeResult } from '@/test/regimeFixtures'
import { CaseDetail } from './CaseDetail'

const summary = regimeResult().cases[0]

describe('사례 상세', () => {
  it('명목 비중·거리 기여와 공통 척도, 시점별 원값·z를 손계산 입력으로 보인다', () => {
    render(<CaseDetail detail={regimeDetail()} summary={summary} mode="FIELD" fieldNames={{ 'field-1': '시험 분야' }} />)
    const detail = screen.getByRole('region', { name: '2012-03 사례 상세' })
    expect(detail.textContent).toContain('명목 비중 0.500000000000')
    expect(detail.textContent).toContain('실제 거리 기여 0.204173755344')
    expect(detail.textContent).toContain('중앙값 3.45 · 산포 0.593040887402')
    expect(detail.textContent).toContain('2016-05~2026-04 (120개월)')
    expect(detail.textContent).toContain('-0.590178531422')
    expect(detail.textContent).toContain('3.1 %')
    expect(detail.textContent).toContain(summary.similar[0])
    expect(detail.textContent).toContain(summary.different[0])
  })

  it('시대별 위치는 별도 문장만 보이고 설명이 없어도 사례는 유지한다', () => {
    render(<CaseDetail detail={regimeDetail({ eraPosition: null })} summary={summary} mode="FIELD" fieldNames={{}} />)
    const era = screen.getByLabelText('시험 지표 당시 평소 대비 위치')
    expect(era.textContent).toContain('계산할 수 없습니다')
    expect(era.textContent).not.toMatch(/z\s*값|분위수|백분율/)
    expect(screen.getByRole('region', { name: '2012-03 사례 상세' })).toBeTruthy()
    expect(within(screen.getByRole('region', { name: '2012-03 사례 상세' })).getByText('전체 거리', { exact: false })).toBeTruthy()
  })

  /** 011 T087: 자리를 주지 않으면 010이 내려 준 문구가 그대로 보인다(010 동작 보존). */
  it('사후 구간 자리를 주지 않으면 010의 문구를 그대로 그린다', () => {
    render(<CaseDetail detail={regimeDetail()} summary={summary} mode="FIELD" fieldNames={{}} />)

    expect(screen.getByText('사례 이후 결과는 아직 연결되지 않았습니다.')).toBeTruthy()
  })

  it('사후 구간 자리를 주면 그 자리에 끼워 넣고 010 문구를 대신한다', () => {
    render(
      <CaseDetail
        detail={regimeDetail()}
        summary={summary}
        mode="FIELD"
        fieldNames={{}}
        postPeriodSlot={<span>끼워 넣은 자리</span>}
      />,
    )

    expect(screen.getByText('끼워 넣은 자리')).toBeTruthy()
    expect(screen.queryByText('사례 이후 결과는 아직 연결되지 않았습니다.')).toBeNull()
  })
})

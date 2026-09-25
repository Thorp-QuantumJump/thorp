import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { regimeDetail, regimeResult } from '@/test/regimeFixtures'
import { FieldBreakdown } from './FieldBreakdown'

const summary = regimeResult().cases[0]

describe('종합 분야 내역', () => {
  it('거리·기여와 닮은 점·차이를 함께 보이고 동일가중을 설계 선택으로 말한다', () => {
    render(<FieldBreakdown detail={regimeDetail({ byField: { 'field-1': '0.4', 'field-2': '0.8' }, contribution: { 'field-1': '0.25', 'field-2': '0.75' } })} summary={summary} fieldNames={{ 'field-1': '시험 분야', 'field-2': '다른 분야' }} />)
    expect(screen.getByText(/Thorp의 설계 선택/)).toBeTruthy()
    expect(screen.queryByText(/모든 지표의 중요성이 같다/)).toBeNull()
    expect(screen.getByText('시험 분야')).toBeTruthy()
    expect(screen.getByText('다른 분야')).toBeTruthy()
    expect(screen.getByText('실제 거리 기여 0.75')).toBeTruthy()
    expect(screen.getByText(summary.similar[0])).toBeTruthy()
    expect(screen.getByText(summary.different[0])).toBeTruthy()
  })

  it('총 거리가 0이어서 기여 키가 없으면 가짜 0을 표시하지 않는다', () => {
    render(<FieldBreakdown detail={regimeDetail({ distance: '0', contribution: undefined })} summary={summary} fieldNames={{ 'field-1': '시험 분야' }} />)
    expect(screen.queryByText(/실제 거리 기여/)).toBeNull()
  })

  it('v3 결과는 경제 블록 거리를 그리고 분야 목록을 그리지 않는다', () => {
    render(<FieldBreakdown
      detail={regimeDetail({
        byField: {}, contribution: undefined,
        blocks: [
          { id: 'real', name: '실물 경기', gap: '2.459', contribution: '0.4177', spread: '0.61' },
          { id: 'financial', name: '금융 여건', gap: '3.427', spread: '0.70' },
        ],
      })}
      summary={summary} fieldNames={{ 'field-1': '시험 분야' }}
    />)
    expect(screen.getByRole('heading', { name: '경제 블록별 거리와 기여' })).toBeTruthy()
    expect(screen.getByText('실물 경기')).toBeTruthy()
    expect(screen.getByText('실제 거리 기여 0.4177')).toBeTruthy()
    expect(screen.getAllByText(/실제 거리 기여/)).toHaveLength(1)
    expect(screen.getByText(/경제 흐름별 블록/)).toBeTruthy()
    expect(screen.queryByText('시험 분야')).toBeNull()
  })
})

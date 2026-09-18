import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { publishCheckFailures } from '@/test/learningFixtures'
import { PublishCheckResultList } from './PublishCheckResultList'

/** 013 T070. 계약 §6 — 걸린 항목을 모두 보이고 문장을 새로 짓지 않는다. */

describe('PublishCheckResultList', () => {
  it('걸린 항목 셋을 모두 보이고 서버 문장을 그대로 쓴다', () => {
    render(<PublishCheckResultList failures={publishCheckFailures} />)

    const list = screen.getByRole('alert')
    expect(within(list).getAllByRole('listitem')).toHaveLength(3)
    for (const failure of publishCheckFailures) {
      expect(within(list).getByText(failure.message)).not.toBeNull()
    }
  })

  it('block 위치는 순번을 1부터 세고 term 위치는 식별자를 보인다', () => {
    render(<PublishCheckResultList failures={publishCheckFailures} />)

    expect(screen.getByText('본문 3번째 블록')).not.toBeNull()
    expect(screen.getByText('용어 max-drawdown')).not.toBeNull()
    expect(screen.getByText('예시')).not.toBeNull()
  })

  it('matched가 있는 항목만 걸린 표현을 보인다', () => {
    render(<PublishCheckResultList failures={publishCheckFailures} />)

    expect(screen.getByText('걸린 표현: 추천 비중')).not.toBeNull()
    expect(screen.getByText('걸린 표현: -30%')).not.toBeNull()
    expect(screen.queryAllByText(/^걸린 표현: /)).toHaveLength(2)
  })

  it('index가 없는 block 위치는 본문 전체를 가리킨다', () => {
    render(
      <PublishCheckResultList
        failures={[
          {
            check: 'REQUIRED_FIELDS',
            location: { part: 'block' },
            message: '문단이 하나도 없습니다.',
          },
        ]}
      />,
    )

    expect(screen.getByText('본문')).not.toBeNull()
  })

  it('항목이 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(<PublishCheckResultList failures={[]} />)
    expect(container.firstChild).toBeNull()
  })
})

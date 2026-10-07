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

  it('(2판) quiz 위치는 문제·보기 식별자를, 새 문제는 순번을, field는 칸 이름을 보인다', () => {
    render(
      <PublishCheckResultList
        failures={[
          {
            check: 'QUIZ',
            location: { part: 'quiz', questionId: 'q2', choiceId: 'c5' },
            message: '정답 보기가 하나가 아닙니다.',
          },
          {
            check: 'FORBIDDEN_EXPRESSION',
            location: { part: 'quiz', index: 0, field: 'explanation' },
            message: '추천으로 읽히는 표현이 있습니다.',
          },
          { check: 'QUIZ', location: { part: 'quiz' }, message: '확인 문제는 2~3개여야 합니다.' },
          {
            check: 'RESULT_NUMBER_OUTSIDE_HYPOTHETICAL',
            location: { part: 'block', index: 4, field: 'rows[0][1]' },
            message: '숫자 검사 문장',
          },
          {
            check: 'PLACEMENT',
            location: { part: 'article' },
            message: '초안을 다른 단계로 옮긴 글입니다.',
          },
        ]}
      />,
    )

    expect(screen.getByText('확인 문제 q2 보기 c5')).not.toBeNull()
    expect(screen.getByText('확인 문제 1번째(새 문제) · 풀이')).not.toBeNull()
    expect(screen.getByText('확인 문제')).not.toBeNull()
    expect(screen.getByText('본문 5번째 블록 · 1행 2열')).not.toBeNull()
    expect(screen.getByText('글 전체')).not.toBeNull()
    // 검사 이름은 서버 값 그대로다.
    expect(screen.getAllByText('QUIZ')).toHaveLength(2)
    expect(screen.getByText('PLACEMENT')).not.toBeNull()
  })

  it('(converge) 표 열·도식 계열·다른 이름 위치도 1부터 센 이름으로 보인다', () => {
    render(
      <PublishCheckResultList
        failures={[
          {
            check: 'C1',
            location: { part: 'block', index: 0, field: 'columns[1]' },
            message: '가',
          },
          {
            check: 'C2',
            location: { part: 'block', index: 1, field: 'series[0].name' },
            message: '나',
          },
          {
            check: 'C3',
            location: { part: 'term', termId: 'x', field: 'aliases[2]' },
            message: '다',
          },
        ]}
      />,
    )

    expect(screen.getByText('본문 1번째 블록 · 2번째 열 이름')).not.toBeNull()
    expect(screen.getByText('본문 2번째 블록 · 1번째 계열 이름')).not.toBeNull()
    expect(screen.getByText('용어 x · 3번째 다른 이름')).not.toBeNull()
  })

  it('(2판) 여러 목록을 함께 그려도 제목 id가 겹치지 않는다', () => {
    render(
      <>
        <PublishCheckResultList title="글 하나" failures={publishCheckFailures} announce={false} />
        <PublishCheckResultList title="글 둘" failures={publishCheckFailures} announce={false} />
      </>,
    )

    const first = screen.getByRole('region', { name: '글 하나' })
    const second = screen.getByRole('region', { name: '글 둘' })
    expect(first.getAttribute('aria-labelledby')).not.toBe(second.getAttribute('aria-labelledby'))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('항목이 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(<PublishCheckResultList failures={[]} />)
    expect(container.firstChild).toBeNull()
  })
})

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { adminV2Article } from '@/test/learningFixtures'
import type { DraftQuizQuestion } from '@/types/learningAdmin'
import { QuizEditor } from './QuizEditor'

/**
 * 013 T174. 확인 문제 편집기(data-model §6). 받은 식별자를 그대로 두고, 새 항목은 `null`로 만들며, 정답은
 * 문제마다 하나만 고른다.
 */

function Harness({
  initial,
  onChange,
}: {
  initial: DraftQuizQuestion[]
  onChange: (questions: DraftQuizQuestion[]) => void
}) {
  const [questions, setQuestions] = useState(initial)
  return (
    <QuizEditor
      questions={questions}
      onChange={(next) => {
        setQuestions(next)
        onChange(next)
      }}
    />
  )
}

function renderEditor(initial = adminV2Article.draft.quiz) {
  const onChange = vi.fn<(questions: DraftQuizQuestion[]) => void>()
  render(<Harness initial={initial} onChange={onChange} />)
  return () => onChange.mock.lastCall?.[0] ?? initial
}

describe('QuizEditor', () => {
  it('정답을 다른 보기로 바꾸면 그 문제의 정답은 하나뿐이고 식별자는 그대로다', async () => {
    const latest = renderEditor()
    const user = userEvent.setup()

    await user.click(screen.getByLabelText('1번째 문제 1번째 보기를 정답으로'))

    const [first, second] = latest()
    expect(first.questionId).toBe('q1')
    expect(first.choices).toEqual([
      { choiceId: 'c2', text: '틀린 보기', correct: true },
      { choiceId: 'c3', text: '정답 보기', correct: false },
    ])
    // 다른 문제의 정답은 건드리지 않는다.
    expect(second).toEqual(adminV2Article.draft.quiz[1])
  })

  it('글자를 고쳐도 받은 식별자를 그대로 둔다', async () => {
    const latest = renderEditor()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/^1번째 문제 질문/), ' 고침')

    expect(latest()[0]).toMatchObject({ questionId: 'q1', prompt: '합성 질문 하나 고침' })
    expect(latest()[0].choices.map((choice) => choice.choiceId)).toEqual(['c2', 'c3'])
  })

  it('새 문제와 새 보기는 식별자가 null이다. 화면이 식별자를 짓지 않는다', async () => {
    const latest = renderEditor([])
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '문제 추가' }))
    await user.click(screen.getByRole('button', { name: '1번째 문제 보기 추가' }))

    const [question] = latest()
    expect(question.questionId).toBeNull()
    expect(question.choices.map((choice) => choice.choiceId)).toEqual([null, null, null])
    expect(question.choices.filter((choice) => choice.correct)).toHaveLength(1)
    expect(screen.getByText('1번째 문제 (새 문제)')).not.toBeNull()
  })

  it('저장 상한에서 추가 단추가 멈춘다(문제 3개, 보기 5개)', async () => {
    renderEditor([])
    const user = userEvent.setup()
    const addQuestion = screen.getByRole('button', { name: '문제 추가' })

    await user.click(addQuestion)
    await user.click(addQuestion)
    await user.click(addQuestion)
    expect(addQuestion).toHaveProperty('disabled', true)

    const addChoice = screen.getByRole('button', { name: '1번째 문제 보기 추가' })
    await user.click(addChoice)
    await user.click(addChoice)
    await user.click(addChoice)
    expect(addChoice).toHaveProperty('disabled', true)
  })
})

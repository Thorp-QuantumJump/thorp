import { readFileSync } from 'node:fs'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import type { QuizQuestion } from '@/types/learning'
import type { QuizSummary, SavedAnswer } from '@/types/learningMe'
import { currentArticle } from '@/test/learningFixtures'
import { openQuiz } from '@/test/learningMeFixtures'
import { QuizPanel, type QuizSubmitOutcome } from './QuizPanel'

/**
 * 013 T184. 비로그인 확인 문제는 화면 안에서만 풀고 아무것도 보내거나 남기지 않는다(FR-022, SC-009,
 * quickstart 9-4, 10-11 화면 부분). 답한 문제의 표시는 목업 '글 끝'과 FR-017·SC-002를 따른다.
 */

const questions = currentArticle.quiz

/** 답한 문제의 보기 줄. 보기 글자로 찾는다. */
function answeredRow(text: string): HTMLElement {
  const row = screen.getByText(text, { exact: false }).closest('li')
  if (!row) throw new Error(`답한 보기 줄이 없습니다: ${text}`)
  return row
}

describe('QuizPanel — 비로그인', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>
  let setItem: ReturnType<typeof vi.spyOn>
  let requestSpies: Array<ReturnType<typeof vi.spyOn>>

  beforeEach(() => {
    fetchSpy = vi.spyOn(globalThis, 'fetch')
    setItem = vi.spyOn(Storage.prototype, 'setItem')
    requestSpies = (['get', 'post', 'put', 'delete', 'request'] as const).map((method) =>
      vi.spyOn(apiClient, method),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('머리에 가상의 예 표시가 있고 첫 문제만 보이며 보기는 질문을 이름으로 단 묶음이다', () => {
    render(<QuizPanel questions={questions} mode="guest" />)

    const panel = screen.getByRole('region', { name: '확인 문제' })
    expect(within(panel).getByText('가상의 예')).not.toBeNull()
    expect(screen.getByText('합성 질문 하나다.')).not.toBeNull()
    expect(screen.queryByText('합성 질문 둘이다.')).toBeNull()
    const group = screen.getByRole('group', { name: /합성 질문 하나다/ })
    expect(within(group).getAllByRole('button')).toHaveLength(2)
    expect(screen.getByText('고르면 바로 답이 됩니다.')).not.toBeNull()
  })

  it('답하기 전에는 정답과 풀이가 없다', () => {
    render(<QuizPanel questions={questions} mode="guest" />)

    expect(screen.queryByText('합성 풀이 하나다.')).toBeNull()
    expect(screen.queryByText('정답')).toBeNull()
    expect(screen.queryByText('틀린 보기')).toBeNull()
  })

  it('정답을 고르면 그 보기에 고른 답·맞음이, 나머지 보기에는 모두 틀린 보기가 붙는다', async () => {
    const user = userEvent.setup()
    const three: QuizQuestion = questions[1]
    render(<QuizPanel questions={[three]} mode="guest" />)

    await user.click(screen.getByRole('button', { name: /합성 보기 다/ }))

    const picked = answeredRow('합성 보기 다')
    expect(within(picked).getByText('고른 답')).not.toBeNull()
    expect(within(picked).getByText('맞음')).not.toBeNull()
    for (const text of ['합성 보기 라', '합성 보기 마']) {
      const row = answeredRow(text)
      expect(within(row).getByText('틀린 보기')).not.toBeNull()
      expect(within(row).queryByText('고른 답')).toBeNull()
    }
    expect(screen.getByText('합성 풀이 둘이다.')).not.toBeNull()
  })

  it('틀린 보기를 고르면 그 보기에 고른 답·틀린 보기가, 정답 보기에는 정답이, 다른 틀린 보기에도 틀린 보기가 붙는다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={[questions[1]]} mode="guest" />)

    await user.click(screen.getByRole('button', { name: /합성 보기 라/ }))

    const picked = answeredRow('합성 보기 라')
    expect(within(picked).getByText('고른 답')).not.toBeNull()
    expect(within(picked).getByText('틀린 보기')).not.toBeNull()
    expect(within(answeredRow('합성 보기 다')).getByText('정답')).not.toBeNull()
    expect(within(answeredRow('합성 보기 마')).getByText('틀린 보기')).not.toBeNull()
  })

  it('답하면 고른 보기를 바꿀 수 없고 초점이 풀이로 옮겨 가며 풀이가 고른 답과 맞았는지를 읽는다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    await user.click(screen.getByRole('button', { name: /합성 보기 가/ }))

    // 답한 문제에는 누를 단추가 없다. 정적 목록이다.
    expect(screen.queryByRole('group', { name: /합성 질문 하나다/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /합성 보기 가/ })).toBeNull()
    const explanation = screen.getByText('합성 풀이 하나다.', { exact: false })
    expect(document.activeElement).toBe(explanation)
    expect(explanation.textContent).toContain('고른 답 1번, 틀린 보기.')
    // 이름표와 풀이 사이에 실제 띄어쓰기가 있어야 읽기·복사에서 붙지 않는다.
    expect(explanation.textContent).toContain('풀이 합성 풀이 하나다.')
  })

  it('답한 문제는 결과와 풀이를 남긴 채 다음 문제가 이어 보이고, 모두 답하면 맞힌 개수를 보인다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))
    expect(screen.queryByText(/문제 중 \d개를 맞혔습니다/)).toBeNull()

    expect(screen.getByText('합성 질문 둘이다.')).not.toBeNull()
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    await user.click(screen.getByRole('button', { name: /합성 보기 마/ }))

    expect(screen.getByText('2문제 중 1개를 맞혔습니다.')).not.toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('보기를 키보드만으로 골라 답할 수 있다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    await user.tab()
    expect(document.activeElement?.textContent).toContain('합성 보기 가')
    await user.tab()
    await user.keyboard('{Enter}')

    expect(within(answeredRow('합성 보기 나')).getByText('맞음')).not.toBeNull()
  })

  it('답하는 동안 요청도 브라우저 저장도 하지 않는다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))
    await user.click(screen.getByRole('button', { name: /합성 보기 다/ }))

    expect(fetchSpy).not.toHaveBeenCalled()
    for (const spy of requestSpies) expect(spy).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
  })

  it('다시 그리면 답하지 않은 처음 상태다', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<QuizPanel questions={questions} mode="guest" />)
    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))
    unmount()

    render(<QuizPanel questions={questions} mode="guest" />)

    expect(screen.getByRole('button', { name: /합성 보기 나/ })).not.toBeNull()
    expect(screen.queryByText('합성 질문 둘이다.')).toBeNull()
    expect(screen.queryByText('합성 풀이 하나다.', { exact: false })).toBeNull()
  })

  it('문제가 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(<QuizPanel questions={[]} mode="guest" />)

    expect(container.textContent).toBe('')
  })

  it('오답 표시와 글 화면 부품에 빨강·파랑 토큰을 쓰지 않는다', () => {
    // CSS 모듈은 가져오면 클래스 이름 객체가 되므로 파일 글자를 직접 읽는다.
    const files = ['./QuizPanel.module.css', './ArticleBody.module.css', './EditionNotice.module.css']
    for (const file of files) {
      const css = readFileSync(new URL(file, import.meta.url), 'utf-8')
      expect(css, file).not.toMatch(/--thorp-(up|down|negative)\b/)
    }
  })
})

/**
 * 013 T188·T190. 로그인 사용자는 저장 응답 뒤에만 맞았는지와 정답·풀이를 본다(FR-022, quickstart 10-3·10-4).
 * 부르는 쪽(글 화면)처럼 저장에 성공하면 첫 답과 요약을 바꿔 다시 그린다.
 */
describe('QuizPanel — 로그인', () => {
  type Submit = (questionId: string, choiceId: string) => Promise<QuizSubmitOutcome>

  function RecordHarness({
    questionList = questions,
    initialAnswers = [],
    initialSummary = openQuiz,
    hasTerms = true,
    onAnswer,
    onRetry = vi.fn<Submit>(),
    onReload = vi.fn(),
    summaryAfterRetry,
  }: {
    questionList?: readonly QuizQuestion[]
    initialAnswers?: SavedAnswer[]
    initialSummary?: QuizSummary
    hasTerms?: boolean
    onAnswer: Submit
    onRetry?: Submit
    onReload?: () => void
    summaryAfterRetry?: QuizSummary
  }) {
    const [answers, setAnswers] = useState(initialAnswers)
    const [summary, setSummary] = useState(initialSummary)
    return (
      <QuizPanel
        questions={questionList}
        mode="record"
        answers={answers}
        summary={summary}
        hasTerms={hasTerms}
        onAnswer={async (questionId, choiceId) => {
          const outcome = await onAnswer(questionId, choiceId)
          if (outcome.status === 'saved') {
            setAnswers((previous) => [
              ...previous,
              { questionId, choiceId: outcome.choiceId, correct: outcome.correct, answeredAt: '' },
            ])
          }
          return outcome
        }}
        onRetry={async (questionId, choiceId) => {
          const outcome = await onRetry(questionId, choiceId)
          if (outcome.status === 'saved' && summaryAfterRetry) setSummary(summaryAfterRetry)
          return outcome
        }}
        onReload={onReload}
      />
    )
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('고른 보기를 잠그고 저장 응답 전에는 맞았는지와 정답·풀이를 보이지 않는다', async () => {
    const user = userEvent.setup()
    let settle: (outcome: QuizSubmitOutcome) => void = () => {}
    const onAnswer = vi.fn<Submit>(
      () =>
        new Promise((resolve) => {
          settle = resolve
        }),
    )
    render(<RecordHarness onAnswer={onAnswer} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))

    expect(onAnswer).toHaveBeenCalledWith('q1', 'c2')
    expect(within(answeredRow('합성 보기 나')).getByText('고른 답')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /합성 보기/ })).toBeNull()
    expect(screen.queryByText('맞음')).toBeNull()
    expect(screen.queryByText('정답')).toBeNull()
    expect(screen.queryByText('틀린 보기')).toBeNull()
    expect(screen.queryByText('합성 풀이 하나다.', { exact: false })).toBeNull()
    // 누른 단추가 잠긴 목록으로 바뀌어도 초점이 문서로 빠지지 않고 기록 중 줄에 머문다(FR-019).
    expect(document.activeElement?.textContent).toBe('기록하는 중입니다.')

    await act(async () => settle({ status: 'saved', choiceId: 'c2', correct: true }))

    expect(within(answeredRow('합성 보기 나')).getByText('맞음')).not.toBeNull()
    const explanation = screen.getByText('합성 풀이 하나다.', { exact: false })
    expect(document.activeElement).toBe(explanation)
    expect(screen.getByText('합성 질문 둘이다.')).not.toBeNull()
  })

  it('저장에 실패하면 정답·풀이 없이 기록되지 않았습니다와 다시 시도이고, 다시 시도는 같은 보기를 보낸다', async () => {
    const user = userEvent.setup()
    const onAnswer = vi
      .fn<Submit>()
      .mockResolvedValueOnce({ status: 'failed' })
      .mockResolvedValueOnce({ status: 'saved', choiceId: 'c1', correct: false })
    render(<RecordHarness onAnswer={onAnswer} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 가/ }))

    expect(await screen.findByText('기록되지 않았습니다.')).not.toBeNull()
    expect(screen.queryByText('합성 풀이 하나다.', { exact: false })).toBeNull()
    expect(screen.queryByText('정답')).toBeNull()
    // 실패하면 초점이 '다시 시도'로 오고, 단추는 실패 문장을 설명으로 달아 함께 읽힌다.
    const resend = screen.getByRole('button', { name: '다시 시도' })
    expect(document.activeElement).toBe(resend)
    expect(resend.getAttribute('aria-describedby')).toBe(
      screen.getByText('기록되지 않았습니다.').id,
    )

    await user.click(resend)

    expect(onAnswer.mock.calls).toEqual([
      ['q1', 'c1'],
      ['q1', 'c1'],
    ])
    expect(await screen.findByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(within(answeredRow('합성 보기 나')).getByText('정답')).not.toBeNull()
  })

  it('이미 다른 탭에서 답한 문제면 서버가 돌려준 첫 답으로 보인다', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn<Submit>().mockResolvedValue({ status: 'saved', choiceId: 'c1', correct: false })
    render(<RecordHarness onAnswer={onAnswer} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))

    const picked = await screen.findByText('고른 답')
    expect(picked.closest('li')?.textContent).toContain('합성 보기 가')
  })

  it('문제가 바뀌었으면 글을 다시 불러오라는 한 줄과 다시 불러오기다', async () => {
    const user = userEvent.setup()
    const onReload = vi.fn()
    const onAnswer = vi.fn<Submit>().mockResolvedValue({ status: 'questionNotFound' })
    render(<RecordHarness onAnswer={onAnswer} onReload={onReload} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))
    await user.click(await screen.findByRole('button', { name: '다시 불러오기' }))

    expect(screen.getByText('이 문제가 바뀌었습니다. 글을 다시 불러와 주세요.')).not.toBeNull()
    expect(onReload).toHaveBeenCalledTimes(1)
  })

  it('다른 곳에서 기록이 바뀌어 다시 보내도 같은 실패면 다시 시도 대신 다시 불러오기를 준다', async () => {
    const user = userEvent.setup()
    const onReload = vi.fn()
    const onAnswer = vi.fn<Submit>().mockResolvedValue({ status: 'stale' })
    render(<RecordHarness onAnswer={onAnswer} onReload={onReload} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))

    expect(await screen.findByText('학습 기록이 바뀌었습니다. 글을 다시 불러와 주세요.')).not.toBeNull()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    expect(onReload).toHaveBeenCalledTimes(1)
    expect(onAnswer).toHaveBeenCalledTimes(1)
  })

  it('완료 카드 안에서는 카드 틀 없이 h3 제목으로 그린다', () => {
    render(<RecordHarness onAnswer={vi.fn<Submit>()} />)

    const region = screen.getByRole('region', { name: '확인 문제' })
    expect(within(region).getByRole('heading', { level: 3, name: '확인 문제' })).not.toBeNull()
    expect(region.className).not.toMatch(/panel/)
  })

  it('운영자가 앞에 새 문제를 더해도 뒤의 답한 문제는 결과와 풀이로 보이고, 새 문제 하나만 열린다', () => {
    const added: QuizQuestion = {
      questionId: 'q9',
      prompt: '합성 새 질문이다.',
      choices: [
        { choiceId: 'c90', text: '합성 새 보기 가' },
        { choiceId: 'c91', text: '합성 새 보기 나' },
      ],
      correctChoiceId: 'c90',
      explanation: '합성 새 풀이다.',
    }
    render(
      <RecordHarness
        questionList={[added, ...questions]}
        onAnswer={vi.fn<Submit>()}
        initialAnswers={[
          { questionId: 'q1', choiceId: 'c2', correct: true, answeredAt: '' },
          { questionId: 'q3', choiceId: 'c4', correct: true, answeredAt: '' },
        ]}
        initialSummary={{ ...openQuiz, total: 3, answered: 2, correct: 2, mastered: true }}
      />,
    )

    expect(screen.getAllByRole('group')).toHaveLength(1)
    expect(screen.getByRole('group', { name: /합성 새 질문이다/ })).not.toBeNull()
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(screen.getByText('합성 풀이 둘이다.', { exact: false })).not.toBeNull()
  })

  it('다른 화면에서 먼저 답한 첫 답이 돌아오면 그 까닭을 한 줄로 알린다', async () => {
    const user = userEvent.setup()
    const onAnswer = vi
      .fn<Submit>()
      .mockResolvedValue({ status: 'saved', choiceId: 'c1', correct: false, alreadyAnswered: true })
    render(<RecordHarness onAnswer={onAnswer} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))

    expect(
      await screen.findByText('다른 화면에서 먼저 고른 답이 첫 답으로 기록되어 있습니다.'),
    ).not.toBeNull()
  })

  it('같은 보기를 다시 보내 이미 답한 것으로 돌아오면 그 줄을 두지 않는다', async () => {
    const user = userEvent.setup()
    const onAnswer = vi
      .fn<Submit>()
      .mockResolvedValue({ status: 'saved', choiceId: 'c2', correct: true, alreadyAnswered: true })
    render(<RecordHarness onAnswer={onAnswer} />)

    await user.click(screen.getByRole('button', { name: /합성 보기 나/ }))

    expect(await screen.findByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(screen.queryByText(/먼저 고른 답/)).toBeNull()
  })

  it('다시 들어오면 저장된 첫 답의 결과와 풀이로 보이고 다음 문제를 답할 수 있다', () => {
    render(
      <RecordHarness
        onAnswer={vi.fn<Submit>()}
        initialAnswers={[{ questionId: 'q1', choiceId: 'c2', correct: true, answeredAt: '' }]}
        initialSummary={{ ...openQuiz, answered: 1, correct: 1, mastered: true, masteredVia: 'FIRST_ANSWER' }}
      />,
    )

    expect(within(answeredRow('합성 보기 나')).getByText('맞음')).not.toBeNull()
    expect(screen.getByText('합성 풀이 하나다.', { exact: false })).not.toBeNull()
    expect(screen.getAllByText('첫 답')).toHaveLength(2)
    expect(screen.getByRole('group', { name: /합성 질문 둘이다/ })).not.toBeNull()
  })

  it('첫 답에서 하나도 못 맞히면 맞힌 개수와 다시 풀기이고, 다시 풀어 맞히면 익힘이 되고 끝난다', async () => {
    const user = userEvent.setup()
    const allWrong: QuizSummary = {
      total: 2,
      answered: 2,
      correct: 0,
      allAnswered: true,
      mastered: false,
      masteredVia: null,
      retryAvailable: true,
    }
    const onRetry = vi
      .fn<Submit>()
      .mockResolvedValueOnce({ status: 'saved', choiceId: 'c1', correct: false })
      .mockResolvedValueOnce({ status: 'saved', choiceId: 'c4', correct: true })
    render(
      <RecordHarness
        onAnswer={vi.fn<Submit>()}
        onRetry={onRetry}
        initialAnswers={[
          { questionId: 'q1', choiceId: 'c1', correct: false, answeredAt: '' },
          { questionId: 'q3', choiceId: 'c5', correct: false, answeredAt: '' },
        ]}
        initialSummary={allWrong}
        summaryAfterRetry={{ ...allWrong, mastered: true, masteredVia: 'RETRY', retryAvailable: false }}
      />,
    )

    expect(screen.getByText('2문제 중 0개를 맞혔습니다.')).not.toBeNull()
    await user.click(screen.getByRole('button', { name: '다시 풀기' }))
    expect(screen.getByText(/경험치는 더하지 않습니다/)).not.toBeNull()
    // 누른 단추가 사라지므로 초점이 다시 풀기 제목으로 온다.
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '다시 풀기' })),
    )

    // 한 문제씩 다시 푼다. 첫 문제를 답해야 둘째 문제가 보인다.
    const retry = screen.getByRole('heading', { name: '다시 풀기' }).parentElement as HTMLElement
    expect(within(retry).queryByText('합성 질문 둘이다.')).toBeNull()
    await user.click(within(retry).getByRole('button', { name: /합성 보기 가/ }))
    await user.click(await within(retry).findByRole('button', { name: /합성 보기 다/ }))

    expect(onRetry.mock.calls).toEqual([
      ['q1', 'c1'],
      ['q3', 'c4'],
    ])
    expect(await screen.findByText('익힘이 되었습니다.')).not.toBeNull()
    expect(screen.queryByRole('button', { name: '다시 풀기' })).toBeNull()
    // 맞힌 개수는 첫 답 기준이라 그대로다.
    expect(screen.getByText('2문제 중 0개를 맞혔습니다.')).not.toBeNull()
  })

  it('용어가 없는 글의 다시 풀기는 용어를 말하지 않는다', async () => {
    const user = userEvent.setup()
    const allWrong: QuizSummary = {
      total: 2,
      answered: 2,
      correct: 0,
      allAnswered: true,
      mastered: false,
      masteredVia: null,
      retryAvailable: true,
    }
    const onRetry = vi
      .fn<Submit>()
      .mockResolvedValue({ status: 'saved', choiceId: 'c2', correct: true })
    render(
      <RecordHarness
        hasTerms={false}
        onAnswer={vi.fn<Submit>()}
        onRetry={onRetry}
        initialAnswers={[
          { questionId: 'q1', choiceId: 'c1', correct: false, answeredAt: '' },
          { questionId: 'q3', choiceId: 'c5', correct: false, answeredAt: '' },
        ]}
        initialSummary={allWrong}
        summaryAfterRetry={{ ...allWrong, mastered: true, masteredVia: 'RETRY', retryAvailable: false }}
      />,
    )

    await user.click(screen.getByRole('button', { name: '다시 풀기' }))
    expect(screen.getByText('다시 풀기는 경험치를 더하지 않습니다.')).not.toBeNull()
    expect(screen.queryByText(/용어/)).toBeNull()

    const retry = screen.getByRole('heading', { name: '다시 풀기' }).parentElement as HTMLElement
    await user.click(within(retry).getByRole('button', { name: /합성 보기 나/ }))
    expect(await screen.findByText('다시 풀기에서 맞혔습니다.')).not.toBeNull()
    expect(screen.queryByText('익힘이 되었습니다.')).toBeNull()
  })
})

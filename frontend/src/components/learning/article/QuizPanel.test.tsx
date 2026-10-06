import { readFileSync } from 'node:fs'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import type { QuizQuestion } from '@/types/learning'
import { currentArticle } from '@/test/learningFixtures'
import { QuizPanel } from './QuizPanel'

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

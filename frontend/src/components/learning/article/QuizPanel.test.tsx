import { readFileSync } from 'node:fs'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { currentArticle } from '@/test/learningFixtures'
import { QuizPanel } from './QuizPanel'

/**
 * 013 T184. 비로그인 확인 문제는 화면 안에서만 풀고 아무것도 보내거나 남기지 않는다(FR-022, SC-009,
 * quickstart 9-4, 10-11 화면 부분).
 */

const questions = currentArticle.quiz

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

  it('답하기 전에는 정답과 풀이가 없고, 답하면 바로 맞았는지와 정답·풀이가 보인다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    expect(screen.getByText('합성 질문 하나다.')).not.toBeNull()
    expect(screen.queryByText('합성 풀이 하나다.')).toBeNull()
    expect(screen.queryByText('정답')).toBeNull()

    await user.click(screen.getByRole('button', { name: '합성 보기 나' }))

    expect(screen.getByRole('status').textContent).toContain('맞았습니다.')
    expect(screen.getByText('합성 풀이 하나다.')).not.toBeNull()
    expect(within(screen.getByRole('button', { name: /합성 보기 나/ })).getByText('정답')).not.toBeNull()
  })

  it('틀린 보기에는 틀린 보기 표시가 붙고 보기를 바꿀 수 없다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    await user.click(screen.getByRole('button', { name: '합성 보기 가' }))

    expect(screen.getByRole('status').textContent).toContain('틀렸습니다.')
    const wrong = screen.getByRole('button', { name: /합성 보기 가/ })
    expect(within(wrong).getByText('틀린 보기')).not.toBeNull()
    expect(within(screen.getByRole('button', { name: /합성 보기 나/ })).getByText('정답')).not.toBeNull()
    for (const button of screen.getAllByRole('button', { name: /합성 보기/ })) {
      expect((button as HTMLButtonElement).disabled).toBe(true)
    }
  })

  it('다음 문제로 넘어가고 모두 답하면 맞힌 개수를 보인다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    expect(screen.queryByRole('button', { name: '다음 문제' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '합성 보기 가' }))
    expect(screen.queryByText(/2문제 중 \d개$/)).toBeNull()
    await user.click(screen.getByRole('button', { name: '다음 문제' }))

    expect(screen.getByText('합성 질문 둘이다.')).not.toBeNull()
    await user.click(screen.getByRole('button', { name: '합성 보기 다' }))

    expect(screen.getByText('2문제 중 1개')).not.toBeNull()
    expect(screen.queryByRole('button', { name: '다음 문제' })).toBeNull()
  })

  it('답하고 넘기는 동안 요청도 브라우저 저장도 하지 않는다', async () => {
    const user = userEvent.setup()
    render(<QuizPanel questions={questions} mode="guest" />)

    await user.click(screen.getByRole('button', { name: '합성 보기 나' }))
    await user.click(screen.getByRole('button', { name: '다음 문제' }))
    await user.click(screen.getByRole('button', { name: '합성 보기 다' }))

    expect(fetchSpy).not.toHaveBeenCalled()
    for (const spy of requestSpies) expect(spy).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
  })

  it('다시 그리면 답하지 않은 처음 상태다', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<QuizPanel questions={questions} mode="guest" />)
    await user.click(screen.getByRole('button', { name: '합성 보기 나' }))
    unmount()

    render(<QuizPanel questions={questions} mode="guest" />)

    expect(screen.getByText('합성 질문 하나다.')).not.toBeNull()
    expect(screen.queryByRole('status')?.textContent ?? '').toBe('')
    expect((screen.getByRole('button', { name: '합성 보기 나' }) as HTMLButtonElement).disabled).toBe(
      false,
    )
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

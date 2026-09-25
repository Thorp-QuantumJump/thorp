import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AssistantError } from '@/api/assistant'
import { EXAMPLE_QUESTIONS, TRANSFER_NOTICE } from './AssistantBubble'
import { FAB_POSITION_KEY } from './fabPosition'
import {
  OTHER_TOKEN,
  TOKEN,
  answered,
  clearMatchMedia,
  mockMatchMedia,
  renderAssistant,
} from './assistantTestSupport'

const api = vi.hoisted(() => ({
  fetchAssistantCapabilities: vi.fn(),
  askAssistant: vi.fn(),
}))
vi.mock('@/api/assistant', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/assistant')>()),
  fetchAssistantCapabilities: api.fetchAssistantCapabilities,
  askAssistant: api.askAssistant,
}))

/** spec 015 US3·US4, FR-003·FR-004·FR-010·FR-012·FR-015(T041). */
describe('AssistantBubble', () => {
  beforeEach(() => {
    window.localStorage.clear()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    api.fetchAssistantCapabilities.mockReset()
    api.askAssistant.mockReset()
    api.fetchAssistantCapabilities.mockResolvedValue({ enabled: true, scopes: ['RESULT'] })
    api.askAssistant.mockResolvedValue(answered)
  })

  afterEach(() => {
    clearMatchMedia()
  })

  async function open(user: ReturnType<typeof userEvent.setup>) {
    const fab = await screen.findByRole('button', { name: '질문하기' })
    await user.click(fab)
    return { fab, dialog: await screen.findByRole('dialog', { name: '질문하기' }) }
  }

  it('열면 맥락 표시·예시 질문·입력 하나·전송 안내가 있고 초점은 입력에 있다', async () => {
    const user = userEvent.setup()
    renderAssistant()

    const { dialog } = await open(user)

    expect(within(dialog).getByText('기준 결과에 질문')).not.toBeNull()
    expect(within(dialog).getByText(TRANSFER_NOTICE)).not.toBeNull()
    const examples = within(dialog).getByRole('list', { name: '예시 질문' })
    expect(within(examples).getAllByRole('button')).toHaveLength(EXAMPLE_QUESTIONS.length)
    expect(within(dialog).getAllByRole('textbox')).toHaveLength(1)
    expect(within(dialog).queryByRole('radio')).toBeNull()
    expect(within(dialog).queryByRole('combobox')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(within(dialog).getByRole('textbox')))
    expect(api.askAssistant).not.toHaveBeenCalled()
  })

  it('왼쪽 여백에 있으면 오른쪽으로 펼치고, 팝오버는 뒤 화면을 막지 않는다', async () => {
    window.localStorage.setItem(FAB_POSITION_KEY, JSON.stringify({ side: 'left', y: 200 }))
    const user = userEvent.setup()
    renderAssistant()

    const { dialog } = await open(user)

    expect(dialog.getAttribute('data-side')).toBe('right')
    expect(dialog.getAttribute('data-shell')).toBe('popover')
    expect(dialog.getAttribute('aria-modal')).not.toBe('true')
  })

  it('예시 질문을 누르면 그 질문이 보내지고 답만 보인다', async () => {
    const user = userEvent.setup()
    renderAssistant()
    const { dialog } = await open(user)

    await user.click(within(dialog).getByRole('button', { name: EXAMPLE_QUESTIONS[0] }))

    expect(api.askAssistant).toHaveBeenCalledTimes(1)
    expect(api.askAssistant.mock.calls[0][0]).toBe(EXAMPLE_QUESTIONS[0])
    expect(api.askAssistant.mock.calls[0][1]).toEqual({ kind: 'RESULT', publicToken: TOKEN })
    expect(await within(dialog).findByText(answered.answer)).not.toBeNull()
    expect(within(dialog).queryByRole('list', { name: '근거' })).toBeNull()
    expect(within(dialog).queryByText(TRANSFER_NOTICE)).toBeNull()
  })

  it('직접 입력해 보내면 입력이 비고 300자를 넘게 칠 수 없다', async () => {
    const user = userEvent.setup()
    renderAssistant()
    const { dialog } = await open(user)
    const input = within(dialog).getByRole('textbox') as HTMLTextAreaElement

    expect(input.maxLength).toBe(300)
    await user.type(input, '거래비용은?')
    await user.click(within(dialog).getByRole('button', { name: '보내기' }))

    expect(api.askAssistant.mock.calls[0][0]).toBe('거래비용은?')
    await within(dialog).findByText(answered.answer)
    expect(input.value).toBe('')

    // Enter 로 보내도 입력이 빈다.
    await user.type(input, '수익률은?{Enter}')
    expect(api.askAssistant.mock.calls[1][0]).toBe('수익률은?')
    expect(input.value).toBe('')
  })

  it('Escape 로 닫히고 초점이 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    renderAssistant()
    const { fab } = await open(user)

    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '질문하기' })).toBeNull())
    expect(document.activeElement).toBe(fab)
    expect(fab.getAttribute('aria-expanded')).toBe('false')
  })

  it('바깥을 누르면 닫힌다', async () => {
    const user = userEvent.setup()
    renderAssistant()
    await open(user)

    await user.click(document.body)

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '질문하기' })).toBeNull())
  })

  it('닫았다 다시 열어도 같은 화면이면 질문과 답이 남아 있다', async () => {
    const user = userEvent.setup()
    renderAssistant()
    const { dialog } = await open(user)
    await user.click(within(dialog).getByRole('button', { name: EXAMPLE_QUESTIONS[0] }))
    await within(dialog).findByText(answered.answer)

    await user.keyboard('{Escape}')
    const reopened = await open(user)

    expect(within(reopened.dialog).getByText(EXAMPLE_QUESTIONS[0])).not.toBeNull()
    expect(within(reopened.dialog).getByText(answered.answer)).not.toBeNull()
    expect(within(reopened.dialog).queryByText(TRANSFER_NOTICE)).toBeNull()
  })

  it('화면이 바뀌면 진행 중 요청을 취소하고 말풍선을 닫으며 이전 답을 새 화면에 보이지 않는다', async () => {
    let resolveFirst: (answer: typeof answered) => void = () => {}
    let firstSignal: AbortSignal | undefined
    api.askAssistant.mockImplementation((_q: string, _c: unknown, signal?: AbortSignal) => {
      firstSignal = signal
      return new Promise((resolve) => {
        resolveFirst = resolve
      })
    })
    const user = userEvent.setup()
    const view = renderAssistant()
    const { dialog } = await open(user)
    await user.click(within(dialog).getByRole('button', { name: EXAMPLE_QUESTIONS[0] }))
    expect(within(dialog).getByRole('status').textContent).toContain('답을 만들고')

    // 다른 결과로 이동 — 실제 앱에서는 라우트 전환으로 `ResultPage`가 새 토큰을 등록한다.
    view.unmount()
    renderAssistant({ path: `/results/${OTHER_TOKEN}` })
    await screen.findByRole('button', { name: '질문하기' })
    expect(firstSignal?.aborted).toBe(true)
    expect(screen.queryByRole('dialog', { name: '질문하기' })).toBeNull()

    await act(async () => {
      resolveFirst(answered)
    })
    const reopened = await open(user)
    expect(within(reopened.dialog).queryByText(answered.answer)).toBeNull()
    expect(within(reopened.dialog).getByText(TRANSFER_NOTICE)).not.toBeNull()
  })

  it('한도 초과(429)는 다시 가능한 시점을 보이고 재시도 버튼이 없다', async () => {
    api.askAssistant.mockRejectedValue(
      new AssistantError('질문 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.', {
        code: 'ASSISTANT_LIMIT',
        status: 429,
        retryAfterSeconds: 42,
      }),
    )
    const user = userEvent.setup()
    renderAssistant()
    const { dialog } = await open(user)

    await user.click(within(dialog).getByRole('button', { name: EXAMPLE_QUESTIONS[0] }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('질문 한도에 도달했습니다')
    expect(alert.textContent).toContain('42초 뒤')
    expect(within(alert).queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('모델 장애(503)는 고정 문구와 재시도 버튼이고 자동으로 다시 보내지 않는다', async () => {
    api.askAssistant.mockRejectedValueOnce(
      new AssistantError('지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요.', {
        code: 'ASSISTANT_UNAVAILABLE',
        status: 503,
      }),
    )
    const user = userEvent.setup()
    renderAssistant()
    const { dialog } = await open(user)

    await user.click(within(dialog).getByRole('button', { name: EXAMPLE_QUESTIONS[0] }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요.')
    expect(api.askAssistant).toHaveBeenCalledTimes(1)

    await user.click(within(alert).getByRole('button', { name: '다시 시도' }))

    expect(api.askAssistant).toHaveBeenCalledTimes(2)
    expect(api.askAssistant.mock.calls[1][0]).toBe(EXAMPLE_QUESTIONS[0])
    expect(await within(dialog).findByText(answered.answer)).not.toBeNull()
  })

  it('범위 밖·근거 부족 답은 고정 문장만 보이고 근거 카드가 없다', async () => {
    api.askAssistant.mockResolvedValue({
      status: 'OUT_OF_SCOPE',
      answer: '이 도우미는 지금 보고 있는 기준 결과의 기록을 설명합니다.',
      evidence: [],
      page: { kind: 'RESULT' },
    })
    const user = userEvent.setup()
    renderAssistant()
    const { dialog } = await open(user)

    await user.click(within(dialog).getByRole('button', { name: EXAMPLE_QUESTIONS[2] }))

    expect(await within(dialog).findByText(/기준 결과의 기록을 설명합니다/)).not.toBeNull()
    expect(within(dialog).queryByRole('list', { name: '근거' })).toBeNull()
  })

  it('좁은 화면에서는 바닥 시트이고 Escape 로 닫힌다', async () => {
    mockMatchMedia(true)
    const user = userEvent.setup()
    renderAssistant()

    const { fab, dialog } = await open(user)

    expect(dialog.getAttribute('data-shell')).toBe('sheet')
    expect(dialog.getAttribute('aria-modal')).toBe('false')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '질문하기' })).toBeNull())
    expect(document.activeElement).toBe(fab)
  })
})

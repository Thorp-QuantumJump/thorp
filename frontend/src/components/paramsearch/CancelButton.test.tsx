import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CancelButton } from './CancelButton'
import { RetryFailedButton } from './RetryFailedButton'

const apiMocks = vi.hoisted(() => ({
  cancelParamSearch: vi.fn(),
  retryFailedCandidates: vi.fn(),
}))

vi.mock('@/api/paramSearch', async () => {
  const actual = await vi.importActual<typeof import('@/api/paramSearch')>('@/api/paramSearch')
  return { ...actual, ...apiMocks }
})

const searchId = 'PS-20260914-000011'

/**
 * 취소와 재시도(T107·T107a, FR-009·FR-024).
 *
 * <p>둘 다 <b>되돌릴 수 없거나 부분적으로만 듣는</b> 동작이라 화면이 무엇이 일어났는지 정확히
 * 말해야 한다. 취소는 큐에 든 후보를 되부르지 못하고, 재시도는 발행 창을 넘겨 보내지 못한다.
 */
describe('CancelButton', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('한 번 더 묻기 전에는 취소를 보내지 않는다', async () => {
    const user = userEvent.setup()
    render(<CancelButton searchId={searchId} onCancelled={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /탐색 멈추기/ }))

    expect(apiMocks.cancelParamSearch).not.toHaveBeenCalled()
    expect(screen.getByText(/되돌릴 수 없습니다/)).toBeTruthy()
  })

  it('계속 실행을 고르면 아무 일도 일어나지 않는다', async () => {
    const user = userEvent.setup()
    render(<CancelButton searchId={searchId} onCancelled={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /탐색 멈추기/ }))
    await user.click(screen.getByRole('button', { name: '계속 실행' }))

    expect(apiMocks.cancelParamSearch).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /탐색 멈추기/ })).toBeTruthy()
  })

  it('멈춘 뒤 미실행 수와 끝까지 도는 수를 함께 알린다', async () => {
    const user = userEvent.setup()
    const onCancelled = vi.fn()
    apiMocks.cancelParamSearch.mockResolvedValue({
      searchId,
      status: 'RUNNING',
      skipped: 1,
      inFlight: 4,
    })
    render(
      <CancelButton searchId={searchId} requesterId={7} onCancelled={onCancelled} />,
    )

    await user.click(screen.getByRole('button', { name: /탐색 멈추기/ }))
    await user.click(screen.getByRole('button', { name: '멈추기' }))

    await waitFor(() => expect(apiMocks.cancelParamSearch).toHaveBeenCalledWith(searchId, 7))
    // 발행된 메시지는 회수할 수 없다. 그 사실을 적지 않으면 취소가 안 먹은 것처럼 보인다.
    expect(await screen.findByText(/미실행 1개를 확정했습니다/)).toBeTruthy()
    expect(screen.getByText(/4개는 끝까지 계산합니다/)).toBeTruthy()
    expect(onCancelled).toHaveBeenCalledTimes(1)
  })

  it('이미 끝난 묶음이면 서버가 준 사유를 보인다', async () => {
    const user = userEvent.setup()
    const { ParamSearchError } = await import('@/api/paramSearch')
    apiMocks.cancelParamSearch.mockRejectedValue(
      new ParamSearchError('이미 끝난 탐색입니다.', { code: 'ALREADY_FINISHED', status: 409 }),
    )
    render(<CancelButton searchId={searchId} onCancelled={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /탐색 멈추기/ }))
    await user.click(screen.getByRole('button', { name: '멈추기' }))

    expect((await screen.findByRole('alert')).textContent).toContain('이미 끝난 탐색입니다.')
  })
})

describe('RetryFailedButton', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('실패한 후보가 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(
      <RetryFailedButton searchId={searchId} failedCount={0} onRetried={vi.fn()} />,
    )

    expect(container.innerHTML).toBe('')
  })

  it('실패가 있으면 그 수를 보이고 같은 조건으로 다시 돈다고 밝힌다', () => {
    const { container } = render(
      <RetryFailedButton searchId={searchId} failedCount={2} onRetried={vi.fn()} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('실패한 후보 2개')
    expect(text).toContain('새 묶음을 만들지 않습니다')
  })

  it('발행 창을 넘겨 남은 실패가 있으면 다시 누르라고 알린다', async () => {
    const user = userEvent.setup()
    apiMocks.retryFailedCandidates.mockResolvedValue({
      searchId,
      status: 'RUNNING',
      retried: 4,
      remainingFailed: 2,
    })
    render(
      <RetryFailedButton
        searchId={searchId}
        failedCount={6}
        requesterId={7}
        onRetried={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: /다시 시도/ }))

    await waitFor(() => expect(apiMocks.retryFailedCandidates).toHaveBeenCalledWith(searchId, 7))
    expect(await screen.findByText(/4개를 다시 보냈습니다/)).toBeTruthy()
    // 자동으로 이어 보내지 않는다 — 사용자가 멈출 자리를 남긴다.
    expect(screen.getByText(/2개가 남았습니다. 다시 눌러 주세요/)).toBeTruthy()
  })

  it('남은 실패가 없으면 그렇게 알린다', async () => {
    const user = userEvent.setup()
    apiMocks.retryFailedCandidates.mockResolvedValue({
      searchId,
      status: 'RUNNING',
      retried: 1,
      remainingFailed: 0,
    })
    render(<RetryFailedButton searchId={searchId} failedCount={1} onRetried={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /다시 시도/ }))

    expect(await screen.findByText(/남은 실패 후보는 없습니다/)).toBeTruthy()
  })
})

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, resetCsrfToken } from '@/api/client'
import { LearningResetSection } from './LearningResetSection'

/**
 * 013 T195·T196. 회원정보의 '학습 초기화'(FR-025, research R36, quickstart 10-9).
 */

function axiosResponse<T>(data: T, status = 200): AxiosResponse<T> {
  return { data, status, statusText: 'OK', headers: {}, config: { headers: new AxiosHeaders() } }
}

describe('LearningResetSection', () => {
  beforeEach(() => {
    resetCsrfToken()
    document.cookie = 'XSRF-TOKEN=synthetic-reset-token; path=/'
    // 상태 변경 전 CSRF 준비 요청(`GET /auth/csrf`)은 성공으로 둔다.
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse({ success: true, data: null }))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('누르면 지워지는 것과 되살릴 수 없다는 안내로 확인을 받고, 확인 전에는 요청하지 않는다', async () => {
    const remove = vi.spyOn(apiClient, 'delete')
    render(<LearningResetSection />)

    await userEvent.click(screen.getByRole('button', { name: '학습 초기화' }))

    const dialog = screen.getByRole('dialog', { name: '학습 기록을 모두 지울까요?' })
    expect(dialog.textContent).toContain(
      '완료한 글, 읽은 위치, 확인 문제의 첫 답, 익힘, 경험치, 배지를 모두 지웁니다.',
    )
    expect(dialog.textContent).toContain('지운 기록은 되살릴 수 없습니다.')
    expect(remove).not.toHaveBeenCalled()
  })

  it('취소하면 요청하지 않는다', async () => {
    const remove = vi.spyOn(apiClient, 'delete')
    render(<LearningResetSection />)

    await userEvent.click(screen.getByRole('button', { name: '학습 초기화' }))
    await userEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(remove).not.toHaveBeenCalled()
  })

  it('확인하면 X-XSRF-TOKEN을 실은 DELETE /learning/me 하나를 보내고 결과를 알린다', async () => {
    const setRequestHeader = vi.spyOn(XMLHttpRequest.prototype, 'setRequestHeader')
    const open = vi.spyOn(XMLHttpRequest.prototype, 'open')
    vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(() => {
      throw new Error('테스트는 요청을 내보내지 않는다')
    })
    render(<LearningResetSection />)

    await userEvent.click(screen.getByRole('button', { name: '학습 초기화' }))
    await userEvent.click(screen.getByRole('button', { name: '모두 지우기' }))

    await waitFor(() => expect(open).toHaveBeenCalledTimes(1))
    expect(open.mock.calls[0][0]).toBe('DELETE')
    expect(String(open.mock.calls[0][1])).toMatch(/\/learning\/me$/)
    const headers = Object.fromEntries(
      setRequestHeader.mock.calls.map(([name, value]) => [name, value]),
    )
    expect(headers['X-XSRF-TOKEN']).toBe('synthetic-reset-token')
    // 보내지 못했으니 지우지 못했다고 알리고, 지웠다고 말하지 않는다.
    expect((await screen.findByRole('alert')).textContent).toContain(
      '학습 기록을 지우지 못했습니다.',
    )
    expect(screen.queryByText('학습 기록을 모두 지웠습니다.')).toBeNull()
  })

  it('지우면 확인 창을 닫고 지웠다고 알린다', async () => {
    vi.spyOn(apiClient, 'delete').mockResolvedValue(axiosResponse(null, 204))
    render(<LearningResetSection />)

    await userEvent.click(screen.getByRole('button', { name: '학습 초기화' }))
    await userEvent.click(screen.getByRole('button', { name: '모두 지우기' }))

    expect((await screen.findByRole('status')).textContent).toContain(
      '학습 기록을 모두 지웠습니다.',
    )
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('개발용 미리보기는 확인해도 지우지 않고 그렇다고 알린다', async () => {
    const remove = vi.spyOn(apiClient, 'delete')
    render(<LearningResetSection preview />)

    await userEvent.click(screen.getByRole('button', { name: '학습 초기화' }))
    await userEvent.click(screen.getByRole('button', { name: '모두 지우기' }))

    expect(screen.getByRole('status').textContent).toContain(
      '미리보기 화면이라 학습 기록을 지우지 않았습니다.',
    )
    expect(remove).not.toHaveBeenCalled()
  })
})

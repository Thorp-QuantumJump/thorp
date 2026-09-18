import axios from 'axios'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { LoadError } from './LoadError'

describe('조회 실패 표시', () => {
  it('실패 응답의 안내를 보여 주고 다시 시도할 수 있다', () => {
    const retry = vi.fn()
    const error = new axios.AxiosError('failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
      data: { httpStatus: 'SERVICE_UNAVAILABLE', isSuccess: false, message: '결과를 읽을 수 없습니다.', code: 503, result: null },
      status: 503, statusText: 'Service Unavailable', headers: {}, config: { headers: new axios.AxiosHeaders() },
    })
    render(<LoadError error={error} onRetry={retry} />)
    expect(screen.getByRole('alert')).toBeTruthy()
    expect(screen.getByText('결과를 읽을 수 없습니다.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(retry).toHaveBeenCalledOnce()
  })
})

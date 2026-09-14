import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  authenticatedUser,
  authenticatedUserSuccess,
  authError,
  signupSuccess,
} from '@/test/authFixtures'
import { type ApiClientError, apiClient, resetCsrfToken } from './client'
import {
  confirmEmailVerification,
  getCurrentUser,
  login,
  logout,
  normalizeEmail,
  requestEmailVerification,
  signup,
} from './auth'

function axiosResponse<T>(data: T, status = 200): AxiosResponse<T> {
  return {
    data,
    status,
    statusText: status >= 400 ? 'Error' : 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

describe('auth API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    resetCsrfToken()
  })

  it('이메일을 정규화하고 CSRF를 준비한 뒤 인증번호를 요청한다', async () => {
    const csrfSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const postSpy = vi.spyOn(apiClient, 'post').mockResolvedValue(axiosResponse(null, 204))

    await requestEmailVerification({ email: '  New.User@Example.COM ' })

    expect(normalizeEmail('  New.User@Example.COM ')).toBe('new.user@example.com')
    expect(csrfSpy).toHaveBeenCalledOnce()
    expect(csrfSpy).toHaveBeenCalledWith('/auth/csrf')
    expect(postSpy).toHaveBeenCalledWith(
      '/accounts/email-verifications',
      { email: 'new.user@example.com' },
      { signal: undefined },
    )
  })

  it('인증번호 확인 요청에는 정규화한 이메일과 6자리 번호만 전달한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const postSpy = vi.spyOn(apiClient, 'post').mockResolvedValue(axiosResponse(null, 204))

    await confirmEmailVerification({
      email: 'USER@EXAMPLE.COM ',
      verificationCode: '123456',
    })

    expect(postSpy).toHaveBeenCalledWith(
      '/accounts/email-verifications/confirm',
      { email: 'user@example.com', verificationCode: '123456' },
      { signal: undefined },
    )
  })

  it('회원가입 성공 envelope를 검증해 비민감 결과만 반환한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    vi.spyOn(apiClient, 'post').mockResolvedValue(axiosResponse(signupSuccess, 201))

    const result = await signup({
      email: ' NEW.USER@EXAMPLE.COM ',
      password: 'safe-password',
      nickname: '새사용자',
      phoneNumber: '01012345678',
    })

    expect(result).toEqual(signupSuccess.data)
  })

  it('서버 오류 envelope를 안정된 코드의 ApiClientError로 변환한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const response = axiosResponse(
      authError('DUPLICATE_EMAIL', '이미 가입된 이메일입니다.'),
      400,
    )
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, response),
    )

    await expect(requestEmailVerification({ email: 'used@example.com' })).rejects.toMatchObject({
      kind: 'api',
      code: 'DUPLICATE_EMAIL',
      message: '이미 가입된 이메일입니다.',
    })
  })

  it('가입 응답 구조가 다르면 값 없이 필드 타입만 담은 원인을 남긴다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    vi.spyOn(apiClient, 'post').mockResolvedValue(
      axiosResponse(
        {
          success: true,
          data: { userId: '42', email: 'new.user@example.com', status: 'ACTIVE' },
          error: null,
        },
        201,
      ),
    )

    const error = await signup({
      email: 'new.user@example.com',
      password: 'password1234',
      nickname: '새사용자',
      phoneNumber: '01012345678',
    }).catch((caught: unknown) => caught)

    expect(error).toMatchObject({ kind: 'unexpected', code: null })
    const cause = (error as ApiClientError).cause
    expect((cause as Error).message).toBe(
      'unexpected signup response shape: { userId: string, email: string, status: string }',
    )
    expect((cause as Error).message).not.toContain('new.user@example.com')
  })

  it('응답이 없는 오류를 사용자가 재시도할 수 있는 연결 오류로 구분한다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(new AxiosError('Network Error'))

    await expect(requestEmailVerification({ email: 'new@example.com' })).rejects.toMatchObject({
      kind: 'network',
      code: null,
    })
  })

  it('이메일을 정규화해 로그인하고 현재 사용자만 반환한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const postSpy = vi
      .spyOn(apiClient, 'post')
      .mockResolvedValue(axiosResponse(authenticatedUserSuccess))

    await expect(
      login({ email: ' User@Example.COM ', password: 'password1234' }),
    ).resolves.toEqual(authenticatedUser)
    expect(postSpy).toHaveBeenCalledWith(
      '/auth/sessions',
      { email: 'user@example.com', password: 'password1234' },
      { signal: undefined },
    )
  })

  it('현재 사용자 조회는 CSRF 준비 없이 세션 기준 사용자를 반환한다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse(authenticatedUserSuccess))

    await expect(getCurrentUser()).resolves.toEqual(authenticatedUser)
    expect(getSpy).toHaveBeenCalledOnce()
    expect(getSpy).toHaveBeenCalledWith('/auth/me', { signal: undefined })
  })

  it('로그아웃은 CSRF를 준비한 뒤 현재 세션만 종료한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const deleteSpy = vi.spyOn(apiClient, 'delete').mockResolvedValue(axiosResponse(null, 204))

    await expect(logout()).resolves.toBeUndefined()
    expect(deleteSpy).toHaveBeenCalledWith('/auth/sessions/current', { signal: undefined })
  })

  it('로그인 실패 envelope를 안정된 인증 오류로 변환한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(null, 204))
    const response = axiosResponse(
      authError('INVALID_CREDENTIALS', '이메일 또는 비밀번호를 확인해 주세요.'),
      401,
    )
    vi.spyOn(apiClient, 'post').mockRejectedValue(
      new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, response),
    )

    await expect(
      login({ email: 'user@example.com', password: 'wrong-password' }),
    ).rejects.toMatchObject({ kind: 'api', code: 'INVALID_CREDENTIALS' })
  })
})

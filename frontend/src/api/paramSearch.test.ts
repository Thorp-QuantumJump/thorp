import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 007 상태 변경 요청의 CSRF 처리(S15P21E205-396).
 *
 * 이 모듈만 공용 CSRF 준비 절차를 건너뛰어 미리보기·실행·취소·실패 재시도가 모두 403
 * `CSRF_TOKEN_INVALID`로 거절됐다. 네 경로가 준비 절차를 거치는지와, 그 절차가 오류를 한 겹
 * 덮어 던져도 서버가 준 오류 이름이 남는지를 고정한다.
 */

const clientMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  runCsrfProtectedMutation: vi.fn(),
}))

vi.mock('./client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client')>()
  return {
    ...actual,
    apiClient: { get: clientMocks.get, post: clientMocks.post },
    runCsrfProtectedMutation: clientMocks.runCsrfProtectedMutation,
  }
})

const {
  cancelParamSearch,
  createParamSearch,
  previewParamSearch,
  retryFailedCandidates,
} = await import('./paramSearch')
const { ApiClientError } = await import('./client')

function ok<T>(data: T) {
  return { data: { success: true, data, error: null } }
}

function axiosFailure(status: number, body: unknown): AxiosError {
  const headers = new AxiosHeaders()
  return new AxiosError('failed', String(status), { headers }, null, {
    status,
    statusText: '',
    headers,
    config: { headers },
    data: body,
  })
}

const request = {
  universe: { instrumentIds: ['091160.XKRX', '091170.XKRX'] },
  strategy: { strategyIds: ['EQUAL_WEIGHT'] },
}

describe('api/paramSearch', () => {
  beforeEach(() => {
    clientMocks.get.mockReset()
    clientMocks.post.mockReset()
    clientMocks.runCsrfProtectedMutation.mockReset()
    clientMocks.runCsrfProtectedMutation.mockImplementation((send: () => Promise<unknown>) =>
      send(),
    )
  })

  it('미리보기·실행·취소·실패 재시도가 모두 CSRF 보호 mutation을 거친다', async () => {
    clientMocks.post.mockResolvedValue(ok({ searchId: 'PS-1', status: 'QUEUED' }))

    await previewParamSearch(request)
    await createParamSearch({ ...request, registryVersion: 'REG-1' })
    await cancelParamSearch('PS-1')
    await retryFailedCandidates('PS-1')

    expect(clientMocks.runCsrfProtectedMutation).toHaveBeenCalledTimes(4)
    expect(clientMocks.post.mock.calls.map((call) => call[0])).toEqual([
      '/param-searches/preview',
      '/param-searches',
      '/param-searches/PS-1/cancel',
      '/param-searches/PS-1/retry-failed',
    ])
  })

  it('CSRF 층이 덮어 던져도 서버가 준 오류 이름을 잃지 않는다', async () => {
    // 공용 mutation은 실패를 ApiClientError로 감싼다. 409 분기는 원래 오류에서 읽어야 산다.
    const changed = axiosFailure(409, {
      success: false,
      data: null,
      error: { code: 'REGISTRY_VERSION_CHANGED', message: '등록표가 바뀌었습니다' },
    })
    clientMocks.runCsrfProtectedMutation.mockRejectedValueOnce(
      new ApiClientError('api', '등록표가 바뀌었습니다', {
        code: 'REGISTRY_VERSION_CHANGED',
        cause: changed,
      }),
    )

    await expect(
      createParamSearch({ ...request, registryVersion: 'REG-1' }),
    ).rejects.toMatchObject({ code: 'REGISTRY_VERSION_CHANGED', status: 409 })
  })

  it('원래 오류가 남아 있지 않아도 이름과 문장은 지킨다', async () => {
    clientMocks.runCsrfProtectedMutation.mockRejectedValueOnce(
      new ApiClientError('api', '요청 확인 정보가 만료되었습니다.', {
        code: 'CSRF_TOKEN_INVALID',
      }),
    )

    await expect(previewParamSearch(request)).rejects.toMatchObject({
      code: 'CSRF_TOKEN_INVALID',
      message: '요청 확인 정보가 만료되었습니다.',
    })
  })
})

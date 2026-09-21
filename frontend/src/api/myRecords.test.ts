import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { analysisDetail, listPage } from '@/test/myRecordsFixtures'

/** 006 T016. contracts "GET /my-records"·"오류". */

const clientMocks = vi.hoisted(() => ({
  get: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
  post: vi.fn(),
  runCsrfProtectedMutation: vi.fn(),
}))

vi.mock('./client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client')>()
  return {
    ...actual,
    apiClient: {
      get: clientMocks.get,
      patch: clientMocks.patch,
      delete: clientMocks.delete,
      post: clientMocks.post,
      defaults: { baseURL: '/api' },
    },
    runCsrfProtectedMutation: clientMocks.runCsrfProtectedMutation,
  }
})

const {
  deleteMyRecord,
  exportMyRecordUrl,
  fetchMyRecord,
  fetchMyRecords,
  renameMyRecord,
  toSearchParams,
} = await import('./myRecords')
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

describe('api/myRecords', () => {
  beforeEach(() => {
    clientMocks.get.mockReset()
    clientMocks.patch.mockReset()
    clientMocks.delete.mockReset()
    clientMocks.runCsrfProtectedMutation.mockReset()
    clientMocks.runCsrfProtectedMutation.mockImplementation((request: () => Promise<unknown>) =>
      request(),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('배열 항목은 같은 이름을 여러 번 보내고 비운 값은 보내지 않는다', () => {
    const params = toSearchParams({
      kind: ['ANALYSIS', 'REGIME_SEARCH'],
      status: ['FAILED'],
      sector: [],
      sort: 'NAME_ASC',
      size: 20,
    })

    expect(params.getAll('kind')).toEqual(['ANALYSIS', 'REGIME_SEARCH'])
    expect(params.getAll('status')).toEqual(['FAILED'])
    expect(params.has('sector')).toBe(false)
    expect(params.get('sort')).toBe('NAME_ASC')
    expect(params.get('size')).toBe('20')
    // 상태를 비우면 서버의 기본 보기다. 화면이 기본값을 대신 채우지 않는다.
    expect(toSearchParams({}).toString()).toBe('')
  })

  it('목록은 질의 없이 기본 경로를, 있으면 질의 문자열을 붙여 부른다', async () => {
    clientMocks.get.mockResolvedValue(ok(listPage))
    const controller = new AbortController()

    await expect(fetchMyRecords({}, controller.signal)).resolves.toEqual(listPage)
    expect(clientMocks.get).toHaveBeenCalledWith('/my-records', { signal: controller.signal })

    await fetchMyRecords({ cursor: 'abc', status: ['COMPLETED', 'FAILED'] })
    expect(clientMocks.get).toHaveBeenLastCalledWith(
      '/my-records?cursor=abc&status=COMPLETED&status=FAILED',
      { signal: undefined },
    )
  })

  it('상세는 식별자를 경로에 넣고 껍데기를 벗긴다', async () => {
    clientMocks.get.mockResolvedValue(ok(analysisDetail))

    await expect(fetchMyRecord('MR-20260918-000042')).resolves.toEqual(analysisDetail)
    expect(clientMocks.get).toHaveBeenCalledWith('/my-records/MR-20260918-000042', {
      signal: undefined,
    })
  })

  it('남의 기록·없는 기록은 서버 코드 그대로 404 오류다', async () => {
    clientMocks.get.mockRejectedValue(
      axiosFailure(404, {
        success: false,
        data: null,
        error: { code: 'RESOURCE_NOT_FOUND', message: '요청한 리소스를 찾을 수 없습니다.' },
      }),
    )

    const error = await fetchMyRecord('MR-20260918-000099').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiClientError)
    expect((error as InstanceType<typeof ApiClientError>).is('RESOURCE_NOT_FOUND')).toBe(true)
  })

  it('success:false 가 200으로 와도 오류다 — null 이 화면까지 내려가지 않는다', async () => {
    clientMocks.get.mockResolvedValue({
      data: { success: false, data: null, error: { code: 'INVALID_INPUT', message: '잘못된 정렬' } },
    })

    await expect(fetchMyRecords({})).rejects.toMatchObject({ code: 'INVALID_INPUT' })
  })

  it('이름 변경·삭제는 CSRF 보호 mutation 으로 간다', async () => {
    clientMocks.patch.mockResolvedValue(ok(analysisDetail))
    clientMocks.delete.mockResolvedValue(
      ok({ recordId: 'MR-20260918-000042', deletedAt: '2026-09-21T00:00:00Z' }),
    )

    await renameMyRecord('MR-20260918-000042', '새 이름')
    expect(clientMocks.patch).toHaveBeenCalledWith('/my-records/MR-20260918-000042', {
      name: '새 이름',
    })
    await deleteMyRecord('MR-20260918-000042')
    expect(clientMocks.delete).toHaveBeenCalledWith('/my-records/MR-20260918-000042')
    expect(clientMocks.runCsrfProtectedMutation).toHaveBeenCalledTimes(2)
  })

  it('내보내기 주소는 형식을 질의로 붙인다', () => {
    expect(exportMyRecordUrl('MR-20260918-000042', 'csv')).toBe(
      '/api/my-records/MR-20260918-000042/export?format=csv',
    )
  })
})

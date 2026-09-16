import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '@/types/api'
import type { SectorCatalog } from '@/types/sector'
import { apiClient } from './client'
import { fetchSectorCatalog } from './sectors'

function axiosResponse<T>(data: T, status = 200): AxiosResponse<T> {
  return {
    data,
    status,
    statusText: status >= 400 ? 'Error' : 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

function axiosFailure(status: number, body: unknown): AxiosError {
  const error = new AxiosError('request failed')
  error.response = axiosResponse(body, status) as AxiosResponse
  return error
}

const catalog: SectorCatalog = {
  mapVersion: 'SEM-2026-09-10-001',
  isSample: true,
  sectors: [
    {
      code: 'SEMICONDUCTOR',
      name: '반도체',
      classification: 'INDUSTRY',
      representativeEtf: {
        instrumentId: '091160.XKRX',
        name: 'KODEX 반도체',
        listedOn: '2006-06-27',
        benchmarkIndex: 'KRX 반도체',
      },
    },
  ],
}

const catalogSuccess: ApiSuccessEnvelope<SectorCatalog> = {
  success: true,
  data: catalog,
  error: null,
}

function errorEnvelope(code: ApiErrorEnvelope['error']['code'], message: string): ApiErrorEnvelope {
  return { success: false, data: null, error: { code, message } }
}

describe('섹터 목록 조회', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('공용 apiClient로 GET /sectors를 한 번만 부른다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(catalogSuccess))

    const result = await fetchSectorCatalog()

    expect(getSpy).toHaveBeenCalledOnce()
    expect(getSpy).toHaveBeenCalledWith('/sectors', { signal: undefined })
    expect(result).toEqual({ status: 'loaded', catalog })
  })

  it('평가 가능 기간 엔드포인트를 부르지 않는다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(catalogSuccess))

    await fetchSectorCatalog()

    const calledPaths = getSpy.mock.calls.map((call) => call[0])
    expect(calledPaths).not.toContain('/sectors/evaluation-window')
  })

  it('봉투 안의 data를 목록으로 읽는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(catalogSuccess))

    const result = await fetchSectorCatalog()

    expect(result.status).toBe('loaded')
    if (result.status !== 'loaded') return
    expect(result.catalog.mapVersion).toBe('SEM-2026-09-10-001')
    expect(result.catalog.isSample).toBe(true)
    expect(result.catalog.sectors[0].representativeEtf.name).toBe('KODEX 반도체')
  })

  it('409 SECTOR_MAP_NOT_READY를 예외가 아니라 상태 값으로 돌려준다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(409, errorEnvelope('SECTOR_MAP_NOT_READY', '대응표가 아직 발행되지 않았다')),
    )

    const result = await fetchSectorCatalog()

    expect(result).toEqual({ status: 'mapNotReady', message: '대응표가 아직 발행되지 않았다' })
  })

  it('발행된 대응표에 섹터가 0개면 빈 목록으로 그리지 않고 따로 알린다', async () => {
    const empty: SectorCatalog = { mapVersion: 'SEM-2026-09-10-001', isSample: false, sectors: [] }
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: empty, error: null }),
    )

    const result = await fetchSectorCatalog()

    // 백엔드가 "대응표 미발행"(409)과 "섹터 0개"(200 + 빈 배열)를 일부러 구분해 내보낸다.
    expect(result).toEqual({ status: 'emptyCatalog', catalog: empty })
  })

  it('연결 실패는 network 분류이고 다시 불러 볼 수 있다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(new AxiosError('offline'))

    const result = await fetchSectorCatalog()

    expect(result.status).toBe('failed')
    if (result.status !== 'failed') return
    expect(result.kind).toBe('network')
    expect(result.retryable).toBe(true)
  })

  it('500 SECTOR_MAP_UNUSABLE은 api 분류이고 다시 불러도 달라지지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(500, errorEnvelope('SECTOR_MAP_UNUSABLE', '대응표를 읽을 수 없다')),
    )

    const result = await fetchSectorCatalog()

    expect(result).toEqual({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_MAP_UNUSABLE',
      message: '대응표를 읽을 수 없다',
      retryable: false,
    })
  })

  it('모르는 오류 이름이 와도 서버가 보낸 안내 문구를 잃지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(
        409,
        errorEnvelope('FUTURE_DOMAIN_CODE', '아직 이 화면이 모르는 이유로 막혀 있습니다.'),
      ),
    )

    const result = await fetchSectorCatalog()

    // 공용 오류 이름 목록이 다시 닫히면, 봉투를 읽는 쪽이 이 응답을 통째로 버리고
    // `unexpected`와 일반 문구로 바꾼다. 그 되돌림을 이 검사가 잡는다.
    expect(result).toEqual({
      status: 'failed',
      kind: 'api',
      code: 'FUTURE_DOMAIN_CODE',
      message: '아직 이 화면이 모르는 이유로 막혀 있습니다.',
      retryable: true,
    })
  })

  it('대응표 미발행이 아닌 409는 준비 중 상태로 다루지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(409, errorEnvelope('INSTRUMENT_PROFILE_NOT_READY', '상품 정보가 없다')),
    )

    const result = await fetchSectorCatalog()

    expect(result.status).toBe('failed')
  })

  it('대표 ETF가 빠진 항목이 오면 숨기지 않고 응답 전체를 읽지 못한 것으로 다룬다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        error: null,
        data: {
          mapVersion: 'SEM-2026-09-10-001',
          isSample: false,
          sectors: [{ code: 'BANK', name: '은행', classification: 'INDUSTRY' }],
        },
      }),
    )

    const result = await fetchSectorCatalog()

    expect(result.status).toBe('failed')
    if (result.status !== 'failed') return
    expect(result.kind).toBe('unexpected')
  })

  it('200으로 온 실패 봉투도 같은 상태 값으로 옮긴다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse(errorEnvelope('SECTOR_MAP_NOT_READY', '준비 중')),
    )

    const result = await fetchSectorCatalog()

    expect(result).toEqual({ status: 'mapNotReady', message: '준비 중' })
  })
})

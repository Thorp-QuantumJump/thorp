import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '@/types/api'
import type { InstrumentProfile, PriceSeries, SectorCatalog } from '@/types/sector'
import { apiClient } from './client'
import {
  fetchEvaluationWindow,
  fetchInstrumentProfile,
  fetchPriceSeries,
  fetchSectorCatalog,
  readEvaluationWindow,
} from './sectors'

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

const profile: InstrumentProfile = {
  instrumentId: '091160.XKRX',
  name: 'KODEX 반도체',
  benchmarkIndex: 'KRX 반도체',
  indexMethodologySummary: 'KRX 반도체 지수를 추종하며 시가총액 가중으로 담는다.',
  listedOn: '2006-06-27',
  manager: '삼성자산운용',
  holdingsOverlap: {
    asOfCurrentComposition: true,
    topHoldings: [
      { name: '삼성전자', weight: '0.2431' },
      { name: 'SK하이닉스', weight: '0.2107' },
    ],
  },
  avgDailyValue3m: '1240000000',
  sourceAttribution: '한국거래소 통계정보',
  survivorshipNote: '현재 상장되어 있는 상품만 다룹니다.',
  datasetId: 'KRX_DAILY_20260907',
  dataDeadline: '2026-09-07T09:00:00Z',
  isSample: true,
}

const profileSuccess: ApiSuccessEnvelope<InstrumentProfile> = {
  success: true,
  data: profile,
  error: null,
}

describe('대표 상품 정보 조회', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('공용 apiClient로 해당 섹터의 경로를 부른다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(profileSuccess))

    const result = await fetchInstrumentProfile('SEMICONDUCTOR')

    expect(getSpy).toHaveBeenCalledWith('/sectors/SEMICONDUCTOR/instrument-profile', {
      signal: undefined,
    })
    expect(result).toEqual({ status: 'loaded', profile })
  })

  it('섹터 코드를 주소에 안전하게 싣는다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(profileSuccess))

    await fetchInstrumentProfile('A/B?C')

    expect(getSpy).toHaveBeenCalledWith('/sectors/A%2FB%3FC/instrument-profile', {
      signal: undefined,
    })
  })

  it('409는 예외가 아니라 화면이 분기할 상태 값으로 돌려준다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(409, errorEnvelope('INSTRUMENT_PROFILE_NOT_READY', '상품 정보 준비 중')),
    )

    const result = await fetchInstrumentProfile('SEMICONDUCTOR')

    expect(result).toEqual({ status: 'notReady', message: '상품 정보 준비 중' })
  })

  it('없는 섹터는 다시 물어도 달라지지 않으므로 행동을 주지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(404, errorEnvelope('SECTOR_NOT_FOUND', '섹터를 찾지 못했습니다')),
    )

    const result = await fetchInstrumentProfile('GONE')

    expect(result).toMatchObject({ status: 'failed', retryable: false })
  })

  it('연결 실패는 다시 눌러 달라질 수 있는 상태로 둔다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(new AxiosError('Network Error'))

    const result = await fetchInstrumentProfile('SEMICONDUCTOR')

    expect(result).toMatchObject({ status: 'failed', kind: 'network', retryable: true })
  })

  it('보유 종목 항목이 깨져 있으면 나머지만 그리지 않고 전체를 읽지 못한 것으로 다룬다', async () => {
    // 하나를 빼고 나머지만 보이면 합이 맞지 않는 구성이 사실처럼 보인다.
    const broken = {
      ...profile,
      holdingsOverlap: {
        asOfCurrentComposition: true,
        topHoldings: [{ name: '삼성전자', weight: 0.2431 }],
      },
    }
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: broken, error: null }),
    )

    const result = await fetchInstrumentProfile('SEMICONDUCTOR')

    expect(result).toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('비중과 거래대금을 숫자로 바꾸지 않고 문자열 그대로 둔다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(profileSuccess))

    const result = await fetchInstrumentProfile('SEMICONDUCTOR')

    // 소수로 옮겨 두면 표시 단계에서 부동소수 오차가 섞인다.
    expect(result).toMatchObject({
      status: 'loaded',
      profile: { avgDailyValue3m: '1240000000' },
    })
    if (result.status !== 'loaded') throw new Error('loaded가 아니다')
    expect(result.profile.holdingsOverlap.topHoldings[0]?.weight).toBe('0.2431')
  })
})

describe('보유 종목 불변식', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  function withHoldings(topHoldings: unknown): ApiSuccessEnvelope<InstrumentProfile> {
    return {
      success: true,
      data: { ...profile, holdingsOverlap: { asOfCurrentComposition: true, topHoldings } },
      error: null,
    } as ApiSuccessEnvelope<InstrumentProfile>
  }

  it.each([['빈 문자열', ''], ['숫자가 아닌 값', 'N/A'], ['0', '0'], ['음수', '-0.1'], ['1 초과', '1.5']])(
    '비중이 %s이면 그 항목만 0%%로 그리지 않고 전체를 읽지 못한 것으로 다룬다',
    async (_label, weight) => {
      /*
        문자열인지만 보면 통과하는 값들이다. 그대로 두면 그 종목이 0.00%로 그려지고 실제 몫은
        '그 외'에 섞여, 합이 맞지 않는 구성이 사실처럼 보인다.
      */
      vi.spyOn(apiClient, 'get').mockResolvedValue(
        axiosResponse(withHoldings([{ name: '삼성전자', weight }])),
      )

      expect(await fetchInstrumentProfile('SEMICONDUCTOR')).toMatchObject({
        status: 'failed',
        kind: 'unexpected',
      })
    },
  )

  it('보유 종목이 하나도 없으면 받지 않는다', async () => {
    // 빈 목록을 받아들이면 화면이 '그 외 100%' 막대 하나를 그려, 모른다는 사실을 자료처럼 보인다.
    vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(withHoldings([])))

    expect(await fetchInstrumentProfile('SEMICONDUCTOR')).toMatchObject({
      status: 'failed',
      kind: 'unexpected',
    })
  })

  it('범위 안의 비중은 문자열 그대로 통과한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse(withHoldings([{ name: '삼성전자', weight: '1' }])),
    )

    const result = await fetchInstrumentProfile('SEMICONDUCTOR')
    expect(result.status).toBe('loaded')
  })

  it('망가진 대응표의 500은 다시 눌러도 달라지지 않는 상태로 둔다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(500, errorEnvelope('SECTOR_MAP_UNUSABLE', '대응표가 망가졌습니다')),
    )

    expect(await fetchInstrumentProfile('SEMICONDUCTOR')).toMatchObject({
      status: 'failed',
      code: 'SECTOR_MAP_UNUSABLE',
      retryable: false,
    })
  })
})

/** 응답에서 키를 빼 "그 필드가 오지 않은" 본문을 만든다. 빈 값으로 두는 것과 키가 없는 것은 계약상 다르다. */
function omitKeys<T extends object>(body: T, ...keys: (keyof T)[]): Partial<T> {
  return Object.fromEntries(
    Object.entries(body).filter(([key]) => !keys.includes(key as keyof T)),
  ) as Partial<T>
}

describe('공통 평가 기간 조회(T042)', () => {
  const windowBody = {
    commonStart: '2024-01-04',
    commonEnd: '2026-01-05',
    warmupTradingDays: 2,
    bindingCandidate: '000001.XKRX',
    bindingSector: 'SEC-A',
    excludedRanges: [{ start: '2024-03-04', end: '2024-03-08', reason: 'SUSPENDED_TOO_LONG' }],
    evaluable: true,
    datasetId: 'TEST-DATASET-1',
    warmupBaseDate: '2024-01-02',
    tradingDaysFromBase: ['2024-01-02', '2024-01-03', '2024-01-04'],
    maxWarmupTradingDays: 2,
    excludedRangesFromBase: [{ start: '2024-03-04', end: '2024-03-08', reason: 'SUSPENDED_TOO_LONG' }],
  }

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('섹터를 쉼표로 잇고 상한을 실어 GET /sectors/evaluation-window를 부른다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse({ success: true, data: windowBody, error: null }))

    const window = await fetchEvaluationWindow({ sectors: ['SEC-A', 'SEC-B'], maxWarmupTradingDays: 2 })

    expect(window).toEqual(windowBody)
    expect(getSpy).toHaveBeenCalledTimes(1)
    const [url, config] = getSpy.mock.calls[0]
    expect(url).toBe('/sectors/evaluation-window')
    expect(config?.params).toEqual({ sectors: 'SEC-A,SEC-B', maxWarmupTradingDays: 2 })
  })

  it('전략을 주면 함께 싣는다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse({ success: true, data: windowBody, error: null }))

    await fetchEvaluationWindow({ sectors: ['SEC-A'], strategy: 'DRIFT_BALANCE', maxWarmupTradingDays: 2 })

    expect(getSpy.mock.calls[0][1]?.params).toMatchObject({ strategy: 'DRIFT_BALANCE' })
  })

  it('엔드포인트가 없으면(404) 거부한다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(404, errorEnvelope('RESOURCE_NOT_FOUND', '없는 주소입니다.')),
    )

    await expect(
      fetchEvaluationWindow({ sectors: ['SEC-A'], maxWarmupTradingDays: 2 }),
    ).rejects.toMatchObject({ kind: 'api', code: 'RESOURCE_NOT_FOUND' })
  })

  it('합의한 필드 셋이 없는 응답(지금 001 계약의 모양)은 읽지 못한 것으로 거부한다', async () => {
    const legacy = omitKeys(
      windowBody,
      'warmupBaseDate',
      'tradingDaysFromBase',
      'maxWarmupTradingDays',
      'excludedRangesFromBase',
    )
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: legacy, error: null }),
    )

    await expect(
      fetchEvaluationWindow({ sectors: ['SEC-A'], maxWarmupTradingDays: 2 }),
    ).rejects.toMatchObject({ kind: 'unexpected' })
  })

  it.each([
    ['첫 거래일이 기준일이 아니다', { tradingDaysFromBase: ['2024-01-03', '2024-01-04', '2024-01-05'] }],
    ['거래일이 오름차순이 아니다', { tradingDaysFromBase: ['2024-01-02', '2024-01-04', '2024-01-03'] }],
    ['같은 거래일이 두 번 있다', { tradingDaysFromBase: ['2024-01-02', '2024-01-02', '2024-01-03'] }],
    ['상한이 목록 길이 - 1과 다르다', { maxWarmupTradingDays: 3 }],
    ['목록이 비었는데 상한이 왔다', { tradingDaysFromBase: [], maxWarmupTradingDays: 0 }],
    ['목록이 있는데 상한이 없다', { maxWarmupTradingDays: undefined }],
    ['마지막 거래일이 commonEnd를 넘는다', { commonEnd: '2024-01-03' }],
    ['시작일이 없는데 평가할 수 있다고 한다', { commonStart: null }],
    ['자료 부족 구간(기준일부터)이 없다', { excludedRangesFromBase: undefined }],
    ['기준 섹터가 없다', { bindingSector: undefined }],
  ])('%s면 계산이 조용히 틀리므로 응답 전체를 읽지 않는다', (_, patch) => {
    expect(readEvaluationWindow({ ...windowBody, ...patch })).toBeNull()
  })

  it('워밍업을 채울 거래일이 모자라면 시작일 없이 분석할 수 없다는 응답도 읽는다', () => {
    const window = readEvaluationWindow({ ...windowBody, commonStart: null, evaluable: false })
    expect(window).toMatchObject({ commonStart: null, evaluable: false, maxWarmupTradingDays: 2 })
  })

  it('기준일이 commonEnd를 넘어 목록이 비고 상한 키가 없는 응답을 읽는다', () => {
    const window = readEvaluationWindow({
      ...omitKeys(windowBody, 'maxWarmupTradingDays'),
      commonStart: null,
      commonEnd: '2023-12-29',
      evaluable: false,
      tradingDaysFromBase: [],
      excludedRanges: [],
      excludedRangesFromBase: [],
    })
    expect(window).toMatchObject({ tradingDaysFromBase: [], maxWarmupTradingDays: null })
  })

  it('요청한 상한보다 긴 목록이 오면 다른 요청의 응답으로 보고 거부한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: windowBody, error: null }),
    )

    await expect(
      fetchEvaluationWindow({ sectors: ['SEC-A'], maxWarmupTradingDays: 1 }),
    ).rejects.toMatchObject({ kind: 'unexpected' })
  })

  it('자료가 아직 없으면(409) 거부한다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(409, errorEnvelope('EVALUATION_WINDOW_NOT_READY', '자료를 준비하고 있습니다.')),
    )

    await expect(
      fetchEvaluationWindow({ sectors: ['SEC-A'], maxWarmupTradingDays: 2 }),
    ).rejects.toMatchObject({ kind: 'api', code: 'EVALUATION_WINDOW_NOT_READY' })
  })
})

const series: PriceSeries = {
  instrumentId: '117700.XKRX',
  datasetId: 'KRX_DAILY_20260922',
  dataDeadline: '2026-09-22T06:30:00Z',
  isSample: false,
  points: [
    {
      date: '2026-09-16',
      adjustedClose: '10000.00000000',
      tradingSuspended: false,
      priceCarriedForward: false,
    },
  ],
}

const seriesSuccess: ApiSuccessEnvelope<PriceSeries> = {
  success: true,
  data: series,
  error: null,
}

describe('가격 흐름 조회', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('공용 apiClient로 해당 섹터의 경로를 부른다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValue(axiosResponse(seriesSuccess))

    const result = await fetchPriceSeries('CONSTRUCTION')

    expect(getSpy).toHaveBeenCalledWith('/sectors/CONSTRUCTION/price-series', {
      signal: undefined,
    })
    expect(result).toEqual({ status: 'loaded', series })
  })

  it('409는 예외가 아니라 화면이 분기할 상태 값으로 돌려준다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(409, errorEnvelope('PRICE_SERIES_NOT_READY', '가격 자료 준비 중')),
    )

    const result = await fetchPriceSeries('CONSTRUCTION')

    expect(result).toEqual({ status: 'notReady', message: '가격 자료 준비 중' })
  })

  it('없는 섹터는 다시 물어도 달라지지 않으므로 행동을 주지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      axiosFailure(404, errorEnvelope('SECTOR_NOT_FOUND', '섹터를 찾지 못했습니다')),
    )

    const result = await fetchPriceSeries('GONE')

    expect(result).toMatchObject({ status: 'failed', retryable: false })
  })

  it('점이 하나도 없는 응답을 빈 선으로 받아들이지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: { ...series, points: [] }, error: null }),
    )

    // 빈 배열을 그대로 그리면 "움직임이 없었다"로 보인다. 읽지 못한 것으로 다룬다.
    const result = await fetchPriceSeries('CONSTRUCTION')

    expect(result).toMatchObject({ status: 'failed', kind: 'unexpected' })
  })

  it('숫자로 읽히지 않는 가격이 섞이면 그 응답을 통째로 버린다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({
        success: true,
        data: {
          ...series,
          points: [
            series.points[0],
            {
              date: '2026-09-17',
              adjustedClose: 'N/A',
              tradingSuspended: false,
              priceCarriedForward: false,
            },
          ],
        },
        error: null,
      }),
    )

    // 성한 점만 골라 그리면 빠진 날의 앞뒤가 이어져 없던 구간이 매끈한 선이 된다.
    const result = await fetchPriceSeries('CONSTRUCTION')

    expect(result).toMatchObject({ status: 'failed', kind: 'unexpected' })
  })
})

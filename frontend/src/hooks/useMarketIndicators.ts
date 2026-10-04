import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchLatestMarketIndicators } from '@/api/market'
import type { Indicator } from '@/types/market'
import type {
  MarketIndicatorBatch,
  MarketIndicatorCode,
  MarketIndicatorResponse,
} from '@/types/marketApi'

const frontendCacheTtl = 60_000
let cachedBatch: { value: MarketIndicatorBatch; storedAt: number } | null = null
let pendingRequest: Promise<MarketIndicatorBatch> | null = null

const requestCodeByUiCode: Record<string, MarketIndicatorCode> = {
  KOSPI: 'KOSPI',
  KOSDAQ: 'KOSDAQ',
  USD_KRW: 'USD_KRW',
  VKOSPI200: 'VKOSPI200',
  KTB_3Y: 'KTB_3Y',
  COINCIDENT_CYCLE: 'COINCIDENT_CYCLE',
  LEADING_CYCLE: 'LEADING_CYCLE',
  ESI_CYCLE: 'ESI_CYCLE',
  CORE_CPI_YOY: 'CORE_CPI_YOY',
  EXPORTS_USD: 'EXPORTS_USD',
  M2: 'M2',
}

const displayNameOverrides: Partial<Record<MarketIndicatorCode, string>> = {
  EXPORTS_USD: '월간 수출액',
  M2: '시중 유동성 (M2)',
}

/** 라우트 전환 시 같은 요청을 반복하지 않도록 짧은 브라우저 메모리 캐시를 공유한다. */
function loadLatestIndicators(force = false) {
  if (!force && cachedBatch && Date.now() - cachedBatch.storedAt < frontendCacheTtl) {
    return Promise.resolve(cachedBatch.value)
  }

  if (!pendingRequest) {
    pendingRequest = fetchLatestMarketIndicators()
      .then((value) => {
        cachedBatch = { value, storedAt: Date.now() }
        return value
      })
      .finally(() => {
        pendingRequest = null
      })
  }

  return pendingRequest
}

function applyLatestValue(
  indicator: Indicator,
  response: MarketIndicatorResponse,
): Indicator {
  return {
    ...indicator,
    name: displayNameOverrides[response.code] ?? indicator.name,
    value: response.value,
    unit: response.unit,
    observationDate: response.observationDate,
    retrievedAt: response.retrievedAt,
    source: {
      provider: response.source.provider,
      datasetName: response.source.datasetName,
      frequency: response.frequency,
    },
    dataStatus: response.status === 'stale' ? 'stale' : 'live',
    statusMessage: response.statusMessage,
  }
}

function mergeIndicators(
  indicators: Indicator[],
  batch: MarketIndicatorBatch | null,
): Indicator[] {
  return indicators.map((indicator) => {
    const requestCode = requestCodeByUiCode[indicator.code]
    if (!requestCode) return indicator

    const response = batch?.data[requestCode]
    if (response) return applyLatestValue(indicator, response)

    if (!batch) {
      return {
        ...indicator,
        dataStatus: 'loading',
        statusMessage: '최신 지표를 불러오는 중입니다.',
      }
    }

    return {
      ...indicator,
      dataStatus: 'error',
      statusMessage: batch.errors[requestCode] ?? '최신 지표를 불러오지 못했습니다.',
    }
  })
}

/** 최신 지표 조회 결과를 첫 화면이 쓰는 UI 지표 모델에 병합한다. */
export function useMarketIndicators(
  marketIndicators: Indicator[],
  macroIndicators: Indicator[],
) {
  const validCache = cachedBatch && Date.now() - cachedBatch.storedAt < frontendCacheTtl
    ? cachedBatch.value
    : null
  const [batch, setBatch] = useState<MarketIndicatorBatch | null>(validCache)
  const [isRefreshing, setIsRefreshing] = useState(false)

  useEffect(() => {
    let active = true

    loadLatestIndicators().then((value) => {
      if (active) setBatch(value)
    })

    return () => {
      active = false
    }
  }, [])

  const refetch = useCallback(async () => {
    setIsRefreshing(true)
    try {
      const value = await loadLatestIndicators(true)
      setBatch(value)
    } finally {
      setIsRefreshing(false)
    }
  }, [])

  return useMemo(() => ({
    marketIndicators: mergeIndicators(marketIndicators, batch),
    macroIndicators: mergeIndicators(macroIndicators, batch),
    isLoading: batch === null,
    isRefreshing,
    failedCodes: batch ? Object.keys(batch.errors) as MarketIndicatorCode[] : [],
    refetch,
  }), [batch, isRefreshing, macroIndicators, marketIndicators, refetch])
}

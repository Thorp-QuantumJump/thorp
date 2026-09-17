import { useEffect, useState } from 'react'
import { fetchMarketIndicatorsFor } from '@/api/market'
import type { MarketIndicatorCode, MarketIndicatorResponse } from '@/types/marketApi'
import styles from './MarketFlow.module.css'

/**
 * 국내 시장의 가격 추세와 변동성을 **경제환경 지표와 구분된 별도 영역**으로 보여 준다(FR-017).
 *
 * 여기 값은 경제환경 검색 입력에 쓰지 않는다. 그래서 이 컴포넌트는 값을 바깥으로 내보내는
 * 통로를 두지 않는다. props도 없고 콜백도 없다. 실어 보낼 자리가 없어야 실리지 않는다.
 *
 * 자료가 없으면 **이 영역만 비우고 분야 요약은 그대로 둔다**(spec Edge Case). 시장 참고
 * 정보가 늦거나 빠지는 것은 불편이고, 그 때문에 경제환경 표시가 막히는 것은 고장이다.
 */

/** FR-017이 말한 '광범위 국내 주가지수'와 그 변동성이다. */
const REFERENCE_CODES: readonly MarketIndicatorCode[] = ['KOSPI', 'VKOSPI200']

type ReferenceState =
  | { status: 'loading' }
  | { status: 'ready'; indicators: MarketIndicatorResponse[] }
  | { status: 'unavailable' }

function formatObservedOn(value: string): string {
  return value.replaceAll('-', '.')
}

export function DomesticMarketReference() {
  const [state, setState] = useState<ReferenceState>({ status: 'loading' })

  useEffect(() => {
    let active = true

    fetchMarketIndicatorsFor(REFERENCE_CODES)
      .then((batch) => {
        if (!active) return
        const indicators = REFERENCE_CODES.map((code) => batch.data[code]).filter(
          (indicator): indicator is MarketIndicatorResponse => indicator !== undefined,
        )
        setState(indicators.length > 0 ? { status: 'ready', indicators } : { status: 'unavailable' })
      })
      .catch(() => {
        if (active) setState({ status: 'unavailable' })
      })

    return () => {
      active = false
    }
  }, [])

  if (state.status === 'loading') {
    return (
      <div className={styles.referenceBlock}>
        <p className={styles.sectorNote} role="status">국내 시장 자료를 불러오는 중입니다.</p>
      </div>
    )
  }

  if (state.status === 'unavailable') {
    return (
      <div className={styles.referenceBlock}>
        <p className={styles.sectorNote}>국내 시장 참고 정보를 지금은 불러올 수 없습니다.</p>
      </div>
    )
  }

  return (
    <div className={styles.referenceBlock}>
      <dl className={styles.referenceValues}>
        {state.indicators.map((indicator) => (
          <div key={indicator.code}>
            <dt>{indicator.name}</dt>
            <dd>
              {indicator.value.toLocaleString('ko-KR')} <small>{indicator.unit}</small>
            </dd>
            <dd className={styles.referenceObserved}>{formatObservedOn(indicator.observationDate)} 기준</dd>
          </div>
        ))}
      </dl>
      {/*
        FR-015: 출처를 값과 함께 둔다. **응답이 준 이름을 그대로 쓰고 화면이 지어내지 않는다.**
        지표마다 원천이 달라(KRX, 한국은행 ECOS) 하나로 박아 두면 언젠가 틀린 출처를 보이게 된다.
      */}
      <p className={styles.referenceSource}>
        출처 · {[...new Set(state.indicators.map((indicator) => indicator.source.provider))].join(', ')}
      </p>
    </div>
  )
}

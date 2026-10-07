import { usePastSearch } from '@/hooks/usePastCases'
import type { RegimeBaselineMonths } from '@/types/regime'
import { PastCaseExplorer, type CaseFocusRequest } from './PastCaseExplorer'
import styles from './PastCompare.module.css'

const COMPOSITE_QUERY = { mode: 'composite', limit: 3 } as const

/**
 * 분야 메뉴의 'Thorp 종합 비교'를 고르면 상세 상자에 서는 화면(009 FR-019, 010 US3). 일곱 분야를 함께 견주므로
 * 한 분야의 지표 그래프에 겹칠 수 없다. 그래서 가까운 사례 목록과 사례 상세·기준월 지정·신뢰 범위 표시를 담은
 * '가까운 과거 사례' 구역이 바로 선다. 누를 때만 조회한다 — 이 컴포넌트는 종합 비교를 고른 동안에만 그려진다.
 *
 * 실패하면 서버가 실은 문장을 그대로 보인다 — 종합 기준월이 아직 계산되지 않은 환경에서는 다시 시도해도 같은
 * 결과라 그 사유를 알려야 한다(010 FR-037).
 */
export function CompositePast({ baselines, fieldNames, focusRequest = null }: {
  baselines: RegimeBaselineMonths | null
  fieldNames: Record<string, string>
  focusRequest?: CaseFocusRequest | null
}) {
  const { state, retry } = usePastSearch(COMPOSITE_QUERY)

  return (
    <section className={styles.composite} aria-labelledby="composite-past-title">
      <header className={styles.compositeHead}>
        <h2 id="composite-past-title">Thorp 종합 비교</h2>
      </header>
      <PastCaseExplorer
        id="market-flow-past-cases"
        mode="composite"
        scopeName="종합"
        base={state}
        onRetryBase={retry}
        baselines={baselines}
        fieldNames={fieldNames}
        focusRequest={focusRequest}
      />
    </section>
  )
}

import { ArrowUpRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { InlineHelp } from '@/components/common/InlineHelp'
import { ComparisonMonthsNotice } from '@/components/regime/ComparisonMonthsNotice'
import { Disclosure } from '@/components/regime/Disclosure'
import * as copy from '@/components/regime/fixedCopy'
import { usePastSearch } from '@/hooks/usePastCases'
import { buildCaseId, buildCompositeSearchPath, buildRegimeLinkStartPath } from '@/marketflow/handoff'
import { observationRange } from './pastFormat'
import styles from './PastCompare.module.css'

const COMPOSITE_QUERY = { mode: 'composite', limit: 3 } as const

/**
 * 분야 메뉴의 'Thorp 종합 비교'를 고르면 상세 상자에 서는 화면(009 FR-019, 010 US3). 일곱 분야를 함께 견주므로
 * 한 분야의 지표 그래프에 겹칠 수 없다. 그래서 사례마다 관측 구간과 비슷한 점·다른 점을 목록으로 보인다.
 * 누를 때만 조회한다 — 이 컴포넌트는 종합 비교를 고른 동안에만 그려진다.
 *
 * 010의 신뢰 범위 표시(예시 자료·빠진 비교 시점·자료 기준 시점)는 분야 줄과 같이 문구로 늘 보인다(010 FR-037).
 * 실패하면 서버가 실은 문장을 그대로 보인다 — 종합 기준월이 아직 계산되지 않은 환경에서는 다시 시도해도 같은
 * 결과라 그 사유를 알려야 한다.
 */
export function CompositePast({ onShowFields }: { onShowFields: () => void }) {
  const { state, retry } = usePastSearch(COMPOSITE_QUERY)
  const result = state.status === 'ready' ? state.result : null
  const context = result ? `종합 ${result.baselineMonth} 검색 결과` : ''

  return (
    <section className={styles.composite} aria-labelledby="composite-past-title">
      <header className={styles.compositeHead}>
        <div className={styles.titleGroup}>
          <h2 id="composite-past-title">Thorp 종합 비교</h2>
          {result ? (
            <InlineHelp label={`검색 상태${result.caseLabel ? '와 사례 구분' : ''}`} mark="i" side="bottom">
              <p><b>검색 상태</b> {result.statusNote}</p>
              {result.caseLabel ? <p><b>사례 구분</b> {result.caseLabel}</p> : null}
            </InlineHelp>
          ) : null}
        </div>
        {result ? <p className={styles.basis}>비교 기준월 {result.baselineMonth} · 가까운 순</p> : null}
      </header>
      <p className={styles.compositeLead}>{copy.compositeLead}</p>

      {state.status === 'loading' || state.status === 'idle' ? (
        <p className={styles.loading} role="status">{copy.loadingCases}</p>
      ) : state.status === 'failed' ? (
        <div className={styles.failure} role="status">
          <p>{state.message ?? copy.compositeLoadFailed}</p>
          <button type="button" className={styles.inlineAction} onClick={retry}>다시 시도</button>
        </div>
      ) : result ? (
        <>
          {/* 제목 옆 i 표시를 펼치지 않아도 보조기기에 읽히도록 같은 문장을 알림 영역에 남긴다(010 FR-037). */}
          <p
            className="sr-only"
            role="status"
            aria-live="polite"
            aria-label={`${context}: 검색 상태${result.caseLabel ? '와 사례 구분' : ''}`}
          >
            검색 상태: {result.statusNote}
            {result.caseLabel ? ` · 사례 구분: ${result.caseLabel}` : null}
          </p>
          <div className={styles.trust}>
            {result.versions.sampleData ? (
              <Disclosure label="예시 자료" context={context} tone="warning">
                <p>{copy.sampleDataNote}</p>
              </Disclosure>
            ) : null}
            <ComparisonMonthsNotice comparisonMonths={result.comparisonMonths} context={context} />
            {result.dataAsOf.note ? (
              <div className={styles.dataAsOf} aria-label={`${context}: 자료 기준 시점`}><p>{result.dataAsOf.note}</p></div>
            ) : null}
          </div>
          {result.status !== 'NOT_COMPUTABLE' && result.cases.length === 0 && result.thresholdNote ? (
            // 교정된 기준을 넘는 사례가 없다(010 US3 시나리오 4). 참고 사례는 검색 화면에서 따로 연다.
            <Disclosure label="유사성 기준" context={context} tone="warning">
              <p>{result.thresholdNote}</p>
              <p className={styles.caseActions}>
                <button type="button" className={styles.detailButton} onClick={onShowFields}>분야별로 살펴보기</button>
                <Link className={styles.detailButton} to={buildCompositeSearchPath()}>
                  참고 사례 보기 <ArrowUpRight size={14} aria-hidden="true" />
                </Link>
              </p>
            </Disclosure>
          ) : result.status === 'NOT_COMPUTABLE' || result.cases.length === 0 ? (
            <p className={styles.emptyNote}>{result.statusNote}</p>
          ) : (
            <ol className={styles.cases} aria-label="가까운 과거 사례">
              {result.cases.map((item) => {
                const caseId = buildCaseId(result.searchId, item.endMonth)
                return (
                  <li key={item.endMonth} className={styles.case}>
                    <div className={styles.caseBody}>
                      <p className={styles.caseRange}>
                        <span>관측 구간</span>
                        <strong>{observationRange(item)}</strong>
                      </p>
                      {item.similar.map((text) => (
                        <p key={text} className={styles.reason}>
                          <b className={styles.similarLabel}>비슷한 점</b>
                          <span className={styles.similarText}>{text}</span>
                        </p>
                      ))}
                      {item.different.map((text) => (
                        <p key={text} className={styles.reason}>
                          <b className={styles.differentLabel}>다른 점</b>
                          <span className={styles.differentText}>{text}</span>
                        </p>
                      ))}
                    </div>
                    <div className={styles.caseActions}>
                      {caseId === null ? null : (
                        <Link className={styles.pick} to={buildRegimeLinkStartPath({ caseId })}>이 기간으로 백테스트</Link>
                      )}
                      <Link className={styles.detailButton} to={buildCompositeSearchPath()}>자세히</Link>
                    </div>
                  </li>
                )
              })}
            </ol>
          )}
        </>
      ) : null}
    </section>
  )
}

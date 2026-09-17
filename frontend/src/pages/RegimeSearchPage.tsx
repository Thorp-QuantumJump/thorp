import { useEffect, useState, type FormEvent } from 'react'
import { getRegimeBaselineMonths } from '@/api/regime'
import { AppHeader } from '@/components/navigation/AppHeader'
import { BasisNotice } from '@/components/regime/BasisNotice'
import { CaseList } from '@/components/regime/CaseList'
import { ComparisonMonthsNotice } from '@/components/regime/ComparisonMonthsNotice'
import { Disclosure } from '@/components/regime/Disclosure'
import { EmptyResult } from '@/components/regime/EmptyResult'
import { LoadError } from '@/components/regime/LoadError'
import { SearchStatusBadge } from '@/components/regime/SearchStatusBadge'
import { useRegimeSearch } from '@/hooks/useRegimeSearch'
import type { RegimeBaselineMonths, RegimeSearchQuery } from '@/types/regime'
import styles from './RegimeSearchPage.module.css'

export function RegimeSearchPage() {
  const [baselines, setBaselines] = useState<RegimeBaselineMonths | null>(null)
  const [baselineError, setBaselineError] = useState<unknown>(null)
  const [baselineRetry, setBaselineRetry] = useState(0)
  const [fieldId, setFieldId] = useState('')
  const [query, setQuery] = useState<RegimeSearchQuery | null>(null)
  const { result, error, isLoading, showMore, retry } = useRegimeSearch(query)

  useEffect(() => {
    const controller = new AbortController()
    getRegimeBaselineMonths(controller.signal)
      .then((next) => {
        if (!controller.signal.aborted) {
          setBaselines(next)
          setBaselineError(null)
        }
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) setBaselineError(caught)
      })
    return () => controller.abort()
  }, [baselineRetry])

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!fieldId) return
    if (query?.field === fieldId) {
      retry()
    } else {
      setQuery({ mode: 'field', field: fieldId })
    }
  }

  const context = result
    ? `${result.field?.name ?? '종합'} ${result.baselineMonth} 검색 결과`
    : ''

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        <header className={styles.hero}>
          <p className={styles.eyebrow}>ECONOMIC REGIME SEARCH</p>
          <h1>비슷했던 때 보기</h1>
          <p>경제 분야를 고르면 과거의 가까운 관측 구간을 살펴볼 수 있습니다.</p>
        </header>

        {baselineError ? (
          <LoadError error={baselineError} onRetry={() => setBaselineRetry((value) => value + 1)} />
        ) : !baselines ? (
          <p role="status" className={styles.loading}>검색할 분야를 불러오는 중입니다.</p>
        ) : (
          <form onSubmit={submit} className={styles.searchForm}>
            <label htmlFor="regime-field">경제 분야</label>
            <select id="regime-field" value={fieldId} onChange={(event) => setFieldId(event.target.value)}>
              <option value="">분야 선택</option>
              {baselines.byField.map((field) => (
                <option key={field.id} value={field.id}>{field.name}</option>
              ))}
            </select>
            <button type="submit" disabled={!fieldId || isLoading}>과거 사례 찾기</button>
          </form>
        )}

        {query && (
          <section className={styles.results} aria-label="경제환경 검색 결과" aria-busy={isLoading}>
            {isLoading && !result && <p role="status" className={styles.loading}>과거 사례를 불러오는 중입니다.</p>}
            {error ? <LoadError error={error} onRetry={retry} /> : result && (
              <>
                <div className={styles.resultHeading}>
                  <div>
                    <p className={styles.eyebrow}>SEARCH RESULT</p>
                    <h2>{result.field?.name}</h2>
                  </div>
                  <p>기준월 <strong>{result.baselineMonth}</strong></p>
                </div>
                <div className={styles.disclosures}>
                  <SearchStatusBadge result={result} context={context} />
                  {result.versions.sampleData && (
                    <Disclosure label="예시 자료" context={context} tone="warning">
                      <p>예시 자료</p>
                    </Disclosure>
                  )}
                  <ComparisonMonthsNotice comparisonMonths={result.comparisonMonths} context={context} />
                  <BasisNotice basis={result.basis} context={context} />
                </div>
                <section className={styles.dataAsOf} aria-label={`${context}: 자료 기준 시점`}>
                  {result.dataAsOf.note && <p>{result.dataAsOf.note}</p>}
                  <dl>
                    {result.dataAsOf.indicators.map((indicator) => (
                      <div key={indicator.indicatorId}>
                        <dt>{indicator.indicator}</dt>
                        <dd>대상월 {indicator.targetMonth ?? '—'} · 발표일 {indicator.publishedAt ?? '—'}</dd>
                      </div>
                    ))}
                  </dl>
                  {result.dataAsOf.revisions.map((revision) => (
                    <p key={`${revision.indicatorId}-${revision.month}`}>
                      개정 · {revision.indicator} {revision.month} · {revision.revisedAt}
                    </p>
                  ))}
                </section>
                {result.status === 'NOT_COMPUTABLE' || result.cases.length === 0 ? (
                  <EmptyResult result={result} />
                ) : (
                  <CaseList result={result} onShowMore={showMore} isLoading={isLoading} context={context} />
                )}
              </>
            )}
          </section>
        )}
      </main>
    </div>
  )
}

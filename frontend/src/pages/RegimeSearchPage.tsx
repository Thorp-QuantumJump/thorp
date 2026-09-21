import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { getRegimeBaselineMonths } from '@/api/regime'
import { AppHeader } from '@/components/navigation/AppHeader'
import { BasisNotice } from '@/components/regime/BasisNotice'
import { BaselineChangeNotice } from '@/components/regime/BaselineChangeNotice'
import { CaseList } from '@/components/regime/CaseList'
import { ComparisonMonthsNotice } from '@/components/regime/ComparisonMonthsNotice'
import { DataAsOf } from '@/components/regime/DataAsOf'
import { Disclosure } from '@/components/regime/Disclosure'
import { EmptyResult } from '@/components/regime/EmptyResult'
import * as copy from '@/components/regime/fixedCopy'
import { LoadError } from '@/components/regime/LoadError'
import { NewerReleases } from '@/components/regime/NewerReleases'
import { SearchStatusBadge } from '@/components/regime/SearchStatusBadge'
import { useRegimeSearch } from '@/hooks/useRegimeSearch'
import { buildCaseId, buildRegimeLinkStartPath } from '@/marketflow/handoff'
import type { RegimeBaselineMonths, RegimeSearchQuery } from '@/types/regime'
import styles from './RegimeSearchPage.module.css'

export function RegimeSearchPage() {
  const [baselines, setBaselines] = useState<RegimeBaselineMonths | null>(null)
  const [baselineError, setBaselineError] = useState<unknown>(null)
  const [baselineRetry, setBaselineRetry] = useState(0)
  const [fieldId, setFieldId] = useState('')
  const [selectedMonth, setSelectedMonth] = useState('')
  const [query, setQuery] = useState<RegimeSearchQuery | null>(null)
  const [baselineChangeNote, setBaselineChangeNote] = useState<string | null>(null)
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

  /**
   * 기준월이나 검색 방식이 바뀌어 후보가 달라지면 한 문장으로 알린다(spec FR-031).
   * 문장은 계약이 고정한 것만 쓰고 화면에서 조립하지 않는다(spec FR-022 ②).
   */
  const changeNote = (mode: 'FIELD' | 'COMPOSITE', month: string | null, userSpecified: boolean) => {
    if (!result || !month) return null
    if (result.baselineMonth !== month) {
      return userSpecified ? copy.baselineMonthChangedNote(month) : baselines?.note ?? null
    }
    return result.mode === mode ? null : copy.searchModeChangedNote
  }

  /** 화면이 고를 수 있는 달은 결과가 실제로 있는 달뿐이다(계약 §5). 뒤면 그 모드의 최신월로 돌아간다. */
  const openSearch = (next: RegimeSearchQuery, latest: string | null | undefined) => {
    const month = selectedMonth && latest && selectedMonth <= latest ? selectedMonth : undefined
    if (selectedMonth && !month) setSelectedMonth('')
    const mode = next.mode === 'composite' ? 'COMPOSITE' : 'FIELD'
    setBaselineChangeNote(changeNote(mode, month ?? latest ?? null, month != null))
    if (query?.mode === next.mode && query.field === next.field && query.baselineMonth === month) retry()
    else setQuery({ ...next, baselineMonth: month })
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!fieldId) return
    openSearch({ mode: 'field', field: fieldId }, baselines?.byField.find((field) => field.id === fieldId)?.baselineMonth)
  }

  const openComposite = () => {
    if (baselines?.composite) openSearch({ mode: 'composite' }, baselines.composite)
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
          <p>{copy.pageLead}</p>
        </header>

        {baselineError ? (
          <LoadError error={baselineError} onRetry={() => setBaselineRetry((value) => value + 1)} />
        ) : !baselines ? (
          <p role="status" className={styles.loading}>{copy.loadingFields}</p>
        ) : (
          <form onSubmit={submit} className={styles.searchForm}>
            <label htmlFor="regime-field">경제 분야</label>
            <select id="regime-field" value={fieldId} onChange={(event) => setFieldId(event.target.value)}>
              <option value="">분야 선택</option>
              {baselines.byField.map((field) => (
                <option key={field.id} value={field.id}>{field.name}</option>
              ))}
            </select>
            {baselines.range?.earliest && baselines.range.latest && (
              <div className={styles.monthControl}>
                <label htmlFor="regime-month">기준월 지정</label>
                <input id="regime-month" type="month" min={baselines.range.earliest} max={baselines.range.latest}
                  value={selectedMonth} onChange={(event) => setSelectedMonth(event.target.value)} />
              </div>
            )}
            <button type="submit" disabled={!fieldId || isLoading}>과거 사례 찾기</button>
            <button type="button" disabled={!baselines.composite || isLoading} onClick={openComposite} className={styles.compositeButton}>
              Thorp 종합 비교
            </button>
          </form>
        )}

        {query && (
          <section className={styles.results} aria-label="경제환경 검색 결과" aria-busy={isLoading}>
            {isLoading && !result && <p role="status" className={styles.loading}>{copy.loadingCases}</p>}
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
                  {baselineChangeNote && <BaselineChangeNotice note={baselineChangeNote} context={context} />}
                  <SearchStatusBadge result={result} context={context} />
                  {result.versions.sampleData && (
                    <Disclosure label="예시 자료" context={context} tone="warning">
                      <p>{copy.sampleDataNote}</p>
                    </Disclosure>
                  )}
                  <ComparisonMonthsNotice comparisonMonths={result.comparisonMonths} context={context} />
                  <BasisNotice basis={result.basis} context={context} />
                  <NewerReleases releases={result.dataAsOf.newerReleases} context={context} />
                </div>
                <DataAsOf data={result.dataAsOf} context={context} />
                {result.status === 'NOT_COMPUTABLE' || result.cases.length === 0 ? (
                  <EmptyResult result={result} />
                ) : (
                  <CaseList
                    key={result.searchId}
                    result={result}
                    onShowMore={showMore}
                    isLoading={isLoading}
                    context={context}
                    fieldNames={Object.fromEntries(baselines?.byField.map((field) => [field.id, field.name]) ?? [])}
                    /*
                      사후 구간 자리에 011 진입을 둔다(011 T087). 이 길은 섹터를 알 수 없어 `sector`를
                      싣지 않는다 — 009에서 010으로 오는 인계에 섹터가 없다. 사례 식별자를 만들 수 없으면
                      010이 내려 준 문구를 그대로 둔다(빈 링크를 만들지 않는다).
                    */
                    postPeriodSlot={(item) => {
                      const caseId = buildCaseId(result.searchId, item.endMonth)
                      if (caseId === null) return undefined
                      return (
                        <p>
                          <Link to={buildRegimeLinkStartPath({ caseId })}>이 기간으로 전략 비교</Link>
                        </p>
                      )
                    }}
                  />
                )}
              </>
            )}
          </section>
        )}
      </main>
    </div>
  )
}

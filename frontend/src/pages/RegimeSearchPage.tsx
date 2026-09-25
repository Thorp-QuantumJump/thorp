import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { recordRegimeSearch } from '@/api/myRecords'
import { getRegimeBaselineMonths } from '@/api/regime'
import { useAuth } from '@/auth/auth-context'
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
import type { MyRecordRegime } from '@/types/myRecords'
import type { RegimeBaselineMonths, RegimeSearchQuery } from '@/types/regime'
import styles from './RegimeSearchPage.module.css'

type EntryPath = MyRecordRegime['entryPath']

/** 어디서 들어왔는가. 결과 화면(005)이 `state.entryPath = 'STRATEGY_RESULT'`를 넘기면 그것이고, 아니면 시장 흐름 탐색이다. */
function entryPathOf(state: unknown): EntryPath {
  return typeof state === 'object' && state !== null && (state as { entryPath?: unknown }).entryPath === 'STRATEGY_RESULT'
    ? 'STRATEGY_RESULT'
    : 'MARKET_FLOW'
}

export function RegimeSearchPage() {
  const [baselines, setBaselines] = useState<RegimeBaselineMonths | null>(null)
  const [baselineError, setBaselineError] = useState<unknown>(null)
  const [baselineRetry, setBaselineRetry] = useState(0)
  const [fieldId, setFieldId] = useState('')
  const [selectedMonth, setSelectedMonth] = useState('')
  const [query, setQuery] = useState<RegimeSearchQuery | null>(null)
  const [baselineChangeNote, setBaselineChangeNote] = useState<string | null>(null)
  // 내 기록의 '다시 보기'(006 R17). 식별자가 있으면 폼 질의 대신 저장된 판을 고정 조회한다.
  const [params, setParams] = useSearchParams()
  const savedSearchId = params.get('searchId')
  // 시장 흐름에서 넘어온 분야와 기준월(009 FR-009). 상태가 아니라 주소로 오므로 새로고침에도
  // 남고, 주소를 건네면 같은 검색을 볼 수 있다.
  const entryField = params.get('field')
  const entryMonth = params.get('month')
  const { result, error, isLoading, showMore, retry } = useRegimeSearch(query, savedSearchId)
  const location = useLocation()
  // 로그인 사용자의 검색을 내 기록에 남긴다(010 FR-032, 006 T037).
  const { user } = useAuth()
  const recorded = useRef<Set<string>>(new Set())
  const [recordFailed, setRecordFailed] = useState(false)

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

  // 진입 경로와 기준월 근거는 이 화면만 안다 — 서버가 추정하지 않으므로 화면이 보낸다(006 research R15). 같은
  // 검색은 한 번만 보내고, 실패해도 검색 결과를 막지 않는다. 실패한 것은 다음 결과가 올 때 한 번 더 시도한다.
  //
  // 저장된 판을 여는 것(?searchId=)은 '열람'이지 새 기록이 아니다. 그 주소에는 진입 경로가 없어 기본값으로 보내면
  // 원래 STRATEGY_RESULT 로 남은 기록 옆에 MARKET_FLOW 기록이 하나 더 생긴다 — 서버 키가 (소유자·검색·경로)다.
  useEffect(() => {
    if (savedSearchId || !user || !result || recorded.current.has(result.searchId)) return
    const { searchId } = result
    recorded.current.add(searchId)
    recordRegimeSearch(searchId, entryPathOf(location.state), result.baselineOrigin).then(
      () => setRecordFailed(false),
      () => {
        recorded.current.delete(searchId)
        setRecordFailed(true)
      },
    )
  }, [savedSearchId, user, result, location.state])

  // 넘어온 분야·기준월로 검색을 한 번 연다.
  //
  // **기준월 목록이 온 뒤에 연다.** `openSearch` 가 그 분야의 최신월과 견줘 넘어온 달이 뒤면
  // 최신월로 물러나고 그 사실을 한 문장으로 알린다(FR-031). 목록 없이 열면 그 판정을 못 해
  // 결과가 없는 달로 검색이 나간다.
  //
  // **한 번만 연다.** 사용자가 화면에서 분야를 바꾼 뒤에도 주소는 그대로라, 다시 열면 사용자가
  // 고른 것을 주소가 덮는다.
  const entryOpened = useRef(false)
  useEffect(() => {
    if (entryOpened.current || savedSearchId || !baselines || !entryField) return
    const field = baselines.byField.find((candidate) => candidate.id === entryField)
    if (!field) return
    // 넘어온 달이 그 분야의 최신월보다 뒤면 결과가 없다. 최신월로 물러나고 그 사실을 알린다.
    const month = entryMonth !== null && entryMonth <= field.baselineMonth ? entryMonth : null
    entryOpened.current = true
    setFieldId(field.id)
    setSelectedMonth(month ?? '')
    setBaselineChangeNote(
      entryMonth !== null && month === null ? copy.baselineMonthChangedNote(field.baselineMonth) : null,
    )
    setQuery({ mode: 'field', field: field.id, baselineMonth: month ?? undefined })
  }, [baselines, entryField, entryMonth, savedSearchId])

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
    // 저장된 검색을 보던 중 새로 검색하면 고정 조회를 푼다 — 그때부터는 최신 판이다.
    if (savedSearchId) {
      setParams(
        (current) => {
          const cleared = new URLSearchParams(current)
          cleared.delete('searchId')
          return cleared
        },
        { replace: true },
      )
    }
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
        ) : baselines.byField.length === 0 ? (
          // 사전 계산이 아직 돌지 않았다. 빈 선택 상자만 보이면 사용자는 자기가 잘못 눌렀다고
          // 읽으므로, 고를 것이 없는 이유를 말한다(FR-030, 원칙 VI).
          <p role="status" className={styles.loading}>{copy.baselinesNotReady}</p>
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

        {(query || savedSearchId) && (
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
                  {savedSearchId && (
                    <Disclosure label="저장된 검색" context={context}>
                      <p>{copy.savedSearchNote}</p>
                    </Disclosure>
                  )}
                  {recordFailed && (
                    <Disclosure label="내 기록" context={context} tone="warning">
                      <p>{copy.recordFailedNote}</p>
                    </Disclosure>
                  )}
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
                {result.status === 'NOT_COMPUTABLE' ||
                (result.cases.length === 0 && !result.thresholdNote && (result.referenceCases ?? []).length === 0) ? (
                  <EmptyResult result={result} />
                ) : (
                  <CaseList
                    key={result.searchId}
                    result={result}
                    onShowMore={showMore}
                    isLoading={isLoading}
                    context={context}
                    fieldNames={Object.fromEntries(baselines?.byField.map((field) => [field.id, field.name]) ?? [])}
                    onBrowseFields={
                      result.mode === 'COMPOSITE' && baselines?.byField[0]
                        ? () => setQuery({ mode: 'field', field: baselines.byField[0].id })
                        : undefined
                    }
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

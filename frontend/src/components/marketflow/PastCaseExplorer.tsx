import { History } from 'lucide-react'
import { useReducedMotion } from 'motion/react'
import { useContext, useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { recordRegimeSearch } from '@/api/myRecords'
import { AuthContext } from '@/auth/auth-context'
import { InlineHelp } from '@/components/common/InlineHelp'
import { BaselineChangeNotice } from '@/components/regime/BaselineChangeNotice'
import { BasisNotice } from '@/components/regime/BasisNotice'
import { CaseDetailLoader } from '@/components/regime/CaseDetailLoader'
import { ComparisonMonthsNotice } from '@/components/regime/ComparisonMonthsNotice'
import { Disclosure } from '@/components/regime/Disclosure'
import * as copy from '@/components/regime/fixedCopy'
import { NewerReleases } from '@/components/regime/NewerReleases'
import { SearchStatusBadge } from '@/components/regime/SearchStatusBadge'
import { usePastSearch, type PastSearchState } from '@/hooks/usePastCases'
import { buildCaseId, buildRegimeLinkStartPath } from '@/marketflow/handoff'
import type { MarketFlowFieldId } from '@/types/marketFlow'
import type { RegimeBaselineMonths, RegimeCaseSummary, RegimeSearchQuery, RegimeSearchResult } from '@/types/regime'
import { observationRange } from './pastFormat'
import past from './PastCompare.module.css'
import styles from './PastCaseExplorer.module.css'

/**
 * 위 '지금과 비슷했던 과거' 줄이 이 구역에 보내는 요청. `endMonth`가 있으면 그 사례의 상세를 펼치고, 없으면 구역
 * 머리로 간다. 같은 사례를 다시 눌러도 반응하도록 `nonce`를 올린다. `scope`가 다른 구역은 무시한다.
 */
export type CaseFocusRequest = { scope: string; endMonth: string | null; nonce: number }

interface PastCaseExplorerProps {
  /** 화면 안 이동의 목적지 id. 분야 상세와 종합 비교가 같은 자리를 쓰므로 하나만 그려진다. */
  id: string
  mode: 'field' | 'composite'
  fieldId?: MarketFlowFieldId
  /** 제목과 보조기기 문맥에 적는 범위 이름(분야 이름 또는 종합). 응답이나 분야 목록에서 온다. */
  scopeName: string
  /** 최신 기준월 검색. 분야 상세의 위 줄과 같은 결과라 페이지가 한 번만 받아 건넨다. */
  base: PastSearchState
  onRetryBase: () => void
  /** 010이 계산을 마친 달 목록. 없으면 기준월 지정 칸을 두지 않고 최신 결과만 보인다. */
  baselines: RegimeBaselineMonths | null
  fieldNames: Record<string, string>
  focusRequest?: CaseFocusRequest | null
}

/**
 * 분야 상세와 종합 비교의 바닥에 서는 '가까운 과거 사례' 구역(009 FR-019, 2026-10-07).
 *
 * 010 검색 화면이 보이던 것 — 가까운 사례와 비슷한 점·다른 점, 사례 상세(계산 근거), 기준월 지정, 신뢰 범위 표시 —
 * 을 그 분야 아래에서 바로 본다. 다른 화면으로 넘기지 않는다. **가까운 사례가 먼저** 서고 신뢰 범위 표시는 그 아래다.
 *
 * 기준월을 지정하지 않은 동안은 페이지가 받은 최신 검색(`base`)을 그대로 쓴다 — 위 줄과 같은 결과를 두 번 받지
 * 않는다. 기준월을 지정하거나 '더 보기'로 열 개를 받을 때만 따로 조회한다. 지정한 달이 그 범위의 최신 계산월보다
 * 뒤면 최신 계산월로 물러나고 그 사실을 알린다(010 FR-031).
 *
 * 종합 비교에서 기준 충족 사례가 없으면(010 FR-021·FR-046) 안내와 함께 **기준 미충족 참고 사례를 바로 펼쳐** 보인다
 * — 충족 사례가 있을 때는 섞이지 않게 눌러야 열린다. 분야별 탐색 버튼은 두지 않는다. 분야 메뉴가 바로 위에 있다.
 *
 * 문장은 응답이나 010의 fixedCopy에서만 온다(010 FR-022 ②). 로그인 사용자가 기준월을 지정해 검색한 것은 내 기록에
 * 남긴다(010 FR-032). 자동으로 열리는 최신 검색은 사용자가 고른 검색이 아니라 남기지 않는다.
 */
export function PastCaseExplorer({ id, mode, fieldId, scopeName, base, onRetryBase, baselines, fieldNames, focusRequest = null }: PastCaseExplorerProps) {
  const reducedMotion = useReducedMotion()
  const titleId = useId()
  const monthInputId = useId()
  const sectionRef = useRef<HTMLElement>(null)
  const caseRefs = useRef(new Map<string, HTMLLIElement>())
  const detailButtonRefs = useRef(new Map<string, HTMLButtonElement>())
  const scope = fieldId ?? 'composite'

  const [draft, setDraft] = useState('')
  /** 지정한 기준월. null이면 최신 계산월이다. */
  const [month, setMonth] = useState<string | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [openCase, setOpenCase] = useState<string | null>(null)
  const [openReasons, setOpenReasons] = useState<ReadonlySet<string>>(() => new Set())
  const [showReferences, setShowReferences] = useState(false)
  const [changeNote, setChangeNote] = useState<string | null>(null)
  const [recordFailed, setRecordFailed] = useState(false)
  const [pendingFocus, setPendingFocus] = useState<CaseFocusRequest | null>(null)

  const baseResult = base.status === 'ready' ? base.result : null
  const latestMonth = (mode === 'composite' ? baselines?.composite : baselines?.byField.find((field) => field.id === fieldId)?.baselineMonth)
    ?? baseResult?.baselineMonth ?? null

  // 지정한 달이나 열 개 보기만 따로 받는다. 둘 다 아니면 페이지가 받은 최신 검색을 그대로 쓴다.
  const ownQuery: RegimeSearchQuery | null = month !== null || expanded
    ? { mode, ...(fieldId ? { field: fieldId } : {}), ...(month !== null ? { baselineMonth: month } : {}), limit: expanded ? 10 : 3 }
    : null
  const { state: ownState, retry: retryOwn } = usePastSearch(ownQuery)
  // 같은 달의 열 개를 받는 동안은 받아 둔 세 개를 그대로 두고 '더 보기' 버튼만 기다린다 — 목록이 사라졌다 돌아오지 않게.
  const loadingMore = ownQuery !== null && month === null && ownState.status === 'loading'
  const search: PastSearchState = ownQuery === null ? base : loadingMore && baseResult ? base : ownState
  const retry = ownQuery === null ? onRetryBase : retryOwn
  const result = search.status === 'ready' ? search.result : null
  const context = result ? `${scopeName} ${result.baselineMonth} 검색 결과` : ''

  // 로그인 사용자가 기준월을 지정해 연 검색만 내 기록에 남긴다(010 FR-032). 같은 검색은 한 번만 보낸다.
  const auth = useContext(AuthContext)
  const user = auth?.user ?? null
  const recorded = useRef(new Set<string>())
  useEffect(() => {
    if (!user || month === null || !result || recorded.current.has(result.searchId)) return
    const { searchId, baselineOrigin } = result
    recorded.current.add(searchId)
    recordRegimeSearch(searchId, 'MARKET_FLOW', baselineOrigin).then(
      () => setRecordFailed(false),
      () => {
        recorded.current.delete(searchId)
        setRecordFailed(true)
      },
    )
  }, [user, month, result])

  // 위 줄의 '자세히'·'모두 보기'. 위 줄은 최신 검색의 사례를 말하므로 지정한 달은 푼다. 그리고 그 사례가 그려진 뒤 옮긴다.
  useEffect(() => {
    if (!focusRequest || focusRequest.scope !== scope) return
    setMonth(null)
    setDraft('')
    setChangeNote(null)
    if (focusRequest.endMonth !== null) setOpenCase(focusRequest.endMonth)
    setPendingFocus(focusRequest)
  }, [focusRequest, scope])
  useEffect(() => {
    if (!pendingFocus || !result) return
    const behavior: ScrollBehavior = reducedMotion ? 'auto' : 'smooth'
    const target = pendingFocus.endMonth !== null ? caseRefs.current.get(pendingFocus.endMonth) ?? null : sectionRef.current
    if (!target) return
    target.scrollIntoView?.({ behavior, block: 'start' })
    const focusTarget = pendingFocus.endMonth !== null ? detailButtonRefs.current.get(pendingFocus.endMonth) ?? target : target
    focusTarget.focus({ preventScroll: true })
    setPendingFocus(null)
  }, [pendingFocus, result, reducedMotion])

  function applyMonth(next: string | null) {
    const current = result?.baselineMonth ?? null
    const applied = next ?? latestMonth
    setChangeNote(applied !== null && applied !== current ? copy.baselineMonthChangedNote(applied) : null)
    setMonth(next !== null && next !== latestMonth ? next : null)
    setDraft(next ?? '')
    setExpanded(false)
    setOpenCase(null)
    setShowReferences(false)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!draft) return
    // 화면이 고를 수 있는 달은 결과가 실제로 있는 달뿐이다(010 FR-031). 뒤면 최신 계산월로 물러난다.
    applyMonth(latestMonth !== null && draft > latestMonth ? latestMonth : draft)
  }

  const toggleReasons = (endMonth: string) =>
    setOpenReasons((current) => {
      const next = new Set(current)
      if (next.has(endMonth)) next.delete(endMonth)
      else next.add(endMonth)
      return next
    })

  const renderCase = (item: RegimeCaseSummary, index: number, searchResult: RegimeSearchResult) => {
    const caseId = buildCaseId(searchResult.searchId, item.endMonth)
    const detailId = `${id}-case-${item.endMonth}`
    const reasonsOpen = openReasons.has(item.endMonth)
    const hidden = Math.max(0, item.similar.length - 1) + Math.max(0, item.different.length - 1)
    const similar = reasonsOpen ? item.similar : item.similar.slice(0, 1)
    const different = reasonsOpen ? item.different : item.different.slice(0, 1)
    const contextLinks = item.context ?? []
    const open = openCase === item.endMonth
    return (
      <li
        key={item.endMonth}
        className={styles.case}
        ref={(element) => {
          if (element) caseRefs.current.set(item.endMonth, element)
          else caseRefs.current.delete(item.endMonth)
        }}
      >
        <span className={styles.rank} aria-hidden="true">{index + 1}</span>
        <div className={styles.caseBody}>
          <p className={styles.caseRange}>
            <span>관측 구간</span>
            <strong>{observationRange(item)}</strong>
          </p>
          {/* 문장이 많은 사례(종합 비교)는 한 문장씩만 먼저 보인다. 문장은 서버가 만든 그대로다. */}
          {similar.map((text) => (
            <p key={text} className={past.reason}>
              <b className={past.similarLabel}>비슷한 점</b>
              <span className={past.similarText}>{text}</span>
            </p>
          ))}
          {different.map((text) => (
            <p key={text} className={past.reason}>
              <b className={past.differentLabel}>다른 점</b>
              <span className={past.differentText}>{text}</span>
            </p>
          ))}
          {hidden > 0 || contextLinks.length > 0 ? (
            <button type="button" className={styles.moreReasons} aria-expanded={reasonsOpen} onClick={() => toggleReasons(item.endMonth)}>
              {reasonsOpen ? '설명 접기' : '설명 더 보기'}
            </button>
          ) : null}
          {reasonsOpen && contextLinks.length > 0 ? (
            <section className={styles.context} aria-label={`${item.endMonth} 당시 맥락`}>
              <h5>당시 맥락</h5>
              <p>{copy.contextNote}</p>
              <ul>
                {contextLinks.map((link) => (
                  <li key={`${link.kind}-${link.date}-${link.title}`}>
                    <time dateTime={link.date}>{link.date}</time>{' '}
                    {link.url ? <a href={link.url} target="_blank" rel="noopener noreferrer">{link.title}</a> : <span>{link.title}</span>}
                    <span className={styles.contextSource}> · {link.source}</span>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </div>
        <div className={styles.caseActions}>
          {caseId === null ? null : (
            <Link className={past.pick} to={buildRegimeLinkStartPath({ caseId })}>이 기간으로 백테스트</Link>
          )}
          {item.detailAvailable ? (
            <button
              type="button"
              className={past.detailButton}
              aria-expanded={open}
              aria-controls={detailId}
              ref={(element) => {
                if (element) detailButtonRefs.current.set(item.endMonth, element)
                else detailButtonRefs.current.delete(item.endMonth)
              }}
              onClick={() => setOpenCase((current) => (current === item.endMonth ? null : item.endMonth))}
            >
              {open ? '상세 닫기' : '상세 보기'}
            </button>
          ) : null}
        </div>
        {open ? (
          <div id={detailId} className={styles.detailSlot}>
            <CaseDetailLoader
              searchId={searchResult.searchId}
              summary={item}
              mode={searchResult.mode}
              fieldNames={fieldNames}
              /* 사후 구간 자리에 011 진입을 둔다(011 진입 계약 §4). 섹터는 이 길에서 알 수 없어 싣지 않는다. */
              postPeriodSlot={caseId === null ? undefined : (
                <p className={styles.postPeriod}>
                  <Link className={past.pick} to={buildRegimeLinkStartPath({ caseId })}>이 기간으로 백테스트</Link>
                </p>
              )}
            />
          </div>
        ) : null}
      </li>
    )
  }

  const references = result?.referenceCases ?? []
  const referenceLabel = result?.referenceLabel ?? copy.referenceLabel
  const showMonthControl = baselines?.range?.earliest != null && latestMonth !== null

  return (
    <section id={id} ref={sectionRef} className={styles.explorer} aria-labelledby={titleId} tabIndex={-1}>
      <header className={styles.head}>
        <div className={styles.titleGroup}>
          <p className={styles.kicker} aria-hidden="true">HISTORICAL CASES</p>
          <h3 id={titleId} className={styles.title}>
            <History size={17} aria-hidden="true" />
            가까운 과거 사례
          </h3>
          {result ? (
            <p className={styles.meta}>
              기준월 <strong>{result.baselineMonth}</strong> · 가까운 순
            </p>
          ) : null}
        </div>
        {showMonthControl ? (
          <form className={styles.monthForm} onSubmit={submit} noValidate aria-label={`${scopeName} 기준월 지정`}>
            <div className={styles.monthField}>
              <div className={styles.monthLabel}>
                <label htmlFor={monthInputId}>기준월 지정</label>
                <InlineHelp label="기준월 지정" side="bottom"><p>{copy.monthPickNote}</p></InlineHelp>
              </div>
              <input
                id={monthInputId}
                type="month"
                min={baselines?.range?.earliest ?? undefined}
                max={latestMonth ?? undefined}
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
              />
            </div>
            <button type="submit" className={styles.findButton} disabled={!draft || draft === (month ?? latestMonth)}>
              이 달로 찾기
            </button>
            {month !== null ? (
              <button type="button" className={styles.resetButton} onClick={() => applyMonth(null)}>최신 기준월로</button>
            ) : null}
          </form>
        ) : null}
      </header>

      {changeNote ? <BaselineChangeNotice note={changeNote} context={context} /> : null}

      {search.status === 'loading' || search.status === 'idle' ? (
        <p className={past.loading} role="status">{copy.loadingCases}</p>
      ) : search.status === 'failed' ? (
        <div className={past.failure} role="status">
          <p>{search.message ?? (mode === 'composite' ? copy.compositeLoadFailed : copy.pastCasesLoadFailed)}</p>
          <button type="button" className={past.inlineAction} onClick={retry}>다시 시도</button>
        </div>
      ) : result ? (
        <>
          {result.status === 'NOT_COMPUTABLE' ? null : (
            <>
              {result.thresholdNote ? (
                <Disclosure label="유사성 기준" context={context} tone="warning">
                  <p>{result.thresholdNote}</p>
                </Disclosure>
              ) : null}
              {result.cases.length > 0 ? (
                <ol className={styles.cases} aria-label={`${context}: 가까운 과거 사례`}>
                  {result.cases.map((item, index) => renderCase(item, index, result))}
                </ol>
              ) : null}
              {references.length > 0 ? (
                result.cases.length === 0 ? (
                  // 충족 사례가 없으면 참고 사례만 남으므로 바로 펼친다. 섞일 목록이 없다(2026-10-07).
                  <section className={styles.references} aria-label={`${context}: ${referenceLabel}`}>
                    <h4>{referenceLabel}</h4>
                    <p className={styles.referenceNote}>{copy.referenceNote}</p>
                    <ol className={styles.cases}>{references.map((item, index) => renderCase(item, index, result))}</ol>
                  </section>
                ) : (
                  <section className={styles.references} aria-label={`${context}: ${referenceLabel}`}>
                    <button
                      type="button"
                      className={styles.toggleReferences}
                      aria-expanded={showReferences}
                      aria-controls={`${id}-references`}
                      onClick={() => setShowReferences((current) => !current)}
                    >
                      {showReferences ? `${referenceLabel} 접기` : `${referenceLabel} 보기`}
                    </button>
                    {showReferences ? (
                      <>
                        <p className={styles.referenceNote}>{copy.referenceNote}</p>
                        <ol id={`${id}-references`} className={styles.cases}>{references.map((item, index) => renderCase(item, index, result))}</ol>
                      </>
                    ) : null}
                  </section>
                )
              ) : null}
              {result.counts.stored > result.counts.displayed ? (
                <button type="button" className={styles.more} onClick={() => setExpanded(true)} disabled={loadingMore}>
                  {loadingMore ? '불러오는 중' : '더 보기'}
                </button>
              ) : null}
              {result.counts.eligible > result.counts.stored && result.displayLimitNote ? (
                <p className={styles.limitNote}>{result.displayLimitNote}</p>
              ) : null}
            </>
          )}

          {/* 신뢰 범위 표시는 사례 아래에 늘 펼쳐 둔다(010 FR-037). 검색 화면과 같은 컴포넌트와 문구다. 계산 불가나 빈 결과의
              사유도 여기 '검색 상태'가 말하므로 사례 자리에 같은 문장을 다시 적지 않는다. */}
          <div className={styles.trust}>
            <SearchStatusBadge result={result} context={context} />
            {result.caseLabel ? (
              <Disclosure label="사례 구분" context={context} tone="warning"><p>{result.caseLabel}</p></Disclosure>
            ) : null}
            {result.phaseNote ? (
              <Disclosure label="지금 시점" context={context} tone="warning"><p>{result.phaseNote}</p></Disclosure>
            ) : null}
            {result.versions.sampleData ? (
              <Disclosure label="예시 자료" context={context} tone="warning"><p>{copy.sampleDataNote}</p></Disclosure>
            ) : null}
            <ComparisonMonthsNotice comparisonMonths={result.comparisonMonths} context={context} />
            <BasisNotice basis={result.basis} context={context} />
            <NewerReleases releases={result.dataAsOf.newerReleases} context={context} />
            {recordFailed ? (
              <Disclosure label="내 기록" context={context} tone="warning"><p>{copy.recordFailedNote}</p></Disclosure>
            ) : null}
          </div>
          {/* 결과가 반영한 대상월과 발표일(010 FR-031). 지표가 많아도 접지 않고 짧은 칸으로 늘어놓는다. */}
          <section className={styles.asOf} aria-label={`${context}: 자료 기준 시점`}>
            <h4>자료 기준 시점</h4>
            {result.dataAsOf.note ? <p>{result.dataAsOf.note}</p> : null}
            {result.dataAsOf.indicators.length > 0 ? (
              <dl>
                {result.dataAsOf.indicators.map((item) => (
                  <div key={item.indicatorId}>
                    <dt>{item.indicator}</dt>
                    <dd>대상월 {item.targetMonth ?? '—'} · 발표일 {item.publishedAt ?? '—'}</dd>
                  </div>
                ))}
              </dl>
            ) : null}
            {result.dataAsOf.revisions.map((item) => (
              <p key={`${item.indicatorId}-${item.month}`}>개정 · {item.indicator} {item.month} · {item.revisedAt}</p>
            ))}
          </section>
        </>
      ) : null}
    </section>
  )
}

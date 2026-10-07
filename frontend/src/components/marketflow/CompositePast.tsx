import { useContext, useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react'
import { recordRegimeSearch } from '@/api/myRecords'
import { AuthContext } from '@/auth/auth-context'
import { InlineHelp } from '@/components/common/InlineHelp'
import { BaselineChangeNotice } from '@/components/regime/BaselineChangeNotice'
import { Disclosure } from '@/components/regime/Disclosure'
import * as copy from '@/components/regime/fixedCopy'
import { useFieldDetails, type FieldDetailLoad } from '@/hooks/useFieldDetails'
import { useCaseDetail, usePastSearch } from '@/hooks/usePastCases'
import type { FieldSummary } from '@/types/marketFlow'
import type { RegimeBaselineMonths, RegimeCaseSummary, RegimeSearchQuery } from '@/types/regime'
import { IndicatorCard } from './IndicatorCard'
import { CompareLegend, PastCaseChooser } from './PastCaseChooser'
import { PastFootnotes } from './PastFootnotes'
import { bandLabel, bandOf, pastForIndicator, unplacedVerdicts, type IndicatorPast } from './pastOverlay'
import styles from './PastCompare.module.css'

/**
 * 분야 메뉴의 'Thorp 종합 비교'를 고르면 상세 상자에 서는 화면(009 FR-019, 010 US3). 분야 상세와 같은 모양이다
 * (2026-10-07) — 위에 가까운 사례 선택 줄, 아래에 지표 그래프. 다만 일곱 분야를 함께 견주므로 그래프는 일곱 분야의
 * 지표 전부이고, 고른 사례의 당시 경로가 그 모든 그래프에 겹친다. 그래서 분야 하나와 무엇이 다른지 그림으로 보인다.
 *
 * 누를 때만 조회한다 — 이 컴포넌트는 종합 비교를 고른 동안에만 그려진다. 머리의 '기준월 지정'으로 다른 달을 기준으로
 * 다시 찾을 수 있고, 지정한 달이 최신 계산월보다 뒤면 최신 계산월로 물러나 그 사실을 알린다(010 FR-031). 로그인
 * 사용자가 기준월을 지정해 연 검색만 내 기록에 남긴다(010 FR-032) — 자동으로 열리는 최신 검색은 고른 검색이 아니다.
 *
 * 기준 충족 사례가 없으면(010 FR-021·FR-046) 안내와 함께 '기준 미충족 참고 사례'를 바로 선택 줄에 둔다 — 섞일 목록이
 * 없다. 충족 사례가 있을 때는 참고 사례를 함께 고를지 따로 켠다. 실패하면 서버가 실은 문장을 그대로 보인다 — 종합
 * 기준월이 아직 계산되지 않은 환경에서는 다시 시도해도 같은 결과라 그 사유를 알려야 한다(010 FR-037).
 */
export function CompositePast({ fields, baselines, fieldNames }: {
  /** 분야 메뉴 순서의 일곱 분야. 그래프 묶음의 순서와 이름이다. */
  fields: readonly FieldSummary[]
  /** 010이 계산을 마친 달 목록. 없으면 기준월 지정 칸을 두지 않고 최신 결과만 보인다. */
  baselines: RegimeBaselineMonths | null
  fieldNames: Record<string, string>
}) {
  const monthInputId = useId()
  const [draft, setDraft] = useState('')
  /** 지정한 기준월. null이면 최신 계산월이다. */
  const [month, setMonth] = useState<string | null>(null)
  const [changeNote, setChangeNote] = useState<string | null>(null)
  const [recordFailed, setRecordFailed] = useState(false)
  // 검색(searchId)마다 고른 사례. 고른 적이 없으면 가장 가까운 사례다.
  const [caseChoices, setCaseChoices] = useState<Readonly<Record<string, string>>>({})
  const [includeReferences, setIncludeReferences] = useState(false)
  const [showBand, setShowBand] = useState(false)

  const query = useMemo<RegimeSearchQuery>(
    () => ({ mode: 'composite', ...(month !== null ? { baselineMonth: month } : {}), limit: 10 }),
    [month],
  )
  const { state: search, retry } = usePastSearch(query)
  const result = search.status === 'ready' ? search.result : null
  const context = result ? `종합 ${result.baselineMonth} 검색 결과` : ''
  const latestMonth = baselines?.composite ?? (month === null ? result?.baselineMonth ?? null : null)

  const computable = result !== null && result.status !== 'NOT_COMPUTABLE'
  const meeting = useMemo(() => (result && result.status !== 'NOT_COMPUTABLE' ? result.cases : []), [result])
  const references = useMemo(() => (result && result.status !== 'NOT_COMPUTABLE' ? result.referenceCases ?? [] : []), [result])
  const usingReferences = meeting.length === 0 && references.length > 0
  const cases: readonly RegimeCaseSummary[] = usingReferences ? references : includeReferences ? [...meeting, ...references] : meeting
  const referenceMonths = useMemo(() => new Set(references.map((item) => item.endMonth)), [references])
  const chosenEndMonth = result ? caseChoices[result.searchId] ?? cases[0]?.endMonth ?? null : null
  const chosenCase = cases.find((item) => item.endMonth === chosenEndMonth) ?? null
  const pastUnavailable = chosenCase !== null && !chosenCase.detailAvailable
  const { state: caseDetail, retry: retryCaseDetail, scaleSource } = useCaseDetail(
    result?.searchId ?? null,
    chosenCase && !pastUnavailable ? chosenCase.endMonth : null,
  )
  const loadedCaseDetail = caseDetail?.status === 'ready' ? caseDetail.detail : null

  // 일곱 분야의 지표를 모두 받는다. 검색과 따로 받으므로 검색이 실패해도 지금의 그래프는 보인다.
  const fieldIds = useMemo(() => fields.map((field) => field.fieldId), [fields])
  const { loads, retry: retryFields } = useFieldDetails(fieldIds)
  const allIndicators = useMemo(
    () => fields.flatMap((field) => {
      const load = loads[field.fieldId]
      return load?.status === 'ready' ? load.data.indicators : []
    }),
    [fields, loads],
  )
  // 종합의 문장은 분야 묶음을 말하므로 지표 칸에 붙지 않고 선택 줄 아래에 모두 선다.
  const unplaced = useMemo(() => (chosenCase ? unplacedVerdicts(allIndicators, chosenCase) : []), [allIndicators, chosenCase])
  const pasts = useMemo<Readonly<Record<string, IndicatorPast>> | null>(() => {
    if (allIndicators.length === 0) return null
    if (!chosenCase) {
      return scaleSource
        ? Object.fromEntries(allIndicators.map((indicator) => [indicator.indicatorId, { path: null, verdict: null, band: bandOf(indicator, scaleSource) }]))
        : null
    }
    return Object.fromEntries(allIndicators.map((indicator) => [indicator.indicatorId, pastForIndicator(indicator, chosenCase, loadedCaseDetail, scaleSource)]))
  }, [allIndicators, chosenCase, loadedCaseDetail, scaleSource])
  const legendPastMonths = pasts ? Object.values(pasts).find((past) => past.path)?.path?.months ?? null : null
  const nowMonths = allIndicators[0]?.path.map((point) => point.month) ?? []

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

  function applyMonth(next: string | null) {
    const current = result?.baselineMonth ?? null
    const applied = next ?? latestMonth
    setChangeNote(applied !== null && applied !== current ? copy.baselineMonthChangedNote(applied) : null)
    setMonth(next !== null && next !== latestMonth ? next : null)
    setDraft(next ?? '')
    setIncludeReferences(false)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!draft) return
    // 화면이 고를 수 있는 달은 결과가 실제로 있는 달뿐이다(010 FR-031). 뒤면 최신 계산월로 물러난다.
    applyMonth(latestMonth !== null && draft > latestMonth ? latestMonth : draft)
  }

  const showMonthControl = baselines?.range?.earliest != null && latestMonth !== null
  const referenceLabel = result?.referenceLabel ?? copy.referenceLabel

  return (
    <section className={styles.composite} aria-labelledby="composite-past-title">
      <header className={styles.compositeHead}>
        <div className={styles.titleGroup}>
          <h2 id="composite-past-title">Thorp 종합 비교</h2>
          {result ? (
            <InlineHelp label={`검색 상태${result.caseLabel ? '와 사례 구분' : ''}`} mark="i" side="bottom">
              <p><b>검색 상태</b> {result.statusNote}</p>
              {result.caseLabel ? <p><b>사례 구분</b> {result.caseLabel}</p> : null}
              <p>{copy.blockWeightNote}</p>
            </InlineHelp>
          ) : null}
        </div>
        <div className={styles.compositeTools}>
          <p className={styles.compareMeta}>
            {result ? <span>비교 기준월 {result.baselineMonth} · 가까운 순</span> : null}
          </p>
          {showMonthControl ? (
            // 범위 밖 달은 브라우저 검증이 막지 않고 화면이 최신 계산월로 물러난다.
            <form className={styles.monthForm} onSubmit={submit} noValidate aria-label="종합 비교 기준월 지정">
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
        </div>
      </header>

      {changeNote ? <BaselineChangeNotice note={changeNote} context={context} /> : null}

      {search.status === 'loading' || search.status === 'idle' ? (
        <p className={styles.loading} role="status">{copy.loadingCases}</p>
      ) : search.status === 'failed' ? (
        <div className={styles.failure} role="status">
          <p>{search.message ?? copy.compositeLoadFailed}</p>
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
          {!computable ? (
            <p className={styles.emptyNote}>{result.statusNote}</p>
          ) : (
            <>
              {usingReferences && result.thresholdNote ? (
                <Disclosure label="유사성 기준" context={context} tone="warning">
                  <p>{result.thresholdNote}</p>
                </Disclosure>
              ) : null}
              {usingReferences ? (
                <p className={styles.referenceLead}>
                  <b>{referenceLabel}</b>
                  <span>{copy.referenceNote}</span>
                </p>
              ) : null}
              {cases.length > 0 ? (
                <PastCaseChooser
                  result={result}
                  cases={cases}
                  referenceMonths={usingReferences || includeReferences ? referenceMonths : undefined}
                  selected={chosenEndMonth}
                  onSelect={(endMonth) => setCaseChoices((current) => ({ ...current, [result.searchId]: endMonth }))}
                  unplaced={unplaced}
                  fieldNames={fieldNames}
                />
              ) : (
                <p className={styles.emptyNote}>{result.statusNote}</p>
              )}
              {!usingReferences && references.length > 0 ? (
                // 충족 사례가 있을 때 참고 사례는 섞이지 않게 따로 켠다(010 FR-021). 켜면 선택 줄 뒤에 '참고' 표를 달고 선다.
                <label className={styles.includeReferences}>
                  <input type="checkbox" checked={includeReferences} onChange={(event) => setIncludeReferences(event.target.checked)} />
                  {referenceLabel}도 함께 고르기
                </label>
              ) : null}
            </>
          )}
          {recordFailed ? (
            <Disclosure label="내 기록" context={context} tone="warning"><p>{copy.recordFailedNote}</p></Disclosure>
          ) : null}
        </>
      ) : null}

      {cases.length > 0 && nowMonths.length > 0 ? (
        <CompareLegend
          nowMonths={nowMonths}
          selected={chosenEndMonth}
          caseDetail={caseDetail}
          onRetryCaseDetail={retryCaseDetail}
          pastUnavailable={pastUnavailable}
          pastMonths={legendPastMonths}
          band={bandLabel(scaleSource)}
          showBand={showBand}
          onToggleBand={setShowBand}
        />
      ) : null}

      {/* 일곱 분야의 지표 그래프. 검색과 상관없이 지금의 경로는 늘 보이고, 사례를 고르면 당시 경로가 겹친다. */}
      <div className={styles.fieldGroups}>
        {fields.map((field) => (
          <FieldGroup
            key={field.fieldId}
            field={field}
            load={loads[field.fieldId]}
            pasts={pasts}
            showBand={showBand}
            comparing={chosenCase !== null}
            onRetry={retryFields}
          />
        ))}
      </div>

      {result ? <PastFootnotes result={result} context={context} /> : null}
    </section>
  )
}

/** 분야 하나의 지표 칸 묶음. 분야 상세의 지표 칸과 같은 칸을 쓰되 머리는 분야 이름과 대상월 한 줄이다. */
function FieldGroup({ field, load, pasts, showBand, comparing, onRetry }: {
  field: FieldSummary
  load: FieldDetailLoad | undefined
  pasts: Readonly<Record<string, IndicatorPast>> | null
  showBand: boolean
  comparing: boolean
  onRetry: () => void
}) {
  const titleId = `composite-field-${field.fieldId}`
  return (
    <section className={styles.fieldGroup} aria-labelledby={titleId}>
      <h3 id={titleId} className={styles.fieldGroupTitle}>
        {field.displayName}
        {load?.status === 'ready' && load.data.referenceMonth ? <span>대상월 {load.data.referenceMonth}</span> : null}
      </h3>
      {load?.status === 'ready' ? (
        load.data.indicators.length === 0 ? (
          <p className={styles.emptyNote}>{copy.noFieldIndicators}</p>
        ) : (
          <div className={styles.fieldGroupIndicators}>
            {load.data.indicators.map((indicator) => (
              <IndicatorCard
                key={indicator.indicatorId}
                indicator={indicator}
                fieldReferenceMonth={load.data.referenceMonth}
                headingTag="h4"
                past={pasts?.[indicator.indicatorId] ?? null}
                showBand={showBand}
                comparing={comparing}
              />
            ))}
          </div>
        )
      ) : load?.status === 'error' ? (
        <div className={styles.failure} role="status">
          <p>{copy.fieldIndicatorsLoadFailed}</p>
          <button type="button" className={styles.inlineAction} onClick={onRetry}>다시 시도</button>
        </div>
      ) : (
        <p className={styles.loading} role="status">{copy.loadingFieldIndicators}</p>
      )}
    </section>
  )
}

import { RefreshCw } from 'lucide-react'
import { LayoutGroup, motion, useReducedMotion } from 'motion/react'
import { useCallback, useEffect, useId, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { getRegimeBaselineMonths } from '@/api/regime'
import { CompositePast } from '@/components/marketflow/CompositePast'
import { FieldDetail } from '@/components/marketflow/FieldDetail'
import { FieldPastCompare } from '@/components/marketflow/FieldPastCompare'
import { CompositeSummaryCard, FieldSummaryCard } from '@/components/marketflow/FieldSummaryCard'
import { bandLabel, bandOf, pastForIndicator, unplacedVerdicts } from '@/components/marketflow/pastOverlay'
import { useCaseDetail, usePastSearch } from '@/hooks/usePastCases'
import { AppHeader } from '@/components/navigation/AppHeader'
import type { FieldDetailResult, FieldSummary, FieldSummaryResult, MarketFlowFieldId } from '@/types/marketFlow'
import type { RegimeBaselineMonths } from '@/types/regime'
import heading from '@/components/common/PageHeading.module.css'
import styles from './MarketFlowPage.module.css'

type DetailState =
  | { fieldId: MarketFlowFieldId; status: 'loading' | 'error' }
  | { fieldId: MarketFlowFieldId; status: 'success'; data: FieldDetailResult }

/** 분야 메뉴에서 고를 수 있는 것. 일곱 분야와 끝의 'Thorp 종합 비교'다(009 FR-019). */
type Selection = MarketFlowFieldId | 'composite'

/**
 * 검색 결과(searchId)마다 고른 사례. 분야를 오갔다 돌아와도 그 분야에서 고른 것이 남고, 고른 적이 없는 결과는
 * 가장 가까운 사례에서 시작한다. '지금만'은 없다(2026-10-07).
 */
type CaseChoices = Readonly<Record<string, string>>

function LoadingNotice({ children }: { children: string }) {
  return <div className={styles.loading} role="status"><span className={styles.loadingTrack} aria-hidden="true" /><p>{children}</p></div>
}

/** 일곱 분야를 동시에 훑고, 같은 화면에서 선택한 분야의 원자료와 지금과 비슷했던 과거를 함께 조회한다. */
export function MarketFlowPage() {
  const reducedMotion = useReducedMotion()
  const selectionGroup = useId()
  const [summary, setSummary] = useState<FieldSummaryResult | null>(null)
  const [summaryError, setSummaryError] = useState(false)
  const [summaryRequest, setSummaryRequest] = useState(0)
  const [selectedId, setSelectedId] = useState<Selection | null>(null)
  const [detail, setDetail] = useState<DetailState | null>(null)
  const [detailRequest, setDetailRequest] = useState(0)
  const [caseChoices, setCaseChoices] = useState<CaseChoices>({})
  const [showBand, setShowBand] = useState(false)
  // 010이 계산을 마친 달 목록. 종합 비교의 '가까운 과거 사례' 구역이 기준월 지정 범위로 쓴다. 못 받으면 지정 칸 없이 최신만 보인다.
  const [baselines, setBaselines] = useState<RegimeBaselineMonths | null>(null)
  // 마지막으로 상세를 받은 분야와 그때의 다시 불러오기 횟수. 종합 비교를 보다 같은 분야로 돌아오면 다시 받지 않는다.
  const loadedDetail = useRef<{ fieldId: MarketFlowFieldId; request: number } | null>(null)
  const compositeOpen = selectedId === 'composite'
  const selected = compositeOpen ? undefined : summary?.fields.find((field) => field.fieldId === selectedId) ?? summary?.fields[0]
  const selectedFieldId = selected?.fieldId

  useEffect(() => {
    const controller = new AbortController()
    setSummaryError(false)
    fetchMarketFlowFields(controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) {
          setSummary({ ...result, fields: [...result.fields].sort((a, b) => a.displayOrder - b.displayOrder) })
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setSummaryError(true)
      })
    return () => controller.abort()
  }, [summaryRequest])

  useEffect(() => {
    const controller = new AbortController()
    getRegimeBaselineMonths(controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setBaselines(result)
      })
      .catch(() => {
        // 기준월 목록이 없어도 사례 구역은 최신 검색으로 선다. 지정 칸만 두지 않는다.
      })
    return () => controller.abort()
  }, [])

  const fieldNames = useMemo(
    () => Object.fromEntries(summary?.fields.map((field) => [field.fieldId, field.displayName]) ?? []),
    [summary],
  )

  const openField = useCallback((field: FieldSummary) => {
    // 같은 분야의 선택은 조회/차트 애니메이션을 다시 시작하지 않는다.
    if (field.fieldId !== selectedFieldId) setSelectedId(field.fieldId)
  }, [selectedFieldId])

  useEffect(() => {
    if (!selectedFieldId) return
    // 종합 비교는 분야 상세를 비우지 않는다. 그 분야로 돌아오면 남은 상세를 그대로 쓴다 — 다시 받으면 이미 그린
    // 그래프가 로딩 안내로 내려갔다가 다시 그려진다.
    if (loadedDetail.current?.fieldId === selectedFieldId && loadedDetail.current.request === detailRequest) return
    // 받기 시작하면 기록을 지운다. 다른 분야를 받다 실패한 뒤 돌아왔을 때 남은 기록으로 건너뛰면 안 된다.
    loadedDetail.current = null
    const controller = new AbortController()
    setDetail({ fieldId: selectedFieldId, status: 'loading' })
    fetchMarketFlowField(selectedFieldId, controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return
        loadedDetail.current = { fieldId: selectedFieldId, request: detailRequest }
        setDetail({ fieldId: selectedFieldId, status: 'success', data })
      })
      .catch(() => {
        if (!controller.signal.aborted) setDetail({ fieldId: selectedFieldId, status: 'error' })
      })
    return () => controller.abort()
  }, [selectedFieldId, detailRequest])

  // 010 분야 검색. 분야 상세와 따로 받으며, 실패해도 상세는 그대로 보인다(009 FR-019).
  // 표시 후보 열 개를 한 번에 받는다. 줄에서 셋이 보이고 나머지는 가로로 넘긴다(2026-10-07).
  const pastQuery = useMemo(() => (selectedFieldId ? { mode: 'field' as const, field: selectedFieldId, limit: 10 as const } : null), [selectedFieldId])
  // 분야를 바꾼 첫 렌더에는 이전 분야의 결과가 남아 있지만, 훅이 키를 대조해 '받는 중'으로 돌려준다.
  const { state: pastSearch, retry: retryPastSearch } = usePastSearch(pastQuery)
  const pastResult = pastSearch.status === 'ready' ? pastSearch.result : null
  // 처음에는 가장 가까운 사례를 겹친다. null은 사례가 없을 때뿐이다.
  const chosenEndMonth = pastResult
    ? caseChoices[pastResult.searchId] ?? pastResult.cases[0]?.endMonth ?? null
    : null
  const chosenCase = pastResult?.cases.find((item) => item.endMonth === chosenEndMonth) ?? null
  // 상세를 열 수 없는 사례(010 detailAvailable)는 당시 경로를 받지 않는다. 순위와 문장은 그대로 보인다.
  const pastUnavailable = chosenCase !== null && !chosenCase.detailAvailable
  const { state: caseDetail, retry: retryCaseDetail, scaleSource } = useCaseDetail(
    pastResult?.searchId ?? null,
    chosenCase && !pastUnavailable ? chosenCase.endMonth : null,
  )
  const loadedCaseDetail = caseDetail?.status === 'ready' ? caseDetail.detail : null

  function navigateFields(event: KeyboardEvent<HTMLElement>) {
    const target = event.target as HTMLElement
    if (!target.closest('button[aria-controls="market-flow-detail"]')) return
    const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button[aria-controls="market-flow-detail"]'))
    const index = buttons.indexOf(target.closest('button') as HTMLButtonElement)
    // 분야 메뉴는 가로 한 줄이지만 좁은 화면에서는 여러 줄로 접히므로 두 방향 화살표를 모두 받는다.
    const nextIndex = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? (index + 1) % buttons.length
      : event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? (index - 1 + buttons.length) % buttons.length
      : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : null
    if (nextIndex === null) return
    event.preventDefault()
    buttons[nextIndex].focus()
    buttons[nextIndex].click()
  }

  const displayedDetail = detail?.fieldId === selectedFieldId ? detail : null
  const shownDetail = displayedDetail?.status === 'success' ? displayedDetail.data : null
  const pasts = useMemo(() => {
    if (!shownDetail) return null
    // 사례가 없어도 보통 범위는 켤 수 있다. 공통 척도는 사례와 상관없이 그 검색에 하나다.
    if (!chosenCase) {
      return scaleSource
        ? Object.fromEntries(shownDetail.indicators.map((indicator) => [indicator.indicatorId, { path: null, verdict: null, band: bandOf(indicator, scaleSource) }]))
        : null
    }
    return Object.fromEntries(shownDetail.indicators.map((indicator) => [indicator.indicatorId, pastForIndicator(indicator, chosenCase, loadedCaseDetail, scaleSource)]))
  }, [shownDetail, chosenCase, loadedCaseDetail, scaleSource])
  // 범례의 당시 기간은 당시 경로가 있는 첫 지표에서 읽는다. 첫 지표를 010이 비교하지 않았을 수도 있다.
  const legendPastMonths = pasts ? Object.values(pasts).find((past) => past.path)?.path?.months ?? null : null
  const introMotion = reducedMotion ? false : { opacity: 0, y: 7 }

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        <header className={styles.hero}>
          <motion.div initial={introMotion} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }}>
            <p className={heading.eyebrow} aria-hidden="true">MARKET FLOW</p>
            {/* 넓은 화면에서는 한 줄로 읽힌다. 뒷부분은 통째로 움직이는 덩어리라 폭이 모자라면 '분야별로'부터
                아랫줄로 내려가고, 단어 중간에서 끊기지 않는다. */}
            <h1>시장 흐름을 <span><em className={heading.emphasis}>분야별로</em> 확인하세요.</span></h1>
          </motion.div>
          <motion.div className={styles.heroAside} initial={introMotion} animate={{ opacity: 1, y: 0 }} transition={{ duration: reducedMotion ? 0 : 0.3, delay: reducedMotion ? 0 : 0.06 }}>
            <p>
              <span>일곱 분야의 실제 지표와 최근 3개월 경로를 보여 드리고,</span>
              <span>지금과 지표가 비슷했던 과거 구간을 같은 그래프에 겹쳐 드립니다.</span>
            </p>
          </motion.div>
        </header>

        {summaryError ? (
          <section className={styles.loadFailure} role="alert">
            <p>분야 자료를 불러오지 못했습니다.</p>
            <button type="button" onClick={() => setSummaryRequest((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> 다시 불러오기</button>
          </section>
        ) : !summary ? <LoadingNotice>분야 자료를 불러오는 중입니다.</LoadingNotice> : (
          <div className={styles.workspace}>
            <section className={styles.fieldRail} aria-labelledby="field-list-title" onKeyDown={navigateFields}>
              {/* 눈에 보이는 제목은 두지 않는다. 메뉴 묶음의 이름으로 보조기기에만 읽힌다. */}
              <h2 id="field-list-title" className={styles.srOnly}>분야별 경제 흐름</h2>
              {/*
                목록 머리의 '7개 분야 · 최근 3개월 · 마지막 갱신' 줄은 두지 않는다(2026-09-29). 그래서 각 분야의
                마지막 갱신 시각은 말풍선마다 적고(FR-011), 고른 분야의 시각은 상세 머리 오른쪽에 있다.
                메뉴는 분야 수만큼 같은 폭으로 나누고 끝에 종합 비교 칸 하나를 더해 아래 상세 카드와 폭을 맞춘다.
              */}
              <div className={styles.fieldList} style={{ '--field-count': summary.fields.length } as CSSProperties}>
                <LayoutGroup id={selectionGroup}>
                  {summary.fields.map((field) => <FieldSummaryCard key={field.fieldId} field={field} selected={selectedFieldId === field.fieldId} onOpen={openField} />)}
                  <CompositeSummaryCard selected={compositeOpen} onOpen={() => setSelectedId('composite')} />
                </LayoutGroup>
              </div>
            </section>
            <div id="market-flow-detail" className={styles.detailPanel} tabIndex={-1} aria-label="선택한 분야 상세">
              <p className={styles.srOnly} role="status">{shownDetail ? `${selected?.displayName} 분야 상세를 불러왔습니다.` : ''}</p>
              {compositeOpen ? (
                <CompositePast fields={summary.fields} baselines={baselines} fieldNames={fieldNames} />
              ) : shownDetail && displayedDetail ? (
                // 퇴장 애니메이션으로 이전 분야가 남지 않도록 새 선택을 즉시 반영한다.
                <FieldDetail
                  key={displayedDetail.fieldId}
                  detail={shownDetail}
                  historyNotice={summary.historicalComparisonNotice}
                  pasts={pasts}
                  showBand={showBand}
                  comparing={chosenCase !== null}
                  compare={(
                    <FieldPastCompare
                      fieldName={shownDetail.displayName}
                      referenceMonth={shownDetail.referenceMonth}
                      nowMonths={shownDetail.indicators[0]?.path.map((point) => point.month) ?? []}
                      search={pastSearch}
                      onRetrySearch={retryPastSearch}
                      selected={chosenEndMonth}
                      onSelect={(endMonth) => {
                        if (pastResult) setCaseChoices((current) => ({ ...current, [pastResult.searchId]: endMonth }))
                      }}
                      caseDetail={caseDetail}
                      onRetryCaseDetail={retryCaseDetail}
                      pastUnavailable={pastUnavailable}
                      pastMonths={legendPastMonths}
                      band={bandLabel(scaleSource)}
                      showBand={showBand}
                      onToggleBand={setShowBand}
                      unplaced={chosenCase ? unplacedVerdicts(shownDetail.indicators, chosenCase) : []}
                    />
                  )}
                />
              ) : displayedDetail?.status === 'error' ? (
                <section className={styles.loadFailure} role="alert">
                  <h2>{selected?.displayName}</h2>
                  <p>분야 상세를 불러오지 못했습니다. 다른 분야는 계속 확인할 수 있습니다.</p>
                  <button type="button" onClick={() => setDetailRequest((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> 상세 다시 불러오기</button>
                </section>
              ) : <LoadingNotice>{`${selected?.displayName ?? '분야'} 상세를 불러오는 중입니다.`}</LoadingNotice>}
            </div>
          </div>
        )}

      </main>
    </div>
  )
}

import { Activity, ArrowDown, ArrowUpRight, ChartNoAxesCombined, Newspaper, RefreshCw, Waypoints } from 'lucide-react'
import { LayoutGroup, motion, useReducedMotion } from 'motion/react'
import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { FieldDetail } from '@/components/marketflow/FieldDetail'
import { DomesticMarketReference } from '@/components/marketflow/DomesticMarketReference'
import { FieldSummaryCard } from '@/components/marketflow/FieldSummaryCard'
import { NewsDigestSection } from '@/components/marketflow/NewsDigestSection'
import { SectorSelector } from '@/components/marketflow/SectorSelector'
import { buildStrategyComparisonHandoff } from '@/marketflow/handoff'
import { AppHeader } from '@/components/navigation/AppHeader'
import type { FieldDetailResult, FieldSummary, FieldSummaryResult, MarketFlowFieldId } from '@/types/marketFlow'
import heading from '@/components/common/PageHeading.module.css'
import styles from './MarketFlowPage.module.css'

type DetailState =
  | { fieldId: MarketFlowFieldId; status: 'loading' | 'error' }
  | { fieldId: MarketFlowFieldId; status: 'success'; data: FieldDetailResult }

function LoadingNotice({ children }: { children: string }) {
  return <div className={styles.loading} role="status"><span className={styles.loadingTrack} aria-hidden="true" /><p>{children}</p></div>
}

/** 일곱 분야를 동시에 훑고, 같은 화면에서 선택한 분야의 원자료를 조회한다. */
export function MarketFlowPage() {
  const reducedMotion = useReducedMotion()
  const selectionGroup = useId()
  const detailPanel = useRef<HTMLDivElement>(null)
  const [summary, setSummary] = useState<FieldSummaryResult | null>(null)
  const [summaryError, setSummaryError] = useState(false)
  const [summaryRequest, setSummaryRequest] = useState(0)
  const [selectedId, setSelectedId] = useState<MarketFlowFieldId | null>(null)
  const [detail, setDetail] = useState<DetailState | null>(null)
  const [detailRequest, setDetailRequest] = useState(0)
  /**
   * 고른 섹터다. **분야 요약·상세 조회의 의존성에 넣지 않는다**(FR-016, SC-004).
   * 여기 들어가는 순간 섹터를 바꿀 때마다 경제환경 자료를 다시 부르게 되고,
   * 같은 값이 다시 그려진다는 보장이 사라진다.
   */
  const [sectorCode, setSectorCode] = useState<string | null>(null)
  const selected = summary?.fields.find((field) => field.fieldId === selectedId) ?? summary?.fields[0]
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

  const openField = useCallback((field: FieldSummary) => {
    // 같은 분야의 선택은 조회/차트 애니메이션을 다시 시작하지 않는다.
    if (field.fieldId !== selectedFieldId) setSelectedId(field.fieldId)
  }, [selectedFieldId])

  useEffect(() => {
    if (!selectedFieldId) return
    const controller = new AbortController()
    setDetail({ fieldId: selectedFieldId, status: 'loading' })
    fetchMarketFlowField(selectedFieldId, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setDetail({ fieldId: selectedFieldId, status: 'success', data })
      })
      .catch(() => {
        if (!controller.signal.aborted) setDetail({ fieldId: selectedFieldId, status: 'error' })
      })
    return () => controller.abort()
  }, [selectedFieldId, detailRequest])

  function navigateFields(event: KeyboardEvent<HTMLElement>) {
    const target = event.target as HTMLElement
    if (!target.closest('button[aria-controls="market-flow-detail"]')) return
    const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button[aria-controls="market-flow-detail"]'))
    const index = buttons.indexOf(target.closest('button') as HTMLButtonElement)
    const nextIndex = event.key === 'ArrowDown' ? (index + 1) % buttons.length
      : event.key === 'ArrowUp' ? (index - 1 + buttons.length) % buttons.length
      : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : null
    if (nextIndex === null) return
    event.preventDefault()
    buttons[nextIndex].focus()
    buttons[nextIndex].click()
  }

  /**
   * 011로 넘길 값이다. 사례는 010의 검색 결과에서 고르는 값이라 이 화면에서는 언제나 `null`이고,
   * 따라서 넘길 값이 없다. 섹터만으로 열면 '이 기간'이 빠진 채 넘어가므로(009 FR-018) 이 카드는
   * 011을 직접 열지 않고 사례를 고르는 010으로 안내한다. 011 진입 버튼은 010 사례 상세에 있다.
   */
  const strategyComparison = buildStrategyComparisonHandoff({ sectorCode, caseId: null })
  const displayedDetail = detail?.fieldId === selectedFieldId ? detail : null
  const introMotion = reducedMotion ? false : { opacity: 0, y: 7 }

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        <header className={styles.hero}>
          <motion.div initial={introMotion} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }}>
            {/* classic은 예전의 아이콘 작은 제목, 새 디자인은 첫 화면과 같은 선 작은 제목이다. */}
            <span className={`${styles.eyebrow} ${styles.classicOnly}`}><Activity size={14} aria-hidden="true" /> MARKET FLOW</span>
            <p className={heading.eyebrow} aria-hidden="true">MARKET FLOW</p>
            <h1>시장 흐름을<br /><span><em className={heading.emphasis}>분야별로</em> 확인하세요.</span></h1>
          </motion.div>
          <motion.div className={styles.heroAside} initial={introMotion} animate={{ opacity: 1, y: 0 }} transition={{ duration: reducedMotion ? 0 : 0.3, delay: reducedMotion ? 0 : 0.06 }}>
            <p>경제를 하나의 점수로 단정하지 않고, 생산·소비·대외거래 등 일곱 분야의 실제 지표와 최근 경로를 나누어 보여드립니다.</p>
            <span>분야를 살펴보고, 지표의 경로를 읽어보세요.</span>
          </motion.div>
        </header>

        <div className={styles.workspaceHeading}>
          <h2 id="field-list-title"><span>01</span> 분야별 경제 흐름</h2>
          <span>7개 분야 <i aria-hidden="true">/</i> 최근 3개월</span>
        </div>
        {summaryError ? (
          <section className={styles.loadFailure} role="alert">
            <p>분야 자료를 불러오지 못했습니다.</p>
            <button type="button" onClick={() => setSummaryRequest((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> 다시 불러오기</button>
          </section>
        ) : !summary ? <LoadingNotice>분야 자료를 불러오는 중입니다.</LoadingNotice> : (
          <>
            <div className={styles.workspace}>
              <section className={styles.fieldRail} aria-labelledby="field-list-title" onKeyDown={navigateFields}>
                <div className={styles.fieldList}>
                  <LayoutGroup id={selectionGroup}>
                    {summary.fields.map((field) => <FieldSummaryCard key={field.fieldId} field={field} selected={selectedFieldId === field.fieldId} onOpen={openField} />)}
                  </LayoutGroup>
                </div>
                <button type="button" className={styles.detailJump} onClick={() => {
                  detailPanel.current?.focus({ preventScroll: true })
                  detailPanel.current?.scrollIntoView({ behavior: reducedMotion ? 'instant' : 'smooth', block: 'start' })
                }}>선택한 분야 상세 보기 <ArrowDown size={16} aria-hidden="true" /></button>
              </section>
              <div id="market-flow-detail" className={styles.detailPanel} ref={detailPanel} tabIndex={-1} aria-label="선택한 분야 상세">
                <p className={styles.srOnly} role="status">{displayedDetail?.status === 'success' ? `${selected?.displayName} 분야 상세를 불러왔습니다.` : ''}</p>
                {displayedDetail?.status === 'success' ? (
                  // 퇴장 애니메이션으로 이전 분야가 남지 않도록 새 선택을 즉시 반영한다.
                  <FieldDetail key={displayedDetail.fieldId} detail={displayedDetail.data} />
                ) : displayedDetail?.status === 'error' ? (
                  <section className={styles.loadFailure} role="alert">
                    <h2>{selected?.displayName}</h2>
                    <p>분야 상세를 불러오지 못했습니다. 다른 분야는 계속 확인할 수 있습니다.</p>
                    <button type="button" onClick={() => setDetailRequest((value) => value + 1)}><RefreshCw size={15} aria-hidden="true" /> 상세 다시 불러오기</button>
                  </section>
                ) : <LoadingNotice>{`${selected?.displayName ?? '분야'} 상세를 불러오는 중입니다.`}</LoadingNotice>}
              </div>
            </div>
            <p className={styles.historyNotice}>{summary.historicalComparisonNotice}</p>
            <SectorSelector onSelectionChange={setSectorCode} />
          </>
        )}

        {/* 국내 시장/섹터는 WB04, 뉴스는 WB04·012, 전략 비교는 후속 연결 작업에 의존한다. */}
        <section className={styles.connections} aria-labelledby="reference-title">
          <div className={styles.connectionHeader}><span className={styles.eyebrow}>NEXT PERSPECTIVE</span><h2 id="reference-title">흐름을 더 넓게 읽는 방법</h2><p>분야의 변화에서 다음 질문으로.</p></div>
          <div className={styles.connectionGrid}>
            <article><ChartNoAxesCombined size={22} aria-hidden="true" /><span className={styles.connectionLabel}>MARKET CONTEXT</span><h3>국내 시장 참고 정보</h3><p>분야별 경제 흐름과는 별개로, 국내 주가지수의 수준과 변동성을 참고로 둡니다.</p><DomesticMarketReference /></article>
            <article><Newspaper size={22} aria-hidden="true" /><span className={styles.connectionLabel}>BEHIND THE NUMBERS</span><h3>시장 흐름 뉴스</h3><p>지표와 관련된 소식입니다. 무엇을 싣고 어떻게 묶을지는 뉴스 묶음이 정합니다.</p><NewsDigestSection sectorCode={sectorCode} /></article>
            <article><Waypoints size={22} aria-hidden="true" /><span className={styles.connectionLabel}>TEST YOUR STRATEGY</span><h3>이 기간으로 전략 비교</h3><p>사례를 먼저 고릅니다. 가까운 과거 사례를 고른 뒤 그 사례의 상세에서 전략 비교로 이어갈 수 있습니다.</p><Link to="/regime-search" className={styles.connectionLink} data-handoff={strategyComparison === null ? undefined : JSON.stringify(strategyComparison)}>과거 사례 고르러 가기 <ArrowUpRight size={17} aria-hidden="true" /></Link></article>
          </div>
        </section>
      </main>
    </div>
  )
}

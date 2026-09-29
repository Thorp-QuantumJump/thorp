import { Activity, ArrowDown, RefreshCw } from 'lucide-react'
import { LayoutGroup, motion, useReducedMotion } from 'motion/react'
import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { fetchMarketFlowField, fetchMarketFlowFields } from '@/api/marketFlow'
import { FieldDetail } from '@/components/marketflow/FieldDetail'
import { FieldSummaryCard } from '@/components/marketflow/FieldSummaryCard'
import { formatUpdatedAt } from '@/components/marketflow/marketFlowFormat'
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
  const selected = summary?.fields.find((field) => field.fieldId === selectedId) ?? summary?.fields[0]
  const selectedFieldId = selected?.fieldId
  /** 분야 중 가장 늦은 갱신 시각. ISO 문자열이라 사전순이 시간순이다. */
  const listUpdatedAt = summary?.fields.reduce<string | null>(
    (latest, field) => (field.lastUpdatedAt !== null && (latest === null || field.lastUpdatedAt > latest) ? field.lastUpdatedAt : latest),
    null,
  ) ?? null

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
            {/* 넓은 화면에서는 한 줄로 읽힌다. 뒷부분은 통째로 움직이는 덩어리라 폭이 모자라면 '분야별로'부터
                아랫줄로 내려가고, 단어 중간에서 끊기지 않는다. */}
            <h1>시장 흐름을 <span><em className={heading.emphasis}>분야별로</em> 확인하세요.</span></h1>
          </motion.div>
          <motion.div className={styles.heroAside} initial={introMotion} animate={{ opacity: 1, y: 0 }} transition={{ duration: reducedMotion ? 0 : 0.3, delay: reducedMotion ? 0 : 0.06 }}>
            <p>경제를 한 점수로 단정하지 않고, 일곱 분야의 실제 지표와 최근 3개월 경로를 나눠 보여 드립니다.</p>
          </motion.div>
        </header>

        <div className={styles.workspaceHeading}>
          {/* 번호를 매길 다음 구역이 없어 눈에 보이는 제목은 두지 않는다. 목록의 이름으로 보조기기에만 읽힌다. */}
          <h2 id="field-list-title" className={styles.srOnly}>분야별 경제 흐름</h2>
          {/* 목록 전체의 마지막 갱신은 여기 한 번만 적는다. 분야 줄은 이와 다를 때만 제 시각을 적는다(FR-011). */}
          <span>
            {summary ? `${summary.fields.length}개 분야` : '7개 분야'} <i aria-hidden="true">·</i> 최근 3개월
            {listUpdatedAt ? <><i aria-hidden="true">·</i> 마지막 갱신 {formatUpdatedAt(listUpdatedAt)} KST</> : null}
          </span>
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
                    {summary.fields.map((field) => <FieldSummaryCard key={field.fieldId} field={field} selected={selectedFieldId === field.fieldId} onOpen={openField} listUpdatedAt={listUpdatedAt} />)}
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
          </>
        )}

      </main>
    </div>
  )
}

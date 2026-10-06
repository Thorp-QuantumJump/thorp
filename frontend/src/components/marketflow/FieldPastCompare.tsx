import { ArrowUpRight, History } from 'lucide-react'
import { useId, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { InlineHelp } from '@/components/common/InlineHelp'
import { ComparisonMonthsNotice } from '@/components/regime/ComparisonMonthsNotice'
import { Disclosure } from '@/components/regime/Disclosure'
import * as copy from '@/components/regime/fixedCopy'
import { NewerReleases } from '@/components/regime/NewerReleases'
import type { CaseDetailState, PastSearchState } from '@/hooks/usePastCases'
import { buildCaseId, buildRegimeLinkStartPath, buildRegimeSearchPath, buildSimilarPeriodHandoff } from '@/marketflow/handoff'
import type { MarketFlowFieldId } from '@/types/marketFlow'
import type { RegimeCaseSummary, RegimeSearchResult } from '@/types/regime'
import { observationRange, shortRange } from './pastFormat'
import type { PastVerdict } from './pastOverlay'
import styles from './PastCompare.module.css'

interface FieldPastCompareProps {
  fieldId: MarketFlowFieldId
  fieldName: string
  /** 분야 상세의 대상월. 010의 비교 기준월과 다르면 그 사실을 밝힌다(009 FR-009). */
  referenceMonth: string | null
  /** 그래프에 그린 지금의 세 달. 범례에 적는다. */
  nowMonths: readonly string[]
  search: PastSearchState
  onRetrySearch: () => void
  /** 고른 사례의 끝 달. null이면 '지금만'이다. */
  selected: string | null
  onSelect: (endMonth: string | null) => void
  caseDetail: CaseDetailState | null
  onRetryCaseDetail: () => void
  /** 고른 사례가 상세를 열 수 없다(detailAvailable false). 당시 경로를 받지 않는다. */
  pastUnavailable: boolean
  /** 고른 사례의 당시 달(그래프의 달 순서대로). 범례에 적는다. */
  pastMonths: readonly (string | null)[] | null
  band: { name: string; window: string } | null
  showBand: boolean
  onToggleBand: (show: boolean) => void
  /** 어느 지표 칸에도 붙지 못한 비슷한 점·다른 점 문장. */
  unplaced: readonly PastVerdict[]
}

/**
 * 010 검색 화면으로 이어 가는 주소. 분야와 **010이 계산한 기준월**을 싣는다(009 FR-009).
 *
 * `?searchId=`로 열면 010 화면은 그것을 내 기록의 '다시 보기'로 다뤄, 시장 흐름에서 온 검색이 기록되지 않고 분야
 * 선택도 비어 있다(010 FR-032, 006 R17). 그래서 저장된 판이 아니라 분야·기준월로 연다. 결과를 아직 받지 못했으면
 * 분야 상세의 대상월로 연다 — 010 화면이 그 달을 그 분야의 최신 계산월과 견줘 물러난다(010 FR-031).
 */
function regimeEntryPath(fieldId: MarketFlowFieldId, month: string | null): string | null {
  return buildRegimeSearchPath(buildSimilarPeriodHandoff({ fieldId, referenceMonth: month }))
}

/**
 * 분야 상세 안의 '지금과 비슷했던 과거' 줄(009 FR-019).
 *
 * 010 분야 검색의 가까운 사례 세 개와 '지금만'을 라디오로 고르면, 고른 사례의 같은 지표 경로가 아래 지표
 * 그래프에 점선으로 겹친다. 사례 칸에는 관측 구간만 적는다 — 무엇이 비슷하고 다른지는 그 지표 칸에 붙는다.
 * 행동(이 기간으로 백테스트·자세히)은 고른 사례에 딸린 것이라 같은 줄 오른쪽에 둔다.
 *
 * 010의 신뢰 범위 표시는 숨기지 않는다(010 FR-037·FR-038·FR-031). 검색 상태와 사례 구분만 i 표시 뒤로 내리고
 * (보조기기에는 늘 읽힌다), 예시 자료·빠진 비교 시점·더 최신 발표·자료 기준 시점은 문구로 늘 보인다. 문장은
 * 응답이나 010의 fixedCopy에서만 온다(010 FR-022 ②).
 */
export function FieldPastCompare(props: FieldPastCompareProps) {
  const { fieldId, fieldName, referenceMonth, nowMonths, search, onRetrySearch } = props
  const titleId = useId()
  const groupName = useId()
  const result = search.status === 'ready' ? search.result : null
  const context = result ? `${fieldName} ${result.baselineMonth} 검색 결과` : ''
  const entryPath = regimeEntryPath(fieldId, result?.baselineMonth ?? referenceMonth)
  const hasCases = result !== null && result.status !== 'NOT_COMPUTABLE' && result.cases.length > 0

  return (
    <section className={styles.compare} aria-labelledby={titleId}>
      <div className={styles.compareHead}>
        <div className={styles.titleGroup}>
          <h3 id={titleId} className={styles.compareTitle}>
            <History size={16} aria-hidden="true" />
            지금과 비슷했던 과거
          </h3>
          {result ? (
            <InlineHelp label={`검색 상태${result.caseLabel ? '와 사례 구분' : ''}`} mark="i" side="bottom">
              <p><b>검색 상태</b> {result.statusNote}</p>
              {result.caseLabel ? <p><b>사례 구분</b> {result.caseLabel}</p> : null}
              <p>{copy.fieldOnlySimilarityNote(fieldName)}</p>
            </InlineHelp>
          ) : null}
        </div>
        <p className={styles.compareMeta}>
          {result ? <span>비교 기준월 {result.baselineMonth} · 가까운 순</span> : null}
          {entryPath ? (
            <Link className={styles.allLink} to={entryPath}>
              모두 보기 <ArrowUpRight size={13} aria-hidden="true" />
            </Link>
          ) : null}
        </p>
      </div>

      {search.status === 'loading' || search.status === 'idle' ? (
        <p className={styles.loading} role="status">{copy.loadingCases}</p>
      ) : search.status === 'failed' ? (
        <div className={styles.failure} role="status">
          <p>{copy.pastCasesLoadFailed}</p>
          <button type="button" className={styles.inlineAction} onClick={onRetrySearch}>다시 시도</button>
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
          {hasCases ? (
            <CaseChooser {...props} groupName={groupName} cases={result.cases} searchId={result.searchId} entryPath={entryPath} />
          ) : result.status !== 'NOT_COMPUTABLE' && result.thresholdNote ? (
            <Disclosure label="유사성 기준" context={context} tone="warning">
              <p>{result.thresholdNote}</p>
            </Disclosure>
          ) : (
            // 010의 EmptyResult는 h2를 쓴다. 이 줄은 분야 상세(h2) 안의 h3라 제목 차례가 뒤집히지 않게 문단으로 적는다.
            <p className={styles.emptyNote}>{result.statusNote}</p>
          )}
          {referenceMonth !== null && result.baselineMonth !== referenceMonth ? (
            <p className={styles.loading}>{copy.overlayBaselineNote(result.baselineMonth)}</p>
          ) : null}
          <TrustNotes result={result} context={context} />
        </>
      ) : null}
      {/* '지금만'이어도 범례는 남긴다. 보통 범위는 사례와 상관없이 켤 수 있다. */}
      {hasCases && nowMonths.length > 0 ? <CompareLegend {...props} /> : null}
    </section>
  )
}

/** 예시 자료·빠진 비교 시점·더 최신 발표·자료 기준 시점. 010 화면과 같은 컴포넌트와 문구를 쓴다. */
function TrustNotes({ result, context }: { result: RegimeSearchResult; context: string }) {
  return (
    <div className={styles.trust}>
      {result.versions.sampleData ? (
        <Disclosure label="예시 자료" context={context} tone="warning">
          <p>{copy.sampleDataNote}</p>
        </Disclosure>
      ) : null}
      <ComparisonMonthsNotice comparisonMonths={result.comparisonMonths} context={context} />
      <NewerReleases releases={result.dataAsOf.newerReleases} context={context} />
      {result.dataAsOf.note || result.dataAsOf.revisions.length > 0 ? (
        <div className={styles.dataAsOf} aria-label={`${context}: 자료 기준 시점`}>
          {result.dataAsOf.note ? <p>{result.dataAsOf.note}</p> : null}
          {result.dataAsOf.revisions.map((item) => (
            <p key={`${item.indicatorId}-${item.month}`}>개정 · {item.indicator} {item.month} · {item.revisedAt}</p>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function CaseChooser({ cases, searchId, entryPath, groupName, selected, onSelect, unplaced }: FieldPastCompareProps & {
  groupName: string
  cases: RegimeCaseSummary[]
  searchId: string
  entryPath: string | null
}) {
  const chosen = cases.find((item) => item.endMonth === selected) ?? null
  const caseId = chosen ? buildCaseId(searchId, chosen.endMonth) : null
  return (
    <>
      <div className={styles.chooser}>
        <fieldset className={styles.options} style={{ '--case-count': cases.length } as CSSProperties}>
          <legend className="sr-only">그래프에 겹쳐 볼 과거 구간</legend>
          <label className={`${styles.option} ${styles.nowOnly}`}>
            <input type="radio" name={groupName} checked={selected === null} onChange={() => onSelect(null)} />
            <span className={styles.optionFace}>지금만</span>
          </label>
          {cases.map((item, index) => (
            <label key={item.endMonth} className={styles.option}>
              <input
                type="radio"
                name={groupName}
                checked={selected === item.endMonth}
                onChange={() => onSelect(item.endMonth)}
                aria-label={`${observationRange(item)}${index === 0 ? ', 가장 가까운 사례' : ''}`}
              />
              <span className={styles.optionFace}>
                <span className={styles.rank} aria-hidden="true">{index + 1}</span>
                {observationRange(item)}
              </span>
            </label>
          ))}
        </fieldset>
        <div className={styles.actions}>
          {chosen && caseId ? (
            <Link className={styles.backtest} to={buildRegimeLinkStartPath({ caseId })}>
              이 기간으로 백테스트
              <small>{observationRange(chosen)}</small>
            </Link>
          ) : (
            <button type="button" className={styles.backtest} disabled>
              이 기간으로 백테스트
              <small>{chosen ? observationRange(chosen) : '과거 구간을 고르세요'}</small>
            </button>
          )}
          {chosen && entryPath ? (
            <Link className={styles.secondary} to={entryPath}>자세히</Link>
          ) : (
            <button type="button" className={styles.secondary} disabled>자세히</button>
          )}
        </div>
      </div>
      {unplaced.length > 0 ? (
        <div className={styles.notes}>
          {unplaced.map((verdict) => (
            <p key={verdict.text} className={styles.reason}>
              <b className={verdict.kind === 'similar' ? styles.similarLabel : styles.differentLabel}>
                {verdict.kind === 'similar' ? '비슷한 점' : '다른 점'}
              </b>
              <span className={verdict.kind === 'similar' ? styles.similarText : styles.differentText}>{verdict.text}</span>
            </p>
          ))}
        </div>
      ) : null}
    </>
  )
}

/** 실선은 지금, 점선은 당시, 옅은 띠는 보통 범위다. 당시 경로를 받는 중이거나 받지 못했으면 그 자리에 적는다. */
function CompareLegend({ nowMonths, selected, caseDetail, onRetryCaseDetail, pastUnavailable, pastMonths, band, showBand, onToggleBand }: FieldPastCompareProps) {
  const nowRange = shortRange(nowMonths)
  const pastRange = pastMonths ? shortRange(pastMonths) : null
  return (
    <div className={styles.legend}>
      {nowRange ? <span className={styles.key}><span className={styles.swatchNow} aria-hidden="true" />지금 {nowRange}</span> : null}
      {selected === null ? null : pastUnavailable ? (
        <span role="status">{copy.pastPathUnavailable}</span>
      ) : caseDetail?.status === 'loading' ? (
        <span role="status">{copy.loadingPastPath}</span>
      ) : caseDetail?.status === 'failed' ? (
        <span className={styles.key} role="status">
          {copy.pastPathLoadFailed}
          <button type="button" className={styles.inlineAction} onClick={onRetryCaseDetail}>다시 시도</button>
        </span>
      ) : caseDetail?.status === 'mismatch' ? (
        <span role="status">{copy.detailMismatchMessage}</span>
      ) : pastRange ? (
        <span className={styles.key}><span className={styles.swatchPast} aria-hidden="true" />당시 {pastRange}</span>
      ) : null}
      {band ? (
        <span className={styles.key}>
          <label className={styles.bandToggle}>
            <input type="checkbox" checked={showBand} onChange={(event) => onToggleBand(event.target.checked)} />
            <span className={styles.swatchBand} aria-hidden="true" />
            {band.name}
          </label>
          {/* 기간은 마우스 title이 아니라 도움말로 둔다. 키보드·터치·보조기기 사용자에게도 닿아야 한다. */}
          <InlineHelp label={band.name} side="top"><p>{copy.usualRangeNote(band.window)}</p></InlineHelp>
        </span>
      ) : null}
    </div>
  )
}

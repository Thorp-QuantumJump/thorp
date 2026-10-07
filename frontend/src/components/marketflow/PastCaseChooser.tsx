import { useId, useState } from 'react'
import { Link } from 'react-router-dom'
import { InlineHelp } from '@/components/common/InlineHelp'
import { CaseDetailLoader } from '@/components/regime/CaseDetailLoader'
import * as copy from '@/components/regime/fixedCopy'
import type { CaseDetailState } from '@/hooks/usePastCases'
import { buildCaseId, buildRegimeLinkStartPath } from '@/marketflow/handoff'
import type { RegimeCaseSummary, RegimeSearchResult } from '@/types/regime'
import { observationRange, shortRange } from './pastFormat'
import type { PastVerdict } from './pastOverlay'
import styles from './PastCompare.module.css'

/*
  분야 상세와 종합 비교가 함께 쓰는 '지금과 비슷했던 과거' 줄의 부품(009 FR-019). 사례 칸을 고르면 아래 지표 그래프에
  그 사례의 당시 경로가 겹친다. 문장은 응답이나 010의 fixedCopy에서만 온다(010 FR-022 ②).
*/

export interface PastCaseChooserProps {
  result: RegimeSearchResult
  /** 고를 수 있는 사례. 종합 비교에서 기준 충족 사례가 없으면 참고 사례다. */
  cases: readonly RegimeCaseSummary[]
  /** 기준 미충족 참고 사례의 끝 달. 그 칸에 '참고' 표를 단다(010 FR-021). */
  referenceMonths?: ReadonlySet<string>
  /** 고른 사례의 끝 달. 사례가 없을 때만 null이다. */
  selected: string | null
  onSelect: (endMonth: string) => void
  /** 어느 지표 칸에도 붙지 못한 비슷한 점·다른 점 문장. 종합 비교는 문장이 분야 묶음을 말해 모두 여기 선다. */
  unplaced: readonly PastVerdict[]
  /** 종합 사례 상세의 분야(v1) 이름. v3 결과는 쓰지 않는다. */
  fieldNames?: Record<string, string>
}

/**
 * 사례 칸(라디오)과 행동 버튼. 칸은 한 번에 셋이 보이고 나머지(최대 열 개)는 가로로 넘긴다 — 초록 스크롤 막대가 더
 * 있다는 것을 알린다. 처음에는 가장 가까운 사례이고 '지금만'은 없다(2026-10-07). 사례 칸에는 관측 구간만 적는다.
 *
 * '자세히'는 고른 사례의 010 사례 상세(계산 근거)를 이 줄 바로 아래에 펼친다 — 010 검색 화면으로 넘기지 않는다.
 * 사후 구간 자리에는 011 진입을 둔다(011 진입 계약 §4).
 */
export function PastCaseChooser({ result, cases, referenceMonths, selected, onSelect, unplaced, fieldNames = {} }: PastCaseChooserProps) {
  const groupName = useId()
  const detailId = useId()
  const [detailOpen, setDetailOpen] = useState(false)
  const chosen = cases.find((item) => item.endMonth === selected) ?? null
  const caseId = chosen ? buildCaseId(result.searchId, chosen.endMonth) : null
  const contextLinks = chosen?.context ?? []
  return (
    <>
      <div className={styles.chooser}>
        {/* fieldset 은 Chrome 에서 내용 폭보다 줄어들지 않아 가로 스크롤 상자가 되지 못한다. 묶음 역할의 div 로 둔다. */}
        <div role="group" className={styles.options} aria-label="그래프에 겹쳐 볼 과거 구간">
          {cases.map((item, index) => {
            const reference = referenceMonths?.has(item.endMonth) ?? false
            return (
              <label key={item.endMonth} className={styles.option}>
                <input
                  type="radio"
                  name={groupName}
                  checked={selected === item.endMonth}
                  onChange={() => onSelect(item.endMonth)}
                  aria-label={`${observationRange(item)}${index === 0 ? ', 가장 가까운 사례' : ''}${reference ? ', 기준 미충족 참고 사례' : ''}`}
                />
                <span className={styles.optionFace}>
                  <span className={styles.rank} aria-hidden="true">{index + 1}</span>
                  {observationRange(item)}
                  {reference ? <span className={styles.referenceTag} aria-hidden="true">참고</span> : null}
                </span>
              </label>
            )
          })}
        </div>
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
          <button
            type="button"
            className={styles.secondary}
            disabled={chosen === null || !chosen.detailAvailable}
            aria-expanded={detailOpen}
            aria-controls={detailId}
            onClick={() => setDetailOpen((current) => !current)}
          >
            {detailOpen ? '닫기' : '자세히'}
          </button>
        </div>
      </div>
      {unplaced.length > 0 || contextLinks.length > 0 ? (
        <div className={styles.notes}>
          {unplaced.map((verdict) => (
            <p key={verdict.text} className={styles.reason}>
              <b className={verdict.kind === 'similar' ? styles.similarLabel : styles.differentLabel}>
                {verdict.kind === 'similar' ? '비슷한 점' : '다른 점'}
              </b>
              <span className={verdict.kind === 'similar' ? styles.similarText : styles.differentText}>{verdict.text}</span>
            </p>
          ))}
          {contextLinks.length > 0 ? (
            // 특이 사례의 관측 구간 안 공식 발간물·사건. 검색 입력이 아니고 순위의 근거도 아니다(010 v3 §4).
            <p className={styles.context}>
              <b className={styles.contextLabel}>당시 맥락</b>
              {contextLinks.map((link) => (
                <span key={`${link.kind}-${link.date}-${link.title}`} className={styles.contextItem}>
                  <time dateTime={link.date}>{link.date}</time>{' '}
                  {link.url ? <a href={link.url} target="_blank" rel="noopener noreferrer">{link.title}</a> : <span>{link.title}</span>}
                  <span className={styles.contextSource}> · {link.source}</span>
                </span>
              ))}
              <InlineHelp label="당시 맥락" side="top"><p>{copy.contextNote}</p></InlineHelp>
            </p>
          ) : null}
        </div>
      ) : null}
      {detailOpen && chosen && chosen.detailAvailable ? (
        // 고른 사례를 바꾸면 그 사례의 상세로 따라간다.
        <div id={detailId} className={styles.detailSlot}>
          <CaseDetailLoader
            key={chosen.endMonth}
            searchId={result.searchId}
            summary={chosen}
            mode={result.mode}
            fieldNames={fieldNames}
            postPeriodSlot={caseId === null ? undefined : (
              <p className={styles.postPeriod}>
                <Link className={styles.pick} to={buildRegimeLinkStartPath({ caseId })}>이 기간으로 백테스트</Link>
              </p>
            )}
          />
        </div>
      ) : null}
    </>
  )
}

export interface CompareLegendProps {
  /** 그래프에 그린 지금의 세 달. 범례에 적는다. */
  nowMonths: readonly string[]
  /** 고른 사례의 끝 달. 사례가 없을 때만 null이다. */
  selected: string | null
  caseDetail: CaseDetailState | null
  onRetryCaseDetail: () => void
  /** 고른 사례가 상세를 열 수 없다(detailAvailable false). 당시 경로를 받지 않는다. */
  pastUnavailable: boolean
  /** 고른 사례의 당시 달(그래프의 달 순서대로). 범례에 적는다. */
  pastMonths: readonly (string | null)[] | null
  band: { name: string; window: string } | null
  showBand: boolean
  onToggleBand: (show: boolean) => void
}

/** 실선은 지금, 점선은 당시, 옅은 띠는 보통 범위다. 당시 경로를 받는 중이거나 받지 못했으면 그 자리에 적는다. */
export function CompareLegend({ nowMonths, selected, caseDetail, onRetryCaseDetail, pastUnavailable, pastMonths, band, showBand, onToggleBand }: CompareLegendProps) {
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

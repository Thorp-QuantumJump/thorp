import type {
  RegimeLinkBasis,
  RegimeLinkCase,
  RegimeLinkRun,
  RegimeLinkView,
} from '@/types/regimeLink'
import { CaseNewsPanel } from './CaseNewsPanel'
import { observationRange } from './observationRange'
import {
  auxiliaryWindowNote,
  basisNote,
  historicalOnlyNote,
  turnoverBasisNote,
} from './fixedCopy'
import { FullPeriodSummary } from './FullPeriodSummary'
import { KospiReference } from './KospiReference'
import { PostWindowDetail } from './PostWindowDetail'
import { PostWindowCompleteness, ResultSlotState } from './ResultSlotState'
import { resultSlotKind } from './resultSlot'
import { ScenarioToggle } from './ScenarioToggle'

interface CaseStrategyResultsProps {
  basis: RegimeLinkBasis | null
  caseItem: RegimeLinkCase
  /** 005 순서(기준 → 비교 대상) 그대로 받는다. 사후 성과로 다시 정렬하지 않는다. */
  runs: RegimeLinkRun[]
  links: RegimeLinkView[]
  freshLinks: RegimeLinkView[]
  onRetry: (linkId: string) => void
  onEnableFreshCash: () => void
  /** FR-018의 한 문장. 사례 부족·사후 자료 미완성·검색 불안정을 결과 옆에 둔다. */
  statusNote?: string | null
  /** 계약에 비용 버전 필드가 없어 화면이 지어내지 않는다. 값이 오면 그린다. */
  costVersion?: string | null
}

/**
 * 사례의 전략 결과 영역 (T061 · T064 · T065 · T073).
 *
 * `basis.kind`가 없으면 **영역을 그리지 않는다**(T064) — 근거 표기 없이 역사 값을 내보내지 않는다.
 */
export function CaseStrategyResults({
  basis,
  caseItem,
  runs,
  links,
  freshLinks,
  onRetry,
  onEnableFreshCash,
  statusNote,
  costVersion,
}: CaseStrategyResultsProps) {
  if (!basis || basis.kind !== 'HISTORICAL_REVISION') return null

  const window = caseItem.postWindow
  const observation = observationRange(caseItem.endMonth)

  return (
    <section aria-labelledby={`case-results-${caseItem.endMonth}`} data-case-id={caseItem.caseId}>
      <h3 id={`case-results-${caseItem.endMonth}`}>{`${caseItem.endMonth} 사례의 전략 결과`}</h3>
      <p>{basisNote}</p>
      <p>{historicalOnlyNote}</p>
      <dl>
        <dt>관측 구간</dt>
        <dd>{`${observation.from.slice(0, 10)} ~ ${observation.to.slice(0, 10)}`}</dd>
        <dt>사후 구간</dt>
        <dd>{`${window.d0} ~ ${window.d63 ?? '진행 중'}`}</dd>
        <dt>워밍업 거래일</dt>
        <dd>{runs.map((run) => run.warmupTradingDays).join(' · ')}</dd>
        {costVersion && (
          <>
            <dt>비용 버전</dt>
            <dd>{costVersion}</dd>
          </>
        )}
      </dl>
      <p>{turnoverBasisNote}</p>
      <p>{auxiliaryWindowNote}</p>
      {statusNote && <p role="status">{statusNote}</p>}

      <ol>
        {runs.map((run) => {
          const link = links.find((item) => item.publicToken === run.publicToken) ?? null
          return (
            <li key={run.publicToken} data-run-role={run.role}>
              <h4>{run.strategyName ?? run.strategyId}</h4>
              {link && <ResultSlotState link={link} onRetry={onRetry} />}
              {link && <PostWindowCompleteness link={link} />}
              {link && resultSlotKind(link) === 'READY' && link.result?.window63 && (
                <>
                  <dl>
                    <dt>수익률</dt>
                    <dd>{link.result.window63.return}</dd>
                    <dt>최대 하락 폭</dt>
                    <dd>{link.result.window63.maxDrawdown}</dd>
                    <dt>거래 횟수</dt>
                    <dd>{link.result.window63.tradeCount}</dd>
                    <dt>월당 거래 횟수</dt>
                    <dd>{link.result.window63.tradesPerMonth}</dd>
                    <dt>회전율(연환산)</dt>
                    <dd>{link.result.window63.turnover}</dd>
                    <dt>거래비용 합계</dt>
                    <dd>{link.result.window63.costs.total}</dd>
                  </dl>
                  <PostWindowDetail result={link.result} />
                  <KospiReference kospi={link.result.kospi} />
                </>
              )}
              <FullPeriodSummary token={run.publicToken} />
            </li>
          )
        })}
      </ol>

      <ScenarioToggle onEnable={onEnableFreshCash} freshLinks={freshLinks} />
      <CaseNewsPanel endMonth={caseItem.endMonth} />
    </section>
  )
}

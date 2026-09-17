import { type KeyboardEvent, type ReactNode, useCallback, useEffect, useRef } from 'react'
import { fetchResultExceptions, fetchResultWeights } from '@/api/result'
import { Button } from '@/components/ui/button'
import { type ResourceState, useResource } from '@/hooks/useResource'
import { useSectorCatalogState } from '@/hooks/useSectorCatalogState'
import type {
  PublicResultDetail,
  ResultConfigItem,
  ResultExceptions,
  ResultWeights,
} from '@/types/result'
import { COMPUTED_WEIGHTS_NOTE, strategyLabel } from './conditionLine'
import { formatMoney, formatRatio } from './formatResult'
import { HoldingDifference } from './HoldingDifference'
import { InstrumentProfileDisclosure } from './InstrumentProfileDisclosure'
import { ResourceArea } from './ResourceArea'
import { SUMMARY_NOT_CALCULATED } from './ResultSummaryCard'
import { type SectorLabels, buildSectorLabels } from './sectorLabels'
import { TradeHistory } from './TradeHistory'
import styles from './resultDetail.module.css'

/**
 * '자세히 보기' 패널(FR-007~FR-009, T064).
 *
 * <p>결과 바로 아래에서 열고 주소를 바꾸지 않는다 — 설정 화면으로 되돌리지 않는다(SC-006). 첫 화면과 함께 받지
 * 않고, 열 때 `/weights`·`/exceptions`와 섹터 목록을 받는다(SC-001). 거래 내역과 대표 상품 정보는 패널 안에서 다시
 * 한 번 열어야 받는다.
 *
 * <p><b>값이 없으면 없다고 보인다.</b> 계산이 제한된 항목은 '계산 제한'으로, 004가 아직 공개 조회로 내보내지 않는
 * 상관관계·위험 기여·손실 기여는 '제공되지 않음'으로 둔다. 어느 쪽도 0이나 임의 값으로 채우지 않는다(FR-009,
 * 원칙 VI). 계산이 제한되었는지는 004가 판정해 준 대로 보이고 이 화면이 자료 길이로 판정하지 않는다.
 */

/** 비교 대상이 준비되었을 때 B05가 넘긴다. 없으면 보유 섹터 차이를 그리지 않는다 */
export type DetailCompareTarget = {
  token: string
  name: string
  items: readonly ResultConfigItem[]
}

type DetailPanelProps = {
  id: string
  token: string
  detail: PublicResultDetail
  compare?: DetailCompareTarget | null
  onClose: () => void
}

export const NOT_PROVIDED = '제공되지 않음'
export const CALCULATION_LIMITED = '계산 제한'
const EMPTY = '없음'

export function DetailPanel({ id, token, detail, compare = null, onClose }: DetailPanelProps) {
  const titleRef = useRef<HTMLHeadingElement>(null)
  const titleId = `${id}-title`

  // 열면 제목으로 초점을 옮긴다. 키보드 사용자가 버튼 줄의 나머지를 건너 내려오지 않아도 된다.
  useEffect(() => {
    titleRef.current?.focus()
  }, [])

  const loadWeights = useCallback(
    (signal: AbortSignal) => fetchResultWeights(token, signal),
    [token],
  )
  const loadExceptions = useCallback(
    (signal: AbortSignal) => fetchResultExceptions(token, signal),
    [token],
  )
  const weights = useResource(loadWeights)
  const exceptions = useResource(loadExceptions)
  const catalog = useSectorCatalogState()

  const { config } = detail
  const labels = buildSectorLabels(
    compare ? [...config.items, ...compare.items] : config.items,
    catalog.status === 'loaded' ? catalog.catalog : null,
  )

  const closeOnEscape = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key !== 'Escape') return
    event.stopPropagation()
    onClose()
  }

  return (
    <section id={id} className={styles.panel} aria-labelledby={titleId} onKeyDown={closeOnEscape}>
      <div className={styles.panelHeader}>
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className={styles.panelTitle}>
          자세히 보기
        </h2>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          닫기
        </Button>
      </div>

      <div className={styles.grid}>
        <RecoveryItem detail={detail} />
        <TurnoverItem detail={detail} />
      </div>

      <div className={styles.item}>
        <div className={styles.grid}>
          <Item title="상관관계">
            <p className={styles.limited}>{NOT_PROVIDED}</p>
            <dl className={styles.facts}>
              <dt>추정 기간</dt>
              <dd>{NOT_PROVIDED}</dd>
            </dl>
          </Item>
          <Item title="위험 기여">
            <p className={styles.limited}>{NOT_PROVIDED}</p>
            <dl className={styles.facts}>
              <dt>추정 기간</dt>
              <dd>{NOT_PROVIDED}</dd>
              <dt>공분산 산정법</dt>
              <dd>{NOT_PROVIDED}</dd>
            </dl>
          </Item>
          <Item title="손실 기여">
            <p className={styles.limited}>{NOT_PROVIDED}</p>
          </Item>
        </div>
        <p className={styles.muted}>
          세 항목은 아직 결과 조회로 받을 수 없어 값을 보이지 않습니다. 0이나 임의 값으로 채우지 않습니다.
        </p>
      </div>

      <div className={styles.grid}>
        <CostItem detail={detail} />
        <RulesItem detail={detail} labels={labels} />
      </div>

      <Item title="목표·실제 비중">
        <ResourceArea resource={weights} loadingText="비중 기록을 불러오는 중입니다.">
          {(data) => <WeightsBody weights={data} labels={labels} />}
        </ResourceArea>
      </Item>

      {compare ? (
        <CompareHoldings
          base={{ name: strategyLabel(detail), items: config.items, weights }}
          target={compare}
          labels={labels}
        />
      ) : null}

      <Item title="계산 제한 항목">
        <ResourceArea resource={exceptions} loadingText="예외 처리 기록을 불러오는 중입니다.">
          {(data) => <ExceptionsBody exceptions={data} labels={labels} />}
        </ResourceArea>
      </Item>

      <Item title="전체 거래 내역">
        <TradeHistory token={token} items={config.items} labels={labels} />
      </Item>

      <Item title="대표 ETF 구성·구성종목 중복·출처">
        <p className={styles.muted}>
          실제로 담았는지와 무관하게 이 결과의 섹터 후보를 모두 둡니다. 펼친 상품만 불러옵니다.
        </p>
        <InstrumentProfileDisclosure items={config.items} catalog={catalog} />
      </Item>
    </section>
  )
}

function Item({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={styles.item}>
      <h3 className={styles.itemTitle}>{title}</h3>
      {children}
    </section>
  )
}

/** 회복 기간. 004 정의: 하락이 없으면 0, 끝까지 회복하지 못하면 `null`이다(워커 `calculate_analytics`). */
function RecoveryItem({ detail }: { detail: PublicResultDetail }) {
  const analytics = detail.summary?.analytics ?? null

  let body: ReactNode
  if (detail.summary === null) {
    body = <p className={styles.copy}>{SUMMARY_NOT_CALCULATED}</p>
  } else if (analytics === null) {
    body = <p className={styles.limited}>{CALCULATION_LIMITED}: 이 결과에는 회복 기간이 계산되지 않았습니다.</p>
  } else if (analytics.recoveryDays === null) {
    body = <p className={styles.copy}>분석 기간이 끝날 때까지 이전 고점을 되찾지 못했습니다.</p>
  } else if (analytics.recoveryDays === 0) {
    body = <p className={styles.copy}>줄어든 적이 없어 회복할 구간이 없었습니다.</p>
  } else {
    body = <p className={styles.value}>{analytics.recoveryDays}거래일</p>
  }

  return (
    <Item title="회복 기간">
      {body}
      <p className={styles.muted}>가장 크게 줄어든 저점에서 이전 고점을 다시 넘기까지 걸린 거래일 수입니다.</p>
    </Item>
  )
}

function TurnoverItem({ detail }: { detail: PublicResultDetail }) {
  const turnover = detail.summary?.analytics?.turnover ?? null

  return (
    <Item title="회전율">
      {detail.summary === null ? (
        <p className={styles.copy}>{SUMMARY_NOT_CALCULATED}</p>
      ) : turnover === null ? (
        <p className={styles.limited}>{CALCULATION_LIMITED}: 이 결과에는 회전율이 계산되지 않았습니다.</p>
      ) : (
        <p className={styles.value}>연 {formatRatio(turnover)}</p>
      )}
      <p className={styles.muted}>
        사고판 금액의 절반을 평균 자산으로 나누고 1년(252거래일) 기준으로 환산한 값입니다.
      </p>
    </Item>
  )
}

function CostItem({ detail }: { detail: PublicResultDetail }) {
  const costProfile = detail.versions.costProfile
  const costs = detail.summary?.costs ?? null

  return (
    <Item title="비용 가정">
      <dl className={styles.facts}>
        <dt>비용 기준</dt>
        <dd>
          {costProfile
            ? `${costProfile.version}${costProfile.provisional ? ' (임시 비용 기준)' : ''}`
            : '확인할 수 없음'}
        </dd>
        {costs ? (
          <>
            <dt>수수료</dt>
            <dd>{formatMoney(costs.commission)}</dd>
            <dt>스프레드</dt>
            <dd>{formatMoney(costs.spread)}</dd>
            <dt>슬리피지</dt>
            <dd>{formatMoney(costs.slippage)}</dd>
            <dt>세금</dt>
            <dd>{formatMoney(costs.tax)}</dd>
            <dt>합계</dt>
            <dd>{formatMoney(costs.total)}</dd>
          </>
        ) : null}
      </dl>
      {/* 요약이 없는 결과(취소 뒤 지표 없음)는 계산 제한과 다른 갈래다. 004가 `summaryUnavailable`로 이유를 준다. */}
      {costs ? null : <p className={styles.copy}>{SUMMARY_NOT_CALCULATED}</p>}
    </Item>
  )
}

function RulesItem({ detail, labels }: { detail: PublicResultDetail; labels: SectorLabels }) {
  const { config, versions } = detail

  return (
    <Item title="비교 규칙">
      <p className={styles.copy}>
        비교 대상은 섹터 후보·평가 기간·시작 금액·비용 기준을 그대로 두고 전략만 바꿔 계산합니다. 평가 기간이
        달라지면 한 그래프에 겹쳐 그리지 않습니다.
      </p>
      <dl className={styles.facts}>
        <dt>전략</dt>
        <dd>{strategyLabel(detail)}</dd>
        <dt>섹터 후보</dt>
        <dd>{config.items.map((item) => labels.instrument(item.instrumentId)).join(', ')}</dd>
        <dt>요청한 기간</dt>
        <dd>
          {config.start} ~ {config.end}
        </dd>
        <dt>실제 평가 기간</dt>
        <dd>
          {config.effectiveStart} ~ {config.effectiveEnd}
        </dd>
        <dt>준비 기간</dt>
        <dd>{config.warmupTradingDays}거래일</dd>
        <dt>시작 금액</dt>
        <dd>{formatMoney(config.initialCash)}</dd>
        <dt>규칙 버전</dt>
        <dd>
          {versions.rules
            ? `${versions.rules.version}${versions.rules.provisional ? ' (임시)' : ''}`
            : config.rulesVersion}
        </dd>
        <dt>대응표 버전</dt>
        <dd>{config.mappingVersion}</dd>
        <dt>자료 버전</dt>
        <dd>{versions.data ?? '확인할 수 없음'}</dd>
        <dt>평가 구분 버전</dt>
        <dd>{versions.evaluationSplit ?? config.evaluationSplitVersion ?? EMPTY}</dd>
      </dl>
    </Item>
  )
}

function WeightsBody({ weights, labels }: { weights: ResultWeights; labels: SectorLabels }) {
  const { points, weightsSource, partial } = weights
  const sourceNote =
    weightsSource === 'COMPUTED' ? COMPUTED_WEIGHTS_NOTE : '입력한 비중을 목표로 삼았습니다.'
  const last = points.at(-1)

  if (!last) {
    return (
      <>
        <p className={styles.copy}>{sourceNote}</p>
        <p className={styles.copy}>비중 기록이 없습니다.</p>
      </>
    )
  }

  const instrumentIds = [...new Set([...Object.keys(last.target), ...Object.keys(last.actual)])]
  if (instrumentIds.length === 0) {
    // 004 예시에도 있는 모양이다(리밸런싱에서 모두 판 날). 머리글만 있는 표를 그리지 않는다.
    return (
      <>
        <p className={styles.copy}>{sourceNote}</p>
        <p className={styles.copy}>
          마지막 리밸런싱({last.date})에서는 담은 종목이 없습니다. 리밸런싱 {points.length}회
        </p>
        {partial ? <p className={styles.muted}>완료 범위까지의 기록입니다.</p> : null}
      </>
    )
  }

  return (
    <>
      <p className={styles.copy}>{sourceNote}</p>
      <div className={styles.tableScroll}>
        <table className={styles.table}>
          <caption>
            마지막 리밸런싱({last.date}) 기준 · 리밸런싱 {points.length}회
          </caption>
          <thead>
            <tr>
              <th scope="col">종목</th>
              <th scope="col">목표 비중</th>
              <th scope="col">실제 비중</th>
            </tr>
          </thead>
          <tbody>
            {instrumentIds.map((instrumentId) => (
              <tr key={instrumentId}>
                <td>{labels.instrument(instrumentId)}</td>
                <td>{last.target[instrumentId] === undefined ? EMPTY : formatRatio(last.target[instrumentId])}</td>
                <td>{last.actual[instrumentId] === undefined ? EMPTY : formatRatio(last.actual[instrumentId])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className={styles.muted}>목표 비중에서 현금 몫은 빠져 있어 합이 100%보다 작을 수 있습니다.</p>
      {partial ? <p className={styles.muted}>완료 범위까지의 기록입니다.</p> : null}
    </>
  )
}

function ExceptionsBody({
  exceptions,
  labels,
}: {
  exceptions: ResultExceptions
  labels: SectorLabels
}) {
  const { items, partial } = exceptions
  if (items.length === 0) {
    return <p className={styles.copy}>계산을 제한하거나 처리 방식을 바꾼 기록이 없습니다.</p>
  }

  return (
    <>
      <div className={styles.tableScroll} data-tall={items.length > 10}>
        <table className={styles.table}>
          <caption>예외 처리 기록 {items.length}건</caption>
          <thead>
            <tr>
              <th scope="col">날짜</th>
              <th scope="col">대상</th>
              <th scope="col">종류</th>
              <th scope="col">규칙</th>
              <th scope="col">처리</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr key={`${item.date}-${item.kind}-${item.instrumentId ?? ''}-${index}`}>
                <td>{item.date}</td>
                <td>{item.instrumentId ? labels.instrument(item.instrumentId) : '포트폴리오 전체'}</td>
                <td>{item.kind}</td>
                <td>{item.rule ?? EMPTY}</td>
                <td>{item.action ?? EMPTY}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {partial ? <p className={styles.muted}>완료 범위까지의 기록입니다.</p> : null}
    </>
  )
}

/** 비교 대상의 비중 기록을 받아 보유 섹터 차이를 보인다. 비교 대상이 있을 때만 그려진다. */
function CompareHoldings({
  base,
  target,
  labels,
}: {
  base: {
    name: string
    items: readonly ResultConfigItem[]
    weights: ResourceState<ResultWeights> & { retry: () => void }
  }
  target: DetailCompareTarget
  labels: SectorLabels
}) {
  const loadTargetWeights = useCallback(
    (signal: AbortSignal) => fetchResultWeights(target.token, signal),
    [target.token],
  )
  const targetWeights = useResource(loadTargetWeights)

  // 기준 쪽 실패는 '목표·실제 비중'이 이미 알린다. 여기서는 비교 대상 쪽만 알린다.
  if (base.weights.status !== 'loaded') return null
  const baseWeights = base.weights.data

  return (
    <ResourceArea resource={targetWeights} loadingText="비교 대상의 비중 기록을 불러오는 중입니다.">
      {(data) => (
        <HoldingDifference
          base={{ name: base.name, items: base.items, weights: baseWeights }}
          target={{ name: target.name, items: target.items, weights: data }}
          labels={labels}
        />
      )}
    </ResourceArea>
  )
}

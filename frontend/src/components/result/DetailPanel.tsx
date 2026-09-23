import { type ReactNode, useCallback } from 'react'
import { fetchResultExceptions, fetchResultWeights } from '@/api/result'
import { type ResourceState, useResource } from '@/hooks/useResource'
import type { SectorCatalogState } from '@/hooks/useSectorCatalogState'
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
import { SUMMARY_NOT_CALCULATED } from './statusSentences'
import { type SectorLabels, buildSectorLabels } from './sectorLabels'
import { InlineHelp } from '@/components/common/InlineHelp'
import { TradeHistory } from './TradeHistory'
import styles from './resultDetail.module.css'

/**
 * '자세히 보기'가 갈라진 세 탭(FR-007~FR-009, T064·T098).
 *
 * <p><b>결과 분석</b>은 계산에서 나온 값(회복 기간·회전율·상관관계·위험 기여·손실 기여·계산 제한), <b>설정·근거</b>는
 * 사용자가 넣은 조건과 그 근거(비교 규칙·비용 가정·목표·실제 비중·대표 ETF 구성·출처), <b>전체 거래 내역</b>은
 * 거래 목록과 조건 질의다. 한 패널에 번갈아 두면 결과에서 나온 값과 넣은 값이 섞여 읽힌다.
 *
 * <p>셋 다 주소를 바꾸지 않고 설정 화면으로 되돌리지 않는다(SC-006). 각 탭은 처음 고를 때 제 자료를 받는다 —
 * 결과 분석은 `/exceptions`, 설정·근거는 `/weights`, 전체 거래 내역은 `/trades`(SC-001). 대표 상품 정보는 탭 안에서
 * 다시 한 번 열어야 받는다. 섹터 목록은 세 탭이 같이 쓰므로 `ResultDetailTabs`가 한 번 받아 넘긴다.
 *
 * <p><b>값이 없으면 없다고 보인다.</b> 계산이 제한된 항목은 '계산 제한'으로 두고 0이나 임의 값으로 채우지 않는다
 * (FR-009, 원칙 VI). 계산이 제한되었는지는 004가 판정해 준 대로(`analytics`의 항목이 `null`) 보이고 이 화면이 자료
 * 길이로 판정하지 않는다. 상관관계·위험 기여·손실 기여의 정의는 워커 `calculate_analytics`가 원본이다(420).
 */

/** 비교 대상이 준비되었을 때 B05가 넘긴다. 없으면 보유 섹터 차이를 그리지 않는다 */
export type DetailCompareTarget = {
  token: string
  name: string
  items: readonly ResultConfigItem[]
}

type PanelProps = {
  token: string
  detail: PublicResultDetail
  /** 섹터 이름·대표 상품을 찾는 001 목록. 부모가 한 번 받아 넘긴다 */
  catalog: SectorCatalogState
}

export const CALCULATION_LIMITED = '계산 제한'
const EMPTY = '없음'

/** '결과 분석' 탭. 계산에서 나온 값만 둔다. */
export function AnalysisPanel({ token, detail, catalog }: PanelProps) {
  const loadExceptions = useCallback(
    (signal: AbortSignal) => fetchResultExceptions(token, signal),
    [token],
  )
  const exceptions = useResource(loadExceptions)
  const { config } = detail
  const labels = buildSectorLabels(config.items, catalog.status === 'loaded' ? catalog.catalog : null)

  return (
    <div className={styles.stack}>
      <div className={styles.grid}>
        <RecoveryItem detail={detail} />
        <TurnoverItem detail={detail} />
      </div>

      <CorrelationItem detail={detail} labels={labels} />

      <div className={styles.grid}>
        <ContributionItem
          title="위험 기여"
          detail={detail}
          labels={labels}
          values={detail.summary?.analytics?.riskContribution ?? null}
          note="포트폴리오 전체 변동(분산) 가운데 각 종목이 만든 몫입니다. 실제 비중 평균과 일별 수익률 공분산으로 구하며 합은 100%입니다."
          showMethod
        />
        <ContributionItem
          title="손실 기여"
          detail={detail}
          labels={labels}
          values={detail.summary?.analytics?.lossContribution ?? null}
          note="구간 손익을 종목별로 나눈 값입니다. 이익이 난 종목도 함께 들어가며 합은 100%입니다."
        />
      </div>

      <Item title="계산 제한 항목">
        <ResourceArea resource={exceptions} loadingText="예외 처리 기록을 불러오는 중입니다.">
          {(data) => <ExceptionsBody exceptions={data} labels={labels} />}
        </ResourceArea>
      </Item>
    </div>
  )
}

/** '전체 거래 내역' 탭. 탭을 고르는 것이 여는 것이므로 목록을 바로 부른다. */
export function TradesPanel({ token, detail, catalog }: PanelProps) {
  const { config } = detail
  const labels = buildSectorLabels(config.items, catalog.status === 'loaded' ? catalog.catalog : null)
  return (
    <div className={styles.stack}>
      <Item title="전체 거래 내역">
        <TradeHistory token={token} items={config.items} labels={labels} alwaysOpen />
      </Item>
    </div>
  )
}

/** '설정·근거' 탭. 사용자가 넣은 조건과 그 근거를 둔다. 비교 대상이 있으면 보유 섹터 차이(FR-008)도 여기다. */
export function SettingsPanel({
  token,
  detail,
  catalog,
  compare = null,
}: PanelProps & { compare?: DetailCompareTarget | null }) {
  const loadWeights = useCallback(
    (signal: AbortSignal) => fetchResultWeights(token, signal),
    [token],
  )
  const weights = useResource(loadWeights)
  const { config } = detail
  const labels = buildSectorLabels(
    compare ? [...config.items, ...compare.items] : config.items,
    catalog.status === 'loaded' ? catalog.catalog : null,
  )

  return (
    <div className={styles.stack}>
      <div className={styles.grid}>
        <RulesItem detail={detail} labels={labels} />
        <CostItem detail={detail} />
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

      <Item title="대표 ETF 구성·구성종목 중복·출처">
        <p className={styles.muted}>
          실제로 담았는지와 무관하게 이 결과의 섹터 후보를 모두 둡니다. 펼친 상품만 불러옵니다.
        </p>
        <InstrumentProfileDisclosure items={config.items} catalog={catalog} />
      </Item>
    </div>
  )
}

function Item({
  title,
  help,
  children,
}: {
  title: string
  /** 제목 옆에 붙일 도움말. 본문에 늘 펼쳐 둘 필요가 없는 설명만 여기로 내린다 */
  help?: ReactNode
  children: ReactNode
}) {
  return (
    <section className={styles.item}>
      <div className={styles.itemHead}>
        <h3 className={styles.itemTitle}>{title}</h3>
        {help}
      </div>
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

/** 요약이 없으면 그 문장, 항목이 `null`이면 계산 제한. 둘 다 아니면 `null`을 돌려 값을 그리게 한다. */
function analyticsGap(detail: PublicResultDetail, present: boolean, what: string): ReactNode | null {
  if (detail.summary === null) return <p className={styles.copy}>{SUMMARY_NOT_CALCULATED}</p>
  if (!present) {
    return (
      <p className={styles.limited}>
        {CALCULATION_LIMITED}: 이 결과에는 {what}이 계산되지 않았습니다.
      </p>
    )
  }
  return null
}

/** 추정 기간은 실제 평가 기간이고, 알려진 산정법 코드는 읽을 수 있는 이름으로 표시한다. */
function EstimationFacts({ detail, showMethod }: { detail: PublicResultDetail; showMethod?: boolean }) {
  const { config } = detail
  const method = detail.summary?.analytics?.method ?? null
  return (
    <dl className={styles.facts}>
      <dt>추정 기간</dt>
      <dd>
        {config.effectiveStart} ~ {config.effectiveEnd}
      </dd>
      {showMethod ? (
        <>
          <dt>공분산 산정법</dt>
          <dd>{method === 'sample-covariance-daily' ? '일별 수익률의 표본 공분산' : method ?? EMPTY}</dd>
        </>
      ) : null}
    </dl>
  )
}

/** 종목 쌍의 상관 행렬. 정의되지 않은 쌍(변동성 0)은 워커가 키를 빼므로 빈칸으로 둔다. */
function CorrelationItem({ detail, labels }: { detail: PublicResultDetail; labels: SectorLabels }) {
  const matrix = detail.summary?.analytics?.correlationMatrix ?? null
  const gap = analyticsGap(detail, matrix !== null, '상관관계')
  const ids = matrix ? Object.keys(matrix) : []

  return (
    <Item title="상관관계">
      {gap ?? (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <caption>일별 수익률의 표본 상관 · 종목 {ids.length}개</caption>
            <thead>
              <tr>
                <th scope="col">종목</th>
                {ids.map((id) => (
                  <th key={id} scope="col">
                    {labels.instrument(id)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {ids.map((row) => (
                <tr key={row}>
                  <th scope="row">{labels.instrument(row)}</th>
                  {ids.map((col) => (
                    <td key={col}>{formatCorrelation(matrix?.[row][col])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <EstimationFacts detail={detail} />
      <p className={styles.muted}>
        1에 가까우면 같이 움직이고 -1에 가까우면 반대로 움직입니다. 평가 기간의 일별 수익률로 계산합니다.
      </p>
    </Item>
  )
}

/** 상관은 비율이 아니라 -1~1 값이라 `formatRatio`를 쓰지 않는다. 읽을 수 없으면 받은 문자열 그대로(원칙 VI). */
function formatCorrelation(value: string | undefined): string {
  if (value === undefined) return EMPTY
  const n = Number(value)
  return value.trim() === '' || !Number.isFinite(n) ? value : n.toFixed(2)
}

/** 위험 기여·손실 기여. 종목별 비율 한 줄씩이고 합은 1이다. */
function ContributionItem({
  title,
  detail,
  labels,
  values,
  note,
  showMethod,
}: {
  title: string
  detail: PublicResultDetail
  labels: SectorLabels
  values: Record<string, string> | null
  note: string
  showMethod?: boolean
}) {
  const gap = analyticsGap(detail, values !== null, title)

  return (
    <Item title={title}>
      {gap ?? (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col">종목</th>
                <th scope="col">{title}</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(values ?? {}).map(([id, value]) => (
                <tr key={id}>
                  <td>{labels.instrument(id)}</td>
                  <td>{formatRatio(value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <EstimationFacts detail={detail} showMethod={showMethod} />
      <p className={styles.muted}>{note}</p>
    </Item>
  )
}

function TurnoverItem({ detail }: { detail: PublicResultDetail }) {
  const turnover = detail.summary?.analytics?.turnover ?? null

  return (
    <Item
      title="회전율"
      help={
        <InlineHelp label="회전율">
          <p>사고판 금액의 절반을 평균 자산으로 나누고 1년(252거래일) 기준으로 환산한 값입니다.</p>
        </InlineHelp>
      }
    >
      {detail.summary === null ? (
        <p className={styles.copy}>{SUMMARY_NOT_CALCULATED}</p>
      ) : turnover === null ? (
        <p className={styles.limited}>{CALCULATION_LIMITED}: 이 결과에는 회전율이 계산되지 않았습니다.</p>
      ) : (
        <p className={styles.value}>연 {formatRatio(turnover)}</p>
      )}
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

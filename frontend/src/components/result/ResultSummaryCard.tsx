import type { PublicResultDetail } from '@/types/result'
import { buildConditionLine } from './conditionLine'
import { formatMoney, formatRatio, isZeroValue } from './formatResult'
import styles from './ResultSummaryCard.module.css'

export const METRIC_DEFINITIONS = {
  netTotalReturn:
    '수수료·슬리피지 등 거래비용을 뺀 뒤, 시작 금액과 비교해 끝 금액이 달라진 비율입니다.',
  maxDrawdown:
    '운용 중 가장 높았던 자산가치에서 그 뒤 가장 낮았던 때까지 줄어든 비율입니다. 시작 금액 대비 손실과 다릅니다.',
  totalTradingCost: '분석 기간 동안 사고팔며 낸 수수료·스프레드·슬리피지·세금의 합계입니다.',
} as const

type ResultSummaryCardProps = {
  detail: PublicResultDetail
  /** 겹쳐 그리는 비교 대상들. 이름은 부른 쪽이 정한다 — 같은 전략이 둘이면 설정값으로 갈라야 해서다 */
  targets?: readonly { detail: PublicResultDetail; name: string }[]
}

/**
 * 결과 첫 화면의 요약 카드: 핵심 숫자 3개와 각 정의, 조건 한 줄, 종료 시 평가 구분(FR-001~FR-003, FR-015).
 *
 * <p>숫자는 응답 문자열을 자릿수만 다듬어 보인다. <b>요약이 없으면 숫자 자리를 그리지 않는다</b> — 0이나
 * 대체값을 넣으면 없는 결과를 만든다(FR-013, 원칙 VI).
 *
 * <p>숫자와 조건 한 줄은 텍스트라 스크린 리더가 읽는다(FR-022).
 */
export function ResultSummaryCard({ detail, targets = [] }: ResultSummaryCardProps) {
  const condition = buildConditionLine({
    ...detail,
    targetName: targets.length ? targets.map((target) => target.name).join(', ') : null,
    targetWeightsSource: targets.some((target) => target.detail.weightsSource === 'COMPUTED')
      ? 'COMPUTED'
      : targets[0]?.detail.weightsSource,
  })
  const { summary } = detail

  return (
    <section className={styles.card} aria-labelledby="result-summary-title">
      <h2 id="result-summary-title" className={styles.visuallyHidden}>
        결과 요약
      </h2>

      <p className={styles.condition} aria-label="분석 조건">
        <span>{condition.period}</span>
        <span>{condition.comparison}</span>
        <span>{condition.cost}</span>
      </p>
      {condition.weightsNote ? <p className={styles.note}>{condition.weightsNote}</p> : null}

      {summary ? (
        <>
          <dl className={styles.metrics}>
            <Metric
              label="비용 차감 후 전체 수익률"
              value={formatRatio(summary.netTotalReturn)}
              definition={METRIC_DEFINITIONS.netTotalReturn}
            />
            <Metric
              label="가장 크게 줄었던 폭"
              value={formatRatio(summary.maxDrawdown)}
              definition={METRIC_DEFINITIONS.maxDrawdown}
            />
            <Metric
              label="거래비용"
              value={formatMoney(summary.totalTradingCost)}
              definition={METRIC_DEFINITIONS.totalTradingCost}
            />
          </dl>

          {isZeroValue(summary.netTotalReturn) && isZeroValue(summary.maxDrawdown) ? (
            <p className={styles.note}>
              수익률과 가장 크게 줄었던 폭이 모두 0입니다. 기간 동안 자산가치가 달라지지 않았다는
              뜻이며 손실이 없었다는 평가가 아닙니다.
              {summary.tradeCount === 0 ? ' 이 기간에 체결된 거래가 없습니다.' : ''}
            </p>
          ) : null}

          <p className={styles.note}>
            {summary.liquidation
              ? `종료일 시가평가 기준 수익률은 ${formatRatio(summary.liquidation.markToMarket.totalReturn)}이고, 종료일에 모두 판다고 가정하면 청산 비용 ${formatMoney(summary.liquidation.liquidated.liquidationCost)}을 포함해 ${formatRatio(summary.liquidation.liquidated.totalReturn)}입니다.`
              : '끝 금액은 종료일 시가평가 기준이며 종료일에 모두 파는 경우를 가정하지 않았습니다.'}
          </p>
        </>
      ) : null}
    </section>
  )
}

function Metric({
  label,
  value,
  definition,
}: {
  label: string
  value: string
  definition: string
}) {
  return (
    <div className={styles.metric}>
      <dt className={styles.metricLabel}>{label}</dt>
      <dd className={styles.metricValue}>{value}</dd>
      <dd className={styles.metricDefinition}>{definition}</dd>
    </div>
  )
}

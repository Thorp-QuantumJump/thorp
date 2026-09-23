import type { ReactNode } from 'react'
import { InlineHelp } from '@/components/common/InlineHelp'
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

/** 화면에 보이는 짧은 이름. 긴 이름(FR-001의 표기)은 도움말 제목으로 남는다 */
export const METRIC_LABELS = {
  netTotalReturn: { short: '수익률', full: '비용 차감 후 전체 수익률' },
  maxDrawdown: { short: '최대 하락 폭', full: '가장 크게 줄었던 폭' },
  totalTradingCost: { short: '거래비용', full: '거래비용' },
} as const

type Props = {
  detail: PublicResultDetail
  target?: PublicResultDetail | null
}

/**
 * 조건 한 줄(FR-003). 분석 기간·비교 대상·비용 적용 여부를 한 묶음으로, 어느 폭에서도 생략하지 않는다.
 * 제목 바로 아래에 둔다 — 그래프와 숫자를 읽기 전에 무엇의 결과인지 안다.
 */
export function ConditionLine({ detail, target }: Props) {
  const condition = buildConditionLine({
    ...detail,
    targetName: target?.strategyName ?? target?.config.strategyId,
    targetWeightsSource: target?.weightsSource,
  })
  return (
    <div className={styles.conditionBlock}>
      <p className={styles.condition} aria-label="분석 조건">
        <span>{condition.period}</span>
        <span>{condition.comparison}</span>
        <span>{condition.cost}</span>
      </p>
      {condition.weightsNote ? <p className={styles.note}>{condition.weightsNote}</p> : null}
    </div>
  )
}

/**
 * 핵심 숫자 3개(FR-001·FR-002·FR-015, T036·T098). 그래프 옆 칸에 세로로 놓인다.
 *
 * <p>숫자는 응답 문자열을 자릿수만 다듬어 보인다. <b>요약이 없으면 숫자 자리를 그리지 않는다</b> — 0이나
 * 대체값을 넣으면 없는 결과를 만든다(FR-013, 원칙 VI).
 *
 * <p>각 숫자의 정의와 종료 시 평가 구분(시가평가/청산)은 이름 옆 물음표 도움말로 내렸다. 본문에 늘 펼쳐
 * 두면 옆 칸이 설명으로 차서 숫자가 묻힌다(원칙 II). 0·0 결과의 주의 문장만은 숫자가 좋은 결과로 읽히는
 * 것을 막아야 하므로 그대로 보인다. 숫자와 이름은 텍스트라 스크린 리더가 읽는다(FR-022).
 */
export function ResultSummaryCard({ detail }: Props) {
  const { summary } = detail
  if (!summary) return null

  const liquidationNote = summary.liquidation
    ? `종료일 시가평가 기준 수익률은 ${formatRatio(summary.liquidation.markToMarket.totalReturn)}이고, 종료일에 모두 판다고 가정하면 청산 비용 ${formatMoney(summary.liquidation.liquidated.liquidationCost)}을 포함해 ${formatRatio(summary.liquidation.liquidated.totalReturn)}입니다.`
    : '끝 금액은 종료일 시가평가 기준이며 종료일에 모두 파는 경우를 가정하지 않았습니다.'

  return (
    <section className={styles.card} aria-labelledby="result-summary-title">
      <h2 id="result-summary-title" className={styles.visuallyHidden}>
        결과 요약
      </h2>

      <dl className={styles.metrics}>
        <Metric
          label={METRIC_LABELS.netTotalReturn}
          value={formatRatio(summary.netTotalReturn)}
          help={
            <>
              <p>{METRIC_DEFINITIONS.netTotalReturn}</p>
              <p>{liquidationNote}</p>
            </>
          }
        />
        <Metric
          label={METRIC_LABELS.maxDrawdown}
          value={formatRatio(summary.maxDrawdown)}
          help={<p>{METRIC_DEFINITIONS.maxDrawdown}</p>}
        />
        <Metric
          label={METRIC_LABELS.totalTradingCost}
          value={formatMoney(summary.totalTradingCost)}
          help={<p>{METRIC_DEFINITIONS.totalTradingCost}</p>}
        />
      </dl>

      {isZeroValue(summary.netTotalReturn) && isZeroValue(summary.maxDrawdown) ? (
        <p className={styles.note}>
          수익률과 최대 하락 폭이 모두 0입니다. 기간 동안 자산가치가 달라지지 않았다는 뜻이며 손실이
          없었다는 평가가 아닙니다.
          {summary.tradeCount === 0 ? ' 이 기간에 체결된 거래가 없습니다.' : ''}
        </p>
      ) : null}
    </section>
  )
}

function Metric({
  label,
  value,
  help,
}: {
  label: { short: string; full: string }
  value: string
  help: ReactNode
}) {
  return (
    <div className={styles.metric}>
      <dt className={styles.metricLabel}>
        <span>{label.short}</span>
        <InlineHelp label={label.full}>{help}</InlineHelp>
      </dt>
      <dd className={styles.metricValue}>{value}</dd>
    </div>
  )
}

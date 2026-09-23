import { Database, Info } from 'lucide-react'
import type { DataFreshnessItem, Indicator } from '@/types/market'
import {
  formatDataDate,
  formatFrequency,
  formatIndicatorChange,
  formatIndicatorValue,
} from '@/utils/marketFormat'
import styles from './DataFreshnessPanel.module.css'

interface DataFreshnessPanelProps {
  sources: DataFreshnessItem[]
  stabilityIndicators: Indicator[]
}

const statusLabels: Record<DataFreshnessItem['status'], string> = {
  example: '예시 데이터',
  'api-ready': 'API 후보 확인',
  'review-needed': '연동 검토 필요',
}

/** 저빈도 금융안정 지표와 향후 데이터 공급처를 핵심 화면에서 분리해 제공한다. */
export function DataFreshnessPanel({
  sources,
  stabilityIndicators,
}: DataFreshnessPanelProps) {
  return (
    <details className={styles.details}>
      <summary>
        <span>
          <Database size={15} aria-hidden="true" />
          데이터 출처와 금융안정 참고지표
        </span>
        <small>지표별 발표 주기와 연동 준비 상태 보기</small>
      </summary>

      <div className={styles.content}>
        <div className={styles.sourceGrid}>
          {sources.map((source) => (
            <article className={styles.sourceCard} key={source.id}>
              <div>
                <strong>{source.provider}</strong>
                <span data-status={source.status}>{statusLabels[source.status]}</span>
              </div>
              <p>{source.coverage}</p>
              <small>
                {formatFrequency(source.frequency)} · {source.note}
              </small>
            </article>
          ))}
        </div>

        <div className={styles.stabilityGrid}>
          {stabilityIndicators.map((indicator) => (
            <article className={styles.stabilityCard} key={indicator.id}>
              <div>
                <span>{indicator.name}</span>
                <small>{formatDataDate(indicator.observationDate)} 기준</small>
              </div>
              <strong>{formatIndicatorValue(indicator)}</strong>
              <p data-tone={indicator.tone}>{formatIndicatorChange(indicator)}</p>
              <small>{indicator.interpretation}</small>
            </article>
          ))}
          <div className={styles.fciNote}>
            <Info size={16} aria-hidden="true" />
            <p>
              FCI는 공식 정기 API가 확정되기 전까지 표시하지 않습니다. 자체 계산 시에는
              금리·신용스프레드·환율·변동성을 사용한 내부 추정 지표임을 명시합니다.
            </p>
          </div>
        </div>
      </div>
    </details>
  )
}

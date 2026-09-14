import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { ValidationBadge } from './ValidationBadge'
import type {
  ParamSearchConfig,
  SearchCandidateItem,
  SelectionRule,
} from '@/types/paramSearch'
import {
  candidateStatusText,
  describeCandidateSetting,
  formatCount,
  formatPercent,
  formatStability,
  formatWon,
  missingPerformanceReason,
  selectionFilterText,
  selectionOutcomeText,
  sortMetricText,
} from '@/utils/paramSearchLabels'
import styles from './CandidateDetail.module.css'

interface CandidateDetailProps {
  candidate: SearchCandidateItem
  config: ParamSearchConfig | null
  selectionRule: SelectionRule | null
  onClose: () => void
}

/**
 * 후보 하나의 상세(FR-018).
 *
 * <p>첫 화면에 두지 않은 것을 여기 둔다 — <b>인접 설정 안정성·선정 이유·탈락 사유</b>. 첫 화면은
 * 세 숫자로 좁히고 판단 근거는 이 층에서 본다.
 *
 * <p><b>탈락과 "표시하지 않음"을 다른 문장으로 적는다.</b> 기준을 통과하고도 표시 범위 밖이었던
 * 후보를 "탈락"이라 쓰면 사실이 아니다.
 *
 * <p>안정성 '계산 불가'는 나쁜 값이 아니라 <b>말할 수 없는 값</b>이다 — 0으로 채우지 않는다(FR-013b).
 */
export function CandidateDetail({
  candidate,
  config,
  selectionRule,
  onClose,
}: CandidateDetailProps) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const { strategyName, parameterText } = describeCandidateSetting(candidate, config)
  const stability = formatStability(candidate.stability)
  const reason = candidate.selectionReason
  const netReturn = formatPercent(candidate.netReturn, { sign: true })
  const maxDrawdown = formatPercent(candidate.maxDrawdown)
  const tradingCost = formatWon(candidate.tradingCost)

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
      event.preventDefault()
      onClose()
    }

    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [onClose])

  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  return (
    <div className={styles.overlay}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="candidate-detail-title"
      >
        <header className={styles.header}>
          <div>
            <h2 id="candidate-detail-title">{strategyName}</h2>
            <p>{parameterText}</p>
          </div>
          <button
            type="button"
            ref={closeRef}
            className={styles.close}
            onClick={onClose}
            aria-label="상세 닫기"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </header>

        <div className={styles.badges}>
          <span className={styles.status}>{candidateStatusText(candidate.status)}</span>
          <ValidationBadge label={candidate.validationLabel} describes={strategyName} />
          {candidate.retryCount > 0 && (
            <span className={styles.retry}>{`재시도 ${formatCount(candidate.retryCount)}회`}</span>
          )}
        </div>

        <dl className={styles.rows}>
          <div>
            <dt>비용 차감 후 수익</dt>
            <dd>{netReturn ?? <span className={styles.absent}>계산하지 않았습니다</span>}</dd>
          </div>
          <div>
            <dt>가장 크게 줄었던 폭</dt>
            <dd>{maxDrawdown ?? <span className={styles.absent}>계산하지 않았습니다</span>}</dd>
          </div>
          <div>
            <dt>거래비용</dt>
            <dd>{tradingCost ?? <span className={styles.absent}>계산하지 않았습니다</span>}</dd>
          </div>
          <div>
            <dt>인접 설정 안정성</dt>
            <dd>
              {stability.text ?? <span className={styles.absent}>계산 불가</span>}
              <span className={styles.rowNote}>
                {stability.text
                  ? `${stability.note} · 작을수록 안정입니다`
                  : stability.note}
              </span>
            </dd>
          </div>
        </dl>

        {netReturn === null && maxDrawdown === null && tradingCost === null && (
          <p className={styles.missing}>{missingPerformanceReason(candidate)}</p>
        )}

        <section className={styles.reason} aria-labelledby="candidate-reason-title">
          <h3 id="candidate-reason-title">선정 판단</h3>
          {reason === null ? (
            <p className={styles.reasonText}>아직 선정을 계산하지 않았습니다.</p>
          ) : (
            <>
              <p className={styles.outcome}>{selectionOutcomeText(reason.outcome)}</p>

              {reason.failedFilter && (
                <p className={styles.reasonText}>
                  {`${selectionFilterText(reason.failedFilter)}에서 걸렀습니다.`}
                </p>
              )}
              {reason.message && <p className={styles.reasonText}>{reason.message}</p>}
              {reason.code && !reason.message && (
                <p className={styles.reasonText}>{reason.code}</p>
              )}

              {reason.passedFilters && reason.passedFilters.length > 0 && (
                <p className={styles.reasonText}>
                  {`통과한 기준: ${reason.passedFilters.map(selectionFilterText).join(' · ')}`}
                </p>
              )}
              {reason.passedFilters && reason.passedFilters.length === 0 && (
                <p className={styles.reasonText}>통과한 기준이 없습니다.</p>
              )}

              {reason.tied && (
                <p className={styles.reasonText}>
                  {`${sortMetricText(selectionRule?.sortMetric ?? 'NET_RETURN')} 값이 같은 후보가 있어 함께 보입니다. 임의로 자르지 않습니다.`}
                </p>
              )}
            </>
          )}
        </section>

        {candidate.backtestId && (
          <p className={styles.runId}>
            {'실행 식별자 '}
            <code>{candidate.backtestId}</code>
          </p>
        )}
      </div>
    </div>
  )
}

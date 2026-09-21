import { useEffect, useState } from 'react'
import { ParamSearchError, getParamSearch } from '@/api/paramSearch'
import { useSearchCandidates } from '@/hooks/useSearchCandidates'
import type { MyRecordStatus } from '@/types/myRecords'
import type { ParamSearchDetail, SelectionRule } from '@/types/paramSearch'
import {
  candidateStatusText,
  describeCandidateSetting,
  formatCount,
  missingPerformanceReason,
  selectionFilterText,
  sortMetricText,
} from '@/utils/paramSearchLabels'
import styles from './records.module.css'

type State =
  | { status: 'loading' }
  | { status: 'loaded'; detail: ParamSearchDetail }
  | { status: 'failed'; message: string }

/** 목록 한 쪽에 보이는 후보 수. 더 보려면 '다시 보기'로 007 화면을 연다 — 여기서 표를 다시 만들지 않는다. */
const CANDIDATE_PAGE_SIZE = 20

/**
 * 탐색 묶음 항목의 펼쳐 보는 층(spec 006 T038, FR-011, quickstart S7-2·S7-3).
 *
 * <p>값은 전부 007이 저장한 것을 그대로 읽는다(`GET /param-searches/{id}`·`/candidates`). <b>후보를 성과순으로
 * 재정렬하는 컨트롤이 없다</b>(원칙 III) — 순번 차례 그대로 보이고, 성과를 나란히 견주는 일은 007 화면의 몫이다.
 * 전략 이름·축 라벨은 `config.space`에서만 읽고 코드에 두지 않는다(007 FR-025).
 */
export function SearchBundlePanel({ searchId, status }: { searchId: string; status: MyRecordStatus }) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const candidates = useSearchCandidates(searchId, { size: CANDIDATE_PAGE_SIZE })

  useEffect(() => {
    let active = true
    setState({ status: 'loading' })
    getParamSearch(searchId).then(
      (detail) => {
        if (active) setState({ status: 'loaded', detail })
      },
      (error: unknown) => {
        if (!active) return
        setState({
          status: 'failed',
          message:
            error instanceof ParamSearchError
              ? error.message
              : '탐색 묶음 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
        })
      },
    )
    return () => {
      active = false
    }
  }, [searchId])

  if (state.status === 'loading') {
    return (
      <p role="status" className={styles.hint}>
        탐색 묶음 정보를 불러오는 중입니다.
      </p>
    )
  }
  if (state.status === 'failed') {
    return (
      <p role="alert" className={styles.hint}>
        {state.message}
      </p>
    )
  }

  const { detail } = state
  const counts = detail.counts
  const config = detail.config

  return (
    <>
      {status === 'PARTIAL' && counts ? (
        <p role="status" className={styles.notice}>
          부분 완료 — {formatCount(counts.completed)}/{formatCount(counts.expected)} 후보
          {detail.partialNote ? ` · ${detail.partialNote}` : null}
        </p>
      ) : null}

      <dl className={styles.facts}>
        {config ? (
          <>
            <dt>탐색 공간</dt>
            <dd>
              <dl className={styles.versions}>
                {Object.values(config.space).map((strategy) => (
                  <div key={strategy.strategyName}>
                    <dt>{strategy.strategyName}</dt>
                    <dd>
                      {strategy.axes.length === 0
                        ? '설정 축 없음'
                        : strategy.axes
                            .map(
                              (axis) =>
                                `${axis.label}: ${axis.values.join(', ')}${axis.unit ? ` ${axis.unit}` : ''}`,
                            )
                            .join(' · ')}
                    </dd>
                  </div>
                ))}
              </dl>
            </dd>
          </>
        ) : null}
        {counts ? (
          <>
            <dt>후보 수</dt>
            <dd>
              예상 {formatCount(counts.expected)} · 유효 {formatCount(counts.valid)} · 완료{' '}
              {formatCount(counts.completed)} · 실패 {formatCount(counts.failed)} · 제외{' '}
              {formatCount(counts.excluded)} · 미실행 {formatCount(counts.skipped)}
            </dd>
          </>
        ) : null}
        {config?.selectionRule ? (
          <>
            <dt>선정 규칙</dt>
            <dd>{describeSelectionRule(config.selectionRule)}</dd>
          </>
        ) : null}
      </dl>

      {candidates.isLoading && !candidates.page ? (
        <p role="status" className={styles.hint}>
          후보 목록을 불러오는 중입니다.
        </p>
      ) : null}
      {candidates.error ? (
        <p role="alert" className={styles.hint}>
          {candidates.error}
        </p>
      ) : null}
      {candidates.page ? (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <caption>
              후보 {formatCount(candidates.page.totalElements)}개 중 {formatCount(candidates.page.content.length)}개 ·
              순번 차례
            </caption>
            <thead>
              <tr>
                <th scope="col">순번</th>
                <th scope="col">설정</th>
                <th scope="col">상태</th>
                <th scope="col">사유</th>
              </tr>
            </thead>
            <tbody>
              {candidates.page.content.map((candidate) => {
                const setting = describeCandidateSetting(candidate, config)
                return (
                  <tr key={candidate.candidateId}>
                    <td>{candidate.candidateNo}</td>
                    <td>
                      {setting.strategyName} · {setting.parameterText}
                    </td>
                    <td>{candidateStatusText(candidate.status)}</td>
                    <td>{candidate.status === 'COMPLETED' ? '' : missingPerformanceReason(candidate)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  )
}

/** 007 화면과 같은 말로 규칙을 한 줄에 적는다. 규칙은 묶음이 시작될 때 굳은 값이다(007 FR-013c). */
function describeSelectionRule(rule: SelectionRule): string {
  const filters: string[] = []
  if (rule.drawdownFilter) filters.push(selectionFilterText('DRAWDOWN'))
  if (rule.stabilityFilter) filters.push(selectionFilterText('STABILITY'))
  const filterText = filters.length > 0 ? `${filters.join('·')} 통과 후 ` : ''
  return `${filterText}${sortMetricText(rule.sortMetric)} 기준 ${formatCount(rule.displayCount)}개 표시`
}

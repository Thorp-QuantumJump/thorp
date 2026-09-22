import { useState } from 'react'
import { InlineHelp } from '@/components/common/InlineHelp'
import { useSearchCandidates } from '@/hooks/useSearchCandidates'
import type {
  CandidateSort,
  CandidateStatus,
  ParamSearchConfig,
  SearchCandidateItem,
} from '@/types/paramSearch'
import {
  candidateStatusText,
  describeCandidateSetting,
  formatCount,
  formatPercent,
  formatStability,
  formatWon,
  missingPerformanceReason,
  selectionOutcomeText,
} from '@/utils/paramSearchLabels'
import styles from './CandidateTable.module.css'

interface CandidateTableProps {
  searchId: string
  /** 축 라벨·전략 이름을 읽을 곳. */
  config: ParamSearchConfig | null
  onOpenDetail: (candidate: SearchCandidateItem) => void
  comparePicks: string[]
  onCompareToggle: (candidate: SearchCandidateItem) => void
}

/** 정렬할 수 있는 열. 서버가 허용하는 것과 같고, 여기에 없는 열은 정렬 단추를 주지 않는다. */
const sortableColumns: { key: CandidateSort; label: string; numeric: boolean }[] = [
  { key: 'candidateNo', label: '순번', numeric: true },
  { key: 'netReturn', label: '비용 차감 후 수익', numeric: true },
  { key: 'maxDrawdown', label: '가장 크게 줄었던 폭', numeric: true },
  { key: 'tradingCost', label: '거래비용', numeric: true },
  { key: 'stability', label: '인접 설정 안정성', numeric: true },
]

const statusOptions: { value: '' | CandidateStatus; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'FAILED', label: '실패' },
  { value: 'EXCLUDED', label: '계산 전 제외' },
  { value: 'SKIPPED', label: '미실행' },
  { value: 'PENDING', label: '대기' },
  { value: 'DISPATCHED', label: '계산 중' },
]

const pageSize = 20

/**
 * 전체 후보 목록(FR-018 상세, FR-017).
 *
 * <p><b>한 항목 기준 정렬은 FR-017이 허용한다.</b> 다만 정렬은 보기 편하라고 있는 것이고 순위를
 * 만들지 않는다 — 그래서 순위 번호 열을 두지 않고, 정렬 기준을 바꿀 수 있다는 사실을 화면에 적는다.
 * `순번`은 후보가 만들어진 차례이지 성적이 아니다.
 *
 * <p>정렬·필터는 서버가 한다. 화면에서 다시 정렬하면 페이지 밖의 후보가 빠진 채 정렬돼 사실과
 * 어긋난다.
 *
 * <p>성과가 없는 후보의 칸은 <b>0이 아니라 사유</b>다(FR-011).
 */
export function CandidateTable({
  searchId,
  config,
  onOpenDetail,
  comparePicks,
  onCompareToggle,
}: CandidateTableProps) {
  const [sort, setSort] = useState<CandidateSort>('candidateNo')
  const [direction, setDirection] = useState<'asc' | 'desc'>('asc')
  const [status, setStatus] = useState<'' | CandidateStatus>('')
  const [page, setPage] = useState(0)

  const { page: result, error, isLoading } = useSearchCandidates(searchId, {
    page,
    size: pageSize,
    sort,
    direction,
    ...(status ? { status } : {}),
  })

  const changeSort = (key: CandidateSort) => {
    if (key === sort) {
      setDirection((current) => (current === 'asc' ? 'desc' : 'asc'))
    } else {
      setSort(key)
      // 순번은 만들어진 차례라 오름차순이 자연스럽고, 지표는 큰 값부터 보는 편이 낫다.
      setDirection(key === 'candidateNo' ? 'asc' : 'desc')
    }
    setPage(0)
  }

  const ariaSortOf = (key: CandidateSort) =>
    key === sort ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'

  return (
    <section className={styles.section} aria-labelledby="candidate-table-title">
      <header className={styles.header}>
        <div className={styles.headline}>
          <h2 id="candidate-table-title">전체 후보</h2>
          <InlineHelp label="전체 후보">
            <p>열 제목을 눌러 한 항목 기준으로 정렬할 수 있습니다.</p>
            <p>정렬은 보기 순서일 뿐 순위가 아닙니다.</p>
          </InlineHelp>
        </div>
        <div className={styles.filter}>
          <label htmlFor="candidate-status">상태</label>
          <select
            id="candidate-status"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as '' | CandidateStatus)
              setPage(0)
            }}
          >
            {statusOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </header>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      {isLoading && !result && (
        <p className={styles.note} role="status">
          후보 목록을 불러오는 중입니다.
        </p>
      )}

      {result && result.content.length === 0 && (
        <p className={styles.note}>조건에 맞는 후보가 없습니다.</p>
      )}

      {result && result.content.length > 0 && (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col" className={styles.pickColumn}>
                  <span className={styles.srOnly}>비교에 담기</span>
                </th>
                <th scope="col">설정</th>
                <th scope="col">상태</th>
                {sortableColumns.map((column) => (
                  <th key={column.key} scope="col" aria-sort={ariaSortOf(column.key)}>
                    <button
                      type="button"
                      className={styles.sortButton}
                      data-active={column.key === sort}
                      onClick={() => changeSort(column.key)}
                    >
                      {column.label}
                      <span aria-hidden="true">
                        {column.key === sort ? (direction === 'asc' ? '▲' : '▼') : '↕'}
                      </span>
                      <span className={styles.srOnly}>
                        {column.key === sort
                          ? direction === 'asc'
                            ? ' 오름차순 정렬됨, 눌러서 내림차순'
                            : ' 내림차순 정렬됨, 눌러서 오름차순'
                          : ' 이 항목으로 정렬'}
                      </span>
                    </button>
                  </th>
                ))}
                <th scope="col">선정</th>
                <th scope="col">
                  <span className={styles.srOnly}>상세</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {result.content.map((candidate) => {
                const { strategyName, parameterText } = describeCandidateSetting(
                  candidate,
                  config,
                )
                const stability = formatStability(candidate.stability)
                const reason = missingPerformanceReason(candidate)
                const pickId = `table-compare-${candidate.candidateId}`
                const comparable = candidate.status === 'COMPLETED'

                return (
                  <tr key={candidate.candidateId}>
                    <td>
                      <input
                        id={pickId}
                        type="checkbox"
                        checked={comparePicks.includes(candidate.candidateId)}
                        onChange={() => onCompareToggle(candidate)}
                        disabled={!comparable}
                      />
                      <label htmlFor={pickId} className={styles.srOnly}>
                        {comparable
                          ? `${strategyName} ${parameterText} 비교에 담기`
                          : `${strategyName} ${parameterText} 결과가 없어 비교에 담을 수 없습니다`}
                      </label>
                    </td>
                    <td>
                      <span className={styles.strategy}>{strategyName}</span>
                      <span className={styles.parameters}>{parameterText}</span>
                    </td>
                    <td>{candidateStatusText(candidate.status)}</td>
                    <td className={styles.numeric}>{formatCount(candidate.candidateNo)}</td>
                    <Numeric
                      value={formatPercent(candidate.netReturn, { sign: true })}
                      reason={reason}
                    />
                    <Numeric value={formatPercent(candidate.maxDrawdown)} reason={reason} />
                    <Numeric value={formatWon(candidate.tradingCost)} reason={reason} />
                    <Numeric value={stability.text} reason={stability.note} />
                    <td>
                      {candidate.selectionReason
                        ? selectionOutcomeText(candidate.selectionReason.outcome)
                        : '계산 전'}
                    </td>
                    <td>
                      <button
                        type="button"
                        className={styles.detailButton}
                        onClick={() => onOpenDetail(candidate)}
                      >
                        상세
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {result && result.totalPages > 1 && (
        <nav className={styles.pager} aria-label="후보 목록 페이지">
          <button type="button" onClick={() => setPage((n) => n - 1)} disabled={page <= 0}>
            이전
          </button>
          <span>{`${formatCount(result.page + 1)} / ${formatCount(result.totalPages)}`}</span>
          <button
            type="button"
            onClick={() => setPage((n) => n + 1)}
            disabled={page >= result.totalPages - 1}
          >
            다음
          </button>
        </nav>
      )}

      {result && (
        <p className={styles.note}>{`후보 ${formatCount(result.totalElements)}개`}</p>
      )}
    </section>
  )
}

/** 숫자 칸. **값이 없으면 0이 아니라 사유를 적는다**(FR-011). */
function Numeric({ value, reason }: { value: string | null; reason: string }) {
  return (
    <td className={styles.numeric}>
      {value ?? <span className={styles.absent}>{reason}</span>}
    </td>
  )
}

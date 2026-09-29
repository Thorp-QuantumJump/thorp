import { ArrowUpRight, Check, History } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getRegimeBaselineMonths, getRegimeSearch } from '@/api/regime'
import { InlineHelp } from '@/components/common/InlineHelp'
import { Disclosure } from '@/components/regime/Disclosure'
import { EmptyResult } from '@/components/regime/EmptyResult'
import { buildCaseId, buildRegimeLinkStartPath, parseCaseId } from '@/marketflow/handoff'
import type { RegimeBaselineMonths, RegimeSearchQuery, RegimeSearchResult } from '@/types/regime'
import styles from './SimilarPastMarket.module.css'

/*
  투자 대상 고르기의 '지금과 비슷했던 과거 시장'(002 FR-018). Thorp의 핵심 흐름 — 섹터를 고르고, 지금과
  비슷했던 과거 시장 기간을 찾아, 그 기간으로 백테스트한다 — 을 한 화면에서 잇는다.

  - 국내 시장 참고 정보(KOSPI·VKOSPI, 009 FR-017)는 여기가 아니라 오른쪽 '다음: 전략 고르기' 아래에 있다.
  - 처음에는 첫 분야(표시 순서)의 최신 기준월로 가까운 과거 사례 3개를 보인다. 종합 비교는 누를 때만 연다
    (010 US3, 011 FR-001).
  - 사례의 순서·문구는 010이 내려 준 것만 쓴다. 순위 번호·'추천' 같은 말을 화면이 만들지 않는다(010 FR-022).
  - 사례를 고르면 011 진입 계약(`/regime-link/start?case=`)으로 들어가 그 기간이 분석 기간이 된다.
  - 이 영역을 받지 못해도 투자 대상 고르기는 막히지 않는다. 그래서 실패는 경고(alert)가 아니라 조용한 안내와
    다시 시도로 알린다 — 곁들이는 영역이 화면의 주 경고와 겹치면 무엇이 막혔는지 흐려진다.
*/

function QuietFailure({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className={styles.failure} role="status">
      <p>{message}</p>
      <button type="button" onClick={onRetry}>다시 시도</button>
    </div>
  )
}

type Search = { status: 'loading' } | { status: 'failed' } | { status: 'ready'; result: RegimeSearchResult }

/** 관측 구간을 처음 달~끝 달로 줄여 적는다. */
function observationRange(months: string[]) {
  if (months.length === 0) return null
  const first = months[0]
  const last = months[months.length - 1]
  return first === last ? first : `${first} ~ ${last}`
}

/**
 * '비슷했던 때 보기'로 가는 주소. 종합 비교를 보던 중이면 종합 비교가 선택된 상태로 연다(S15P21E205-450).
 * 분야 검색은 저장된 판(searchId)을 그대로 연다 — 카드와 같은 결과다.
 */
function regimeSearchPath(result: RegimeSearchResult): string {
  if (result.mode === 'COMPOSITE') return '/regime-search?mode=composite'
  return `/regime-search?searchId=${encodeURIComponent(result.searchId)}`
}

export function SimilarPastMarket({ caseId }: { caseId: string | null }) {
  const [baselines, setBaselines] = useState<RegimeBaselineMonths | null>(null)
  const [baselinesFailed, setBaselinesFailed] = useState(false)
  const [query, setQuery] = useState<RegimeSearchQuery | null>(null)
  const [search, setSearch] = useState<Search>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const retry = useCallback(() => setAttempt((count) => count + 1), [])
  const chosen = parseCaseId(caseId)

  useEffect(() => {
    const controller = new AbortController()
    setBaselinesFailed(false)
    getRegimeBaselineMonths(controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return
        setBaselines(next)
        // 첫 분야의 최신 기준월로 먼저 연다. 빈 칸보다 하나라도 보여야 이 자리가 무엇인지 읽힌다.
        setQuery((current) => current ?? (next.byField[0] ? { mode: 'field', field: next.byField[0].id, limit: 3 } : null))
      })
      .catch(() => {
        if (!controller.signal.aborted) setBaselinesFailed(true)
      })
    return () => controller.abort()
  }, [attempt])

  useEffect(() => {
    if (!query) return
    const controller = new AbortController()
    setSearch({ status: 'loading' })
    getRegimeSearch(query, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setSearch({ status: 'ready', result })
      })
      .catch(() => {
        if (!controller.signal.aborted) setSearch({ status: 'failed' })
      })
    return () => controller.abort()
  }, [query, attempt])

  const result = search.status === 'ready' ? search.result : null
  const context = result ? `${result.field?.name ?? '종합'} ${result.baselineMonth} 검색 결과` : ''

  return (
    <section className={styles.panel} aria-labelledby="similar-past-title">
      {/* 제목과 기준월을 한 줄에 둔다. 기준월은 제목에 딸린 값이라 같은 줄 오른쪽 끝에 붙인다. */}
      <div className={styles.head}>
        {/*
          검색 상태와 사례 구분은 제목 바로 오른쪽 i 표시 뒤로 내린다. 가리키거나 키보드 초점을 주면 펼쳐진다.
          결과를 곁들여 보이는 요약 카드라서이고, 같은 결과의 '비슷했던 때 보기' 화면에서는 늘 펼쳐 둔다(010 FR-037).
        */}
        <div className={styles.titleGroup}>
          <h2 id="similar-past-title">
            <History size={16} aria-hidden="true" />
            지금과 비슷했던 과거 시장
          </h2>
          {result ? (
            <InlineHelp label={`검색 상태${result.caseLabel ? '와 사례 구분' : ''}`} mark="i" side="bottom">
              <p><b>검색 상태</b> {result.statusNote}</p>
              {result.caseLabel ? <p><b>사례 구분</b> {result.caseLabel}</p> : null}
            </InlineHelp>
          ) : null}
        </div>
        {result ? <p className={styles.basis}>기준월 {result.baselineMonth}</p> : null}
      </div>

      {chosen ? (
        <p className={styles.chosen} role="status">
          <Check size={15} aria-hidden="true" />
          <span>
            {chosen.endMonth} 사례의 기간으로 분석을 이어 갑니다. 다음 단계의 분석 기간이 그 기간에 맞춰집니다.
          </span>
          <Link to="/diagnosis/sectors">사례 해제</Link>
        </p>
      ) : null}

      {baselinesFailed ? (
        <QuietFailure message="경제 분야를 불러오지 못했습니다." onRetry={retry} />
      ) : baselines ? (
        <div className={styles.fields} role="group" aria-label="경제 분야">
          {baselines.byField.map((field) => (
            <button
              key={field.id}
              type="button"
              className={styles.field}
              aria-pressed={query?.mode === 'field' && query.field === field.id}
              onClick={() => setQuery({ mode: 'field', field: field.id, limit: 3 })}
            >
              {field.name}
            </button>
          ))}
          {/* 종합은 누를 때만 연다(010 US3). 분야와 다른 무게로 보이게 끝에 떼어 둔다. */}
          <button
            type="button"
            className={styles.composite}
            aria-pressed={query?.mode === 'composite'}
            disabled={!baselines.composite}
            onClick={() => setQuery({ mode: 'composite', limit: 3 })}
          >
            Thorp 종합 비교
          </button>
        </div>
      ) : (
        <p className={styles.loading} role="status">경제 분야를 불러오는 중입니다.</p>
      )}

      {query ? (
        search.status === 'loading' ? (
          <p className={styles.loading} role="status">과거 사례를 불러오는 중입니다.</p>
        ) : search.status === 'failed' ? (
          <QuietFailure message="과거 사례를 불러오지 못했습니다." onRetry={retry} />
        ) : result ? (
          <div className={styles.result}>
            {/* 제목 옆 i 표시를 펼치지 않아도 보조기기에 읽히도록 같은 문장을 알림 영역에 남긴다(010 FR-037). */}
            <p
              className="sr-only"
              role="status"
              aria-live="polite"
              aria-label={`${context}: 검색 상태${result.caseLabel ? '와 사례 구분' : ''}`}
            >
              검색 상태: {result.statusNote}
              {result.caseLabel ? ` · 사례 구분: ${result.caseLabel}` : null}
            </p>
            {result.status !== 'NOT_COMPUTABLE' && result.cases.length === 0 && result.thresholdNote ? (
              // 교정된 기준을 넘는 사례가 없다(spec US3 시나리오 4). 참고 사례는 검색 화면에서 따로 연다.
              <Disclosure label="유사성 기준" context={context} tone="warning">
                <p>{result.thresholdNote}</p>
                <p>
                  {baselines?.byField[0] && result.mode === 'COMPOSITE' ? (
                    <button type="button" className={styles.detail} onClick={() => setQuery({ mode: 'field', field: baselines.byField[0].id, limit: 3 })}>
                      분야별로 살펴보기
                    </button>
                  ) : null}{' '}
                  <Link className={styles.detail} to={regimeSearchPath(result)}>
                    참고 사례 보기 <ArrowUpRight size={14} aria-hidden="true" />
                  </Link>
                </p>
              </Disclosure>
            ) : result.status === 'NOT_COMPUTABLE' || result.cases.length === 0 ? (
              <EmptyResult result={result} />
            ) : (
              <ol className={styles.cases} aria-label="가까운 과거 사례">
                {result.cases.map((item) => {
                  const itemCaseId = buildCaseId(result.searchId, item.endMonth)
                  const isChosen = chosen?.endMonth === item.endMonth && caseId === itemCaseId
                  return (
                    <li key={item.endMonth} className={styles.case} data-chosen={isChosen || undefined}>
                      <div className={styles.caseBody}>
                        <p className={styles.caseRange}>
                          <span>관측 구간</span>
                          <strong>{observationRange(item.observationMonths) ?? item.endMonth}</strong>
                        </p>
                        {item.similar[0] ? (
                          <p className={styles.reason}>
                            <b className={styles.similarLabel}>비슷한 점</b>
                            <span className={styles.similarText}>{item.similar[0]}</span>
                          </p>
                        ) : null}
                        {item.different[0] ? (
                          <p className={styles.reason}>
                            <b className={styles.differentLabel}>다른 점</b>
                            <span className={styles.differentText}>{item.different[0]}</span>
                          </p>
                        ) : null}
                      </div>
                      <div className={styles.caseActions}>
                        {itemCaseId === null ? null : isChosen ? (
                          <span className={styles.chosenTag}>
                            <Check size={14} aria-hidden="true" /> 고른 기간
                          </span>
                        ) : (
                          <Link className={styles.pick} to={buildRegimeLinkStartPath({ caseId: itemCaseId })}>
                            이 기간으로 백테스트
                          </Link>
                        )}
                        <Link className={styles.detailButton} to={regimeSearchPath(result)}>
                          자세히
                        </Link>
                      </div>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
        ) : null
      ) : null}
    </section>
  )
}

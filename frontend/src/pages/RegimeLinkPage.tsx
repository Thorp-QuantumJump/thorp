import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getRegimeBaselineMonths } from '@/api/regime'
import { fetchResult } from '@/api/result'
import {
  createRegimeLinkSearch,
  createRegimeLinks,
  fetchAvailability,
  listRegimeLinks,
  retryRegimeLink,
  RegimeLinkError,
} from '@/api/regimeLink'
import { useAuth } from '@/auth/auth-context'
import { BundleChangeNotice } from '@/components/regimelink/BundleChangeNotice'
import { CaseStrategyResults } from '@/components/regimelink/CaseStrategyResults'
import { FieldPicker, type FieldChoice } from '@/components/regimelink/FieldPicker'
import { FullPeriodConfirm } from '@/components/regimelink/FullPeriodConfirm'
import {
  baseRunFailedNote,
  baseRunPendingTitle,
  baseTokenMissingNote,
  emptyFilteredNote,
  expiredNote,
  fewCasesNote,
  filteredViewLabel,
  fullPeriodResultLabel,
  fullSearchToggleLabel,
  fullViewLabel,
  loadFailedTitle,
  loadingAvailability,
  postIncompleteAsideNote,
  snapshotNotFoundNote,
  unstableSearchNote,
  startDiagnosisLabel,
} from '@/components/regimelink/fixedCopy'
import { useLoginRedirect } from '@/hooks/useLoginRedirect'
import { useRegimeExploration } from '@/hooks/useRegimeExploration'
import type { RegimeBaselineMonths } from '@/types/regime'
import type { PublicResultDetail } from '@/types/result'
import type {
  RegimeLinkAvailabilityResult,
  RegimeLinkBundleChanged,
  RegimeLinkCase,
  RegimeLinkConfirmRequired,
  RegimeLinkEntryPath,
  RegimeLinkView,
} from '@/types/regimeLink'

const tokenPattern = /^[0-9a-f]{32}$/

function newClientRequestId(): string {
  const source = globalThis.crypto
  if (source && typeof source.randomUUID === 'function') return source.randomUUID()
  return `rls-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/** `caseId`는 마지막 `~`로 나뉜다. 011은 snapshotKey를 분해하지 않는다. */
function snapshotOf(caseId: string): string | null {
  const at = caseId.lastIndexOf('~')
  return at > 0 ? caseId.slice(0, at) : null
}

/**
 * FR-018의 한 문장을 고른다 (T073).
 *
 * 문장을 지어내지 않고 `fixedCopy`의 고정 문구 중 하나를 고르기만 한다. 해당 없으면 `null`이고
 * 전체 기간 결과 비교(T065)는 이 문장과 무관하게 계속 보인다.
 */
function statusNoteFor(
  availability: RegimeLinkAvailabilityResult,
  visibleCount: number,
  openCase: RegimeLinkCase,
): string | null {
  if (!openCase.postWindow.complete63) return postIncompleteAsideNote
  if (availability.search.status === 'NEAREST_ONLY') return unstableSearchNote
  if (visibleCount <= 2) return fewCasesNote
  return null
}

/**
 * 011 결과 화면 (`/regime-link`).
 *
 * 진입 계약은 `contracts/entry-handoff.md` §1·§2다. 주소에 `case`가 있으면 시장 흐름에서 온
 * 것이라 필터를 걸지 않고, 없으면 진단 결과에서 온 것이라 분야·기준월부터 고른다.
 */
export function RegimeLinkPage() {
  const [params, setParams] = useSearchParams()
  const auth = useAuth()
  const { redirectToLogin } = useLoginRedirect()
  const base = params.get('base') ?? ''
  const compare = params.get('compare') ?? undefined
  const snapshotInUrl = params.get('snapshot')
  const caseInUrl = params.get('case')
  const fullInUrl = params.get('full') === '1'

  const exploration = useRegimeExploration(base, snapshotInUrl)
  const entryPath: RegimeLinkEntryPath =
    exploration.stored?.entryPath ?? (caseInUrl ? 'MARKET_FLOW' : 'STRATEGY_RESULT')

  const [baselines, setBaselines] = useState<RegimeBaselineMonths | null>(null)
  const [choice, setChoice] = useState<FieldChoice | null>(null)
  const [availability, setAvailability] = useState<RegimeLinkAvailabilityResult | null>(null)
  const [linkSearchId, setLinkSearchId] = useState<string | null>(null)
  const [bundleChanged, setBundleChanged] = useState<RegimeLinkBundleChanged | null>(null)
  const [full, setFull] = useState(fullInUrl || exploration.stored?.full === true)
  const [openCaseId, setOpenCaseId] = useState<string | null>(caseInUrl)
  const [links, setLinks] = useState<RegimeLinkView[]>([])
  const [confirm, setConfirm] = useState<RegimeLinkConfirmRequired | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [expired, setExpired] = useState(false)
  const [notFound, setNotFound] = useState(false)
  const [loading, setLoading] = useState(false)
  /** 시장 흐름 경로는 방금 접수한 실행을 기다린다(T048). 진단 결과 경로는 이미 끝난 실행이다. */
  const [baseRun, setBaseRun] = useState<PublicResultDetail | null>(null)
  /** 같은 묶음에서 한 번 확인했으면 다음 사례는 확인창 없이 진행한다(T072). */
  const confirmedOnce = useRef(false)

  const validBase = tokenPattern.test(base)
  const snapshotKey =
    snapshotInUrl ?? exploration.stored?.snapshotKey ?? (caseInUrl ? snapshotOf(caseInUrl) : null)
  const needsFieldChoice = validBase && !snapshotKey && entryPath === 'STRATEGY_RESULT'

  // 첫 진입의 분야 선택에 쓸 등록표. 이름·순서를 화면에 박지 않는다.
  useEffect(() => {
    if (!needsFieldChoice) return
    const controller = new AbortController()
    getRegimeBaselineMonths(controller.signal)
      .then((result) => setBaselines(result))
      .catch(() => setError(loadFailedTitle))
    return () => controller.abort()
  }, [needsFieldChoice])

  /**
   * 시장 흐름 경로의 기준 실행을 기다린다 (T048).
   *
   * 003이 접수 직후 이 주소로 보내므로 실행이 아직 `QUEUED`·`RUNNING`일 수 있다. 005와 같은
   * 방식으로 진행 상태를 보이고 완료되면 그대로 이어 간다. 진단 결과 경로는 이미 끝난 실행이라
   * 부르지 않는다.
   */
  useEffect(() => {
    if (!validBase || entryPath !== 'MARKET_FLOW') return
    let alive = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const poll = () => {
      fetchResult(base)
        .then((detail) => {
          if (!alive) return
          setBaseRun(detail)
          if (detail.status === 'QUEUED' || detail.status === 'RUNNING') {
            timer = setTimeout(poll, 3_000)
          }
        })
        .catch(() => undefined)
    }
    poll()
    return () => {
      alive = false
      if (timer) clearTimeout(timer)
    }
  }, [base, entryPath, validBase])

  const runSearch = useCallback(
    async (query: Parameters<typeof fetchAvailability>[0], showFull: boolean) => {
      setLoading(true)
      setError(null)
      setNotFound(false)
      try {
        const result = await fetchAvailability(query)
        setAvailability(result)
        const previous = exploration.stored?.previousCases
        setParams(
          (current) => {
            const next = new URLSearchParams(current)
            next.set('snapshot', result.search.snapshotKey)
            if (showFull) next.set('full', '1')
            else next.delete('full')
            return next
          },
          { replace: true },
        )
        if (auth.status === 'authenticated') {
          const created = await createRegimeLinkSearch({
            clientRequestId: newClientRequestId(),
            snapshotKey: result.search.snapshotKey,
            base,
            compare,
            entryPath,
            fullSearchShown: showFull,
            previousLinkSearchId: exploration.stored?.linkSearchId,
          })
          setAvailability(created)
          setLinkSearchId(created.linkSearchId)
          setBundleChanged(created.bundleChanged ?? null)
          exploration.remember({
            snapshotKey: created.search.snapshotKey,
            entryPath,
            full: showFull,
            linkSearchId: created.linkSearchId,
            previousCases: created.cases.map((item) => item.endMonth),
          })
          return
        }
        // 익명은 기록을 만들지 않는다. 같은 탐색 흐름의 직전 목록으로 변경만 표시한다.
        const currentCases = result.cases.map((item) => item.endMonth)
        if (previous) {
          const added = currentCases.filter((month) => !previous.includes(month))
          const removed = previous.filter((month) => !currentCases.includes(month))
          if (added.length > 0 || removed.length > 0) {
            setBundleChanged({ added, removed, excludedCount: result.filter.excludedCount ?? 0 })
          }
        }
        exploration.remember({
          snapshotKey: result.search.snapshotKey,
          entryPath,
          full: showFull,
          previousCases: currentCases,
        })
      } catch (thrown) {
        const failure = thrown instanceof RegimeLinkError ? thrown : null
        if (failure?.status === 410) setExpired(true)
        else if (failure?.status === 404) setNotFound(true)
        else setError(failure?.message ?? loadFailedTitle)
      } finally {
        setLoading(false)
      }
    },
    [auth.status, base, compare, entryPath, exploration, setParams],
  )

  // 검색 조건이 정해지면 목록을 확정한다. 분야 선택이 남아 있으면 부르지 않는다.
  const started = useRef(false)
  useEffect(() => {
    if (!validBase || started.current) return
    if (!snapshotKey) return
    // 시장 흐름 경로는 기준 실행이 끝나야 목록을 확정한다. 진행 중에는 진행 상태만 보인다.
    if (entryPath === 'MARKET_FLOW' && baseRun?.status !== 'COMPLETED') return
    started.current = true
    void runSearch({ base, compare, snapshotKey, entryPath }, full)
  }, [base, baseRun?.status, compare, entryPath, full, runSearch, snapshotKey, validBase])

  function confirmChoice(next: FieldChoice) {
    started.current = true
    void runSearch(
      {
        base,
        compare,
        entryPath,
        mode: next.mode,
        field: next.mode === 'field' ? next.field : undefined,
        baselineMonth: next.baselineMonth || undefined,
      },
      full,
    )
  }

  function toggleFull() {
    const next = !full
    setFull(next)
    if (snapshotKey) void runSearch({ base, compare, snapshotKey, entryPath }, next)
  }

  const loadLinks = useCallback(
    async (caseId: string) => {
      try {
        setLinks(await listRegimeLinks({ base, compare, caseId }))
      } catch {
        setLinks([])
      }
    },
    [base, compare],
  )

  async function openCase(caseId: string, confirmFullPeriod: boolean) {
    // 익명은 계산을 만들 수 없다. 같은 주소로 돌아오도록 저장한 뒤 로그인으로 보낸다(T044 훅).
    if (auth.status !== 'authenticated') {
      redirectToLogin()
      return
    }
    if (!linkSearchId) return
    setOpenCaseId(caseId)
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.set('case', caseId)
        return next
      },
      { replace: true },
    )
    try {
      const outcome = await createRegimeLinks({
        linkSearchId,
        caseId,
        scenario: 'CONTINUOUS',
        confirmFullPeriod: confirmFullPeriod || confirmedOnce.current,
      })
      if (outcome.outcome) {
        setConfirm(outcome)
        return
      }
      setConfirm(null)
      await loadLinks(caseId)
    } catch (thrown) {
      const failure = thrown instanceof RegimeLinkError ? thrown : null
      setError(failure?.message ?? loadFailedTitle)
    }
  }

  async function enableFreshCash(caseId: string) {
    if (!linkSearchId) return
    try {
      await createRegimeLinks({
        linkSearchId,
        caseId,
        scenario: 'FRESH_CASH',
        confirmFullPeriod: false,
      })
      await loadLinks(caseId)
    } catch {
      // 현금 시작 접수 실패는 그 자리에만 남는다. 연속 운용 결과를 지우지 않는다.
    }
  }

  /**
   * 주소로 들어온 사례는 목록이 확정되면 스스로 열린다(T048). 한 번만 연다.
   *
   * 익명은 계산을 만들 수 없으므로 **이미 있는 공개 결과만 읽는다**. 목록 조회에 주소의 토큰을
   * 그대로 실어 보내므로(`base`·`compare`) 로그인 없이도 열린 결과는 보인다. 새 계산이 필요한
   * 자리는 그대로 두고, 사용자가 누를 때 T044 훅이 로그인으로 보낸다.
   */
  const autoOpened = useRef(false)
  useEffect(() => {
    if (autoOpened.current) return
    if (!caseInUrl || !availability) return
    if (auth.status === 'authenticated') {
      if (!linkSearchId) return
      autoOpened.current = true
      void openCase(caseInUrl, false)
      return
    }
    if (auth.status === 'checking') return
    autoOpened.current = true
    setOpenCaseId(caseInUrl)
    void loadLinks(caseInUrl)
    // openCase는 렌더마다 새로 만들어지므로 의존성에 넣지 않는다. 한 번만 부르는 것은 ref가 막는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.status, availability, caseInUrl, linkSearchId, loadLinks])

  /** 대기·계산 중인 링크가 있으면 다시 읽는다. 완료되면 멈춘다. */
  useEffect(() => {
    if (!openCaseId) return
    const pending = links.some(
      (link) => link.status === 'QUEUED' || link.status === 'RUNNING' || link.status === 'PENDING_RUN',
    )
    if (!pending) return
    const timer = setTimeout(() => void loadLinks(openCaseId), 3_000)
    return () => clearTimeout(timer)
  }, [links, loadLinks, openCaseId])

  const visibleCases: RegimeLinkCase[] = useMemo(() => {
    if (!availability) return []
    return full ? availability.cases : availability.cases.filter((item) => item.shownInFiltered)
  }, [availability, full])

  if (!validBase) {
    return (
      <main>
        <p>{baseTokenMissingNote}</p>
        <Link to="/diagnosis/sectors">{startDiagnosisLabel}</Link>
      </main>
    )
  }

  if (expired) {
    return (
      <main>
        <p>{expiredNote}</p>
      </main>
    )
  }

  const openCase0 = visibleCases.find((item) => item.caseId === openCaseId) ?? null
  const notComputable = availability?.search.status === 'NOT_COMPUTABLE'

  return (
    <main>
      <h1>경제환경이 비슷했던 과거</h1>
      {notFound && <p role="status">{snapshotNotFoundNote}</p>}
      {error && <p role="alert">{error}</p>}

      {(needsFieldChoice || notFound) && baselines && (
        <FieldPicker
          baselines={baselines}
          value={choice}
          onChange={(next) => {
            setChoice(next)
            confirmChoice(next)
          }}
        />
      )}

      {entryPath === 'MARKET_FLOW' &&
        baseRun &&
        (baseRun.status === 'QUEUED' || baseRun.status === 'RUNNING') && (
          <div role="status" data-base-run={baseRun.status}>
            <p>{baseRunPendingTitle}</p>
            <dl>
              <dt>단계</dt>
              <dd>{baseRun.progress?.stage ?? baseRun.status}</dd>
              {baseRun.progress?.completedTradingDays != null && (
                <>
                  <dt>진행</dt>
                  <dd>{`${baseRun.progress.completedTradingDays} / ${baseRun.progress.totalTradingDays ?? '-'}`}</dd>
                </>
              )}
              {baseRun.queueWaitSeconds != null && (
                <>
                  <dt>기다린 시간(초)</dt>
                  <dd>{baseRun.queueWaitSeconds}</dd>
                </>
              )}
            </dl>
          </div>
        )}
      {entryPath === 'MARKET_FLOW' &&
        baseRun &&
        baseRun.status !== 'QUEUED' &&
        baseRun.status !== 'RUNNING' &&
        baseRun.status !== 'COMPLETED' && (
          <div role="status" data-base-run={baseRun.status}>
            <p>{baseRunFailedNote}</p>
            <Link to={`/results/${base}`}>{fullPeriodResultLabel}</Link>
          </div>
        )}

      {loading && <p role="status">{loadingAvailability}</p>}

      {availability && (
        <>
          {bundleChanged && <BundleChangeNotice change={bundleChanged} />}
          {availability.filter.applied && availability.filter.availableRange && (
            <p>
              {`선택 ETF로 비교 가능한 기간 ${availability.filter.availableRange.firstCaseEndMonth ?? '없음'} ~ ${
                availability.filter.availableRange.lastCaseEndMonth ?? '없음'
              }`}
            </p>
          )}
          {availability.filter.applied && availability.filter.excludedCount != null && (
            <p>{`제외된 사례 ${availability.filter.excludedCount}개`}</p>
          )}
          <p>{full ? fullViewLabel : filteredViewLabel}</p>
          <button type="button" onClick={toggleFull}>
            {full ? filteredViewLabel : fullSearchToggleLabel}
          </button>

          {notComputable ? (
            <p role="status">{availability.search.status}</p>
          ) : (
            <>
              {visibleCases.length === 0 && <p role="status">{emptyFilteredNote}</p>}
              <ol>
                {visibleCases.map((item) => (
                  <li key={item.caseId} data-case-id={item.caseId}>
                    <span>{item.rank}</span>
                    <span>{item.endMonth}</span>
                    <span data-availability={item.availability.state}>
                      {item.availability.state}
                    </span>
                    {/* 전체 탐색에서는 실행별 제한 상태도 함께 보인다(T055). */}
                    {full &&
                      item.perRun.map((run) => (
                        <span key={run.publicToken} data-run-availability={run.state}>
                          {run.state}
                        </span>
                      ))}
                    <button type="button" onClick={() => void openCase(item.caseId, false)}>
                      전략 결과 보기
                    </button>
                  </li>
                ))}
              </ol>

              {confirm && openCaseId && (
                <FullPeriodConfirm
                  outcome={confirm.outcome}
                  requiredRange={confirm.requiredRange}
                  affected={confirm.affected}
                  onConfirm={() => {
                    confirmedOnce.current = true
                    void openCase(openCaseId, true)
                  }}
                  onReject={() => setConfirm(null)}
                />
              )}

              {openCase0 && !confirm && (
                <CaseStrategyResults
                  basis={availability.basis}
                  caseItem={openCase0}
                  runs={availability.bundle.runs}
                  links={links.filter((link) => link.scenario === 'CONTINUOUS')}
                  freshLinks={links.filter((link) => link.scenario === 'FRESH_CASH')}
                  onRetry={(linkId) => {
                    void retryRegimeLink(linkId).then(() => loadLinks(openCase0.caseId))
                  }}
                  onEnableFreshCash={() => void enableFreshCash(openCase0.caseId)}
                  statusNote={statusNoteFor(availability, visibleCases.length, openCase0)}
                />
              )}
            </>
          )}
        </>
      )}
    </main>
  )
}

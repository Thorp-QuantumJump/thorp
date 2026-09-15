import { ArrowLeft, SlidersHorizontal } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { createParamSearch, normalizeParamSearchError, previewParamSearch } from '@/api/paramSearch'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { CancelButton } from '@/components/paramsearch/CancelButton'
import { CandidateDetail } from '@/components/paramsearch/CandidateDetail'
import { CandidateTable } from '@/components/paramsearch/CandidateTable'
import { CompareHandoff } from '@/components/paramsearch/CompareHandoff'
import { PartialResultNotice } from '@/components/paramsearch/PartialResultNotice'
import { PreflightPanel } from '@/components/paramsearch/PreflightPanel'
import { RejectionNotice } from '@/components/paramsearch/RejectionNotice'
import { RetryFailedButton } from '@/components/paramsearch/RetryFailedButton'
import { RegistryChangedDialog } from '@/components/paramsearch/RegistryChangedDialog'
import { SelectionResult } from '@/components/paramsearch/SelectionResult'
import { ValidationBadge } from '@/components/paramsearch/ValidationBadge'
import { ValidationNote } from '@/components/paramsearch/ValidationNote'
import { SpaceEditor } from '@/components/paramsearch/SpaceEditor'
import { useParamSearchStatus } from '@/hooks/useParamSearchStatus'
import { useSearchCandidates } from '@/hooks/useSearchCandidates'
import type {
  ParamSearchPreview,
  ParamSearchRequest,
  SearchCandidateItem,
  SearchSpaceSelection,
  StrategySpaceSummary,
} from '@/types/paramSearch'
import { formatCount } from '@/utils/paramSearchLabels'
import styles from './ParamSearchPage.module.css'

/** 쉼표로 이어 붙인 질의 문자열을 목록으로. 빈 값과 중복은 버린다. */
function parseList(raw: string | null): string[] {
  if (!raw) return []
  return [...new Set(raw.split(',').map((item) => item.trim()).filter(Boolean))]
}

/** 진행 상태 문구. 상태 이름을 그대로 보이면 사용자가 읽을 수 없다. */
const statusText: Record<string, string> = {
  QUEUED: '대기 중',
  RUNNING: '실행 중',
  COMPLETED: '완료',
  PARTIAL: '부분 완료',
  FAILED: '실패',
  REJECTED: '거부됨',
}

/**
 * 설정 조합 자동 비교 화면(US1).
 *
 * <p><b>탐색 대상은 이 화면이 정하지 않는다.</b> 섹터와 전략은 전략 화면에서 고른 것을 질의 문자열로
 * 받아 온다 — 화면에 전략 목록을 두면 전략이 늘 때마다 이 파일을 고쳐야 하고, 그것이 FR-025가
 * 막으려는 것이다. 대상이 없으면 실행 대신 들어온 경로를 안내한다.
 *
 * <p>대상을 질의 문자열에 둔 덕에 새로고침·링크 공유가 그대로 되고, 접수한 뒤에는 `searchId`도 같은
 * 자리에 둬서 새로고침해도 진행을 계속 본다.
 */
export function ParamSearchPage() {
  const [params, setParams] = useSearchParams()
  const { user } = useAuth()

  const strategyIds = useMemo(() => parseList(params.get('strategies')), [params])
  const instrumentIds = useMemo(() => parseList(params.get('instruments')), [params])
  const searchId = params.get('searchId')
  const hasTarget = strategyIds.length > 0 && instrumentIds.length > 0

  const [space, setSpace] = useState<SearchSpaceSelection>({})
  /**
   * 좁히기 전의 등록 범위. **좁힌 뒤의 응답을 그대로 쓰면 안 된다** — 미리보기는 보낸 `space`만
   * 되돌려 주므로, 끈 값이 다음 응답에서 사라져 다시 켤 수 없게 된다.
   */
  const [baseSpace, setBaseSpace] = useState<
    { registryVersion: string; summary: StrategySpaceSummary[] } | null
  >(null)
  const [preview, setPreview] = useState<ParamSearchPreview | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewToken, setPreviewToken] = useState(0)
  const [showSpaceEditor, setShowSpaceEditor] = useState(false)

  const [creating, setCreating] = useState(false)
  const [runError, setRunError] = useState<{ code: string | null; message: string } | null>(null)

  // 409를 받은 순간의 미리보기와, 그것을 대신할 새 미리보기. 확인 전에는 다시 보내지 않는다.
  const [conflictBase, setConflictBase] = useState<ParamSearchPreview | null>(null)
  const [conflictNext, setConflictNext] = useState<ParamSearchPreview | null>(null)
  const [conflictLoading, setConflictLoading] = useState(false)
  const [conflictError, setConflictError] = useState<string | null>(null)

  const status = useParamSearchStatus(searchId)
  const requestSeq = useRef(0)
  const targetKey = `${instrumentIds.join(',')}|${strategyIds.join(',')}`

  // 대상이 바뀌면 좁힌 범위는 뜻을 잃는다 — 다른 전략의 축을 그대로 들고 갈 수 없다.
  useEffect(() => {
    setSpace({})
    setBaseSpace(null)
  }, [targetKey])

  // 효과가 baseSpace 에 의존하면 갱신할 때마다 다시 조회한다. 버전만 ref 로 들고 본다.
  const baseSpaceVersion = useRef<string | null>(null)
  useEffect(() => {
    baseSpaceVersion.current = baseSpace?.registryVersion ?? null
  }, [baseSpace])

  const buildRequest = useCallback((): ParamSearchRequest => {
    const request: ParamSearchRequest = {
      universe: { instrumentIds },
      strategy: { strategyIds },
    }
    if (Object.keys(space).length > 0) request.space = space
    return request
  }, [instrumentIds, strategyIds, space])

  // 대상이나 범위가 바뀌면 확인 내용을 다시 받는다. 늦게 온 응답이 최신 것을 덮지 않도록 순번으로 막는다.
  useEffect(() => {
    if (!hasTarget) {
      setPreview(null)
      setPreviewError(null)
      return
    }

    const seq = ++requestSeq.current
    const narrowed = Object.keys(space).length > 0
    let active = true
    setPreviewLoading(true)

    const timer = window.setTimeout(() => {
      previewParamSearch(buildRequest())
        .then((next) => {
          if (!active || seq !== requestSeq.current) return
          setPreview(next)
          setPreviewError(null)

          if (!narrowed) {
            // 좁히지 않고 물었으니 이 응답이 등록 범위 자체다.
            setBaseSpace({ registryVersion: next.registryVersion, summary: next.spaceSummary })
            return
          }
          if (baseSpaceVersion.current !== null && baseSpaceVersion.current !== next.registryVersion) {
            // 등록표가 바뀌면 좁혀 둔 값이 아직 등록된 값인지 알 수 없다. 범위를 놓고 다시 받는다.
            setSpace({})
            setBaseSpace(null)
          }
        })
        .catch((caught: unknown) => {
          if (!active || seq !== requestSeq.current) return
          // 실패하면 이전 확인 내용을 지운다 — 캐시된 요약으로 실행하지 않는다(FR-028).
          setPreview(null)
          setPreviewError(normalizeParamSearchError(caught).message)
        })
        .finally(() => {
          if (active && seq === requestSeq.current) setPreviewLoading(false)
        })
    }, 200)

    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [hasTarget, buildRequest, previewToken, space])

  const startSearch = useCallback(
    async (source: ParamSearchPreview) => {
      setCreating(true)
      setRunError(null)

      try {
        const detail = await createParamSearch(
          { ...buildRequest(), registryVersion: source.registryVersion },
          user?.userId,
        )
        setParams(
          (current) => {
            const next = new URLSearchParams(current)
            next.set('searchId', detail.searchId)
            return next
          },
          { replace: false },
        )
      } catch (caught) {
        const failure = normalizeParamSearchError(caught)

        // 등록표가 바뀌었으면 다시 확인받기 전에는 보내지 않는다(FR-028).
        if (failure.is('REGISTRY_VERSION_CHANGED')) {
          setConflictBase(source)
          setConflictNext(null)
          setConflictError(null)
          setPreviewToken((token) => token + 1)
          return
        }

        setRunError({ code: failure.code, message: failure.message })
      } finally {
        setCreating(false)
      }
    },
    [buildRequest, setParams, user?.userId],
  )

  // 409 대화상자가 열려 있는 동안 바뀐 내용을 가져온다. 실패하면 실행 버튼을 열지 않는다.
  const loadConflictPreview = useCallback(() => {
    setConflictLoading(true)
    setConflictError(null)

    previewParamSearch(buildRequest())
      .then((next) => {
        setConflictNext(next)
        setPreview(next)
      })
      .catch((caught: unknown) => {
        setConflictError(normalizeParamSearchError(caught).message)
      })
      .finally(() => setConflictLoading(false))
  }, [buildRequest])

  useEffect(() => {
    if (conflictBase && !conflictNext && !conflictLoading && !conflictError) {
      loadConflictPreview()
    }
  }, [conflictBase, conflictNext, conflictLoading, conflictError, loadConflictPreview])

  const closeConflict = () => {
    setConflictBase(null)
    setConflictNext(null)
    setConflictError(null)
  }

  const confirmConflict = () => {
    if (!conflictNext) return
    const confirmed = conflictNext
    closeConflict()
    void startSearch(confirmed)
  }

  const startNewSearch = () => {
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.delete('searchId')
        return next
      },
      { replace: false },
    )
    setRunError(null)
  }

  const detail = status.detail
  const selection = detail?.selection ?? null

  // 상세와 비교 담기는 후보 객체를 그대로 들고 있는다 — 목록이 다시 불릴 때마다 id 로 되찾지 않아도 된다.
  const [detailCandidate, setDetailCandidate] = useState<SearchCandidateItem | null>(null)
  const [comparePicks, setComparePicks] = useState<SearchCandidateItem[]>([])

  const toggleComparePick = useCallback((candidate: SearchCandidateItem) => {
    setComparePicks((current) =>
      current.some((item) => item.candidateId === candidate.candidateId)
        ? current.filter((item) => item.candidateId !== candidate.candidateId)
        : [...current, candidate],
    )
  }, [])

  const removeComparePick = useCallback((candidateId: string) => {
    setComparePicks((current) => current.filter((item) => item.candidateId !== candidateId))
  }, [])

  // 표시 후보만 따로 받는다. selection.displayed 의 순서가 정렬 지표 순서라 그대로 맞춘다.
  const displayedQuery = useSearchCandidates(selection ? searchId : null, {
    selected: true,
    size: 200,
  })

  const displayedCandidates = useMemo(() => {
    if (!selection || !displayedQuery.page) return []
    const byId = new Map(
      displayedQuery.page.content.map((candidate) => [candidate.candidateId, candidate]),
    )
    return selection.displayed
      .map((candidateId) => byId.get(candidateId))
      .filter((candidate): candidate is SearchCandidateItem => candidate !== undefined)
  }, [selection, displayedQuery.page])

  const comparePickIds = comparePicks.map((candidate) => candidate.candidateId)

  return (
    <div className={styles.page}>
      <AppHeader />

      <main className={styles.main} aria-labelledby="param-search-title">
        <div className={styles.eyebrow}>
          <SlidersHorizontal size={16} aria-hidden="true" />
          설정 조합 자동 비교
        </div>
        <h1 id="param-search-title">한 번에 여러 설정을 같은 조건으로 비교합니다</h1>
        <p className={styles.lead}>
          고른 섹터와 전략에 등록된 설정 조합을 모두 돌려 같은 기준으로 견줍니다. 몇 개를 돌릴지는
          등록표와 한도가 정하므로 숫자를 입력할 필요가 없습니다.
        </p>

        {!hasTarget ? (
          <section className={styles.guide}>
            <h2>탐색 대상이 없습니다</h2>
            <p>
              비교할 섹터와 전략은 전략 화면에서 고른 것을 그대로 받아 옵니다. 전략 화면에서 '설정
              조합 자동 비교'로 들어와 주세요.
            </p>
            <p className={styles.guideNote}>
              주소로 바로 열 때는 <code>?instruments=&#123;종목ID&#125;&amp;strategies=&#123;전략ID&#125;</code>
              처럼 대상을 함께 적습니다. 고를 수 있는 값은 등록표가 정하므로 이 화면에 목록을 두지
              않습니다.
            </p>
            <Link to="/backtest" className={styles.guideLink}>
              <ArrowLeft size={15} aria-hidden="true" />
              전략 화면으로
            </Link>
          </section>
        ) : (
          <>
            <section className={styles.target} aria-label="탐색 대상">
              <div>
                <span>대상 섹터</span>
                <strong>{instrumentIds.join(', ')}</strong>
              </div>
              <div>
                <span>대상 전략</span>
                <strong>
                  {preview && preview.strategies.length > 0
                    ? preview.strategies.map((item) => item.name).join(', ')
                    : `${formatCount(strategyIds.length)}개`}
                </strong>
              </div>
            </section>

            {searchId ? (
              <section className={styles.progress} aria-labelledby="progress-title">
                <header>
                  <h2 id="progress-title">진행 상태</h2>
                  <button type="button" className={styles.textButton} onClick={startNewSearch}>
                    새 탐색 준비
                  </button>
                </header>

                <p className={styles.searchId}>{searchId}</p>

                {status.isLoading && !detail && (
                  <p className={styles.note} role="status">
                    진행 상태를 불러오는 중입니다.
                  </p>
                )}

                {status.error && (
                  <p className={styles.error} role="alert">
                    {status.error}
                  </p>
                )}

                {detail && (
                  <>
                    <p className={styles.statusLine} aria-live="polite">
                      <strong>{statusText[detail.status] ?? detail.status}</strong>
                      {detail.stopReason ? <span> · 멈추는 중</span> : null}
                      <ValidationBadge label={detail.validationLabel} describes="이 탐색 묶음" />
                    </p>

                    <ValidationNote
                      label={detail.validationLabel}
                      note={detail.validationNote}
                    />

                    {detail.counts && (
                      <dl className={styles.counts}>
                        {[
                          ['유효 후보', detail.counts.valid],
                          ['완료', detail.counts.completed],
                          ['실패', detail.counts.failed],
                          ['대기', detail.counts.pending],
                          ['제외', detail.counts.excluded],
                          ['미실행', detail.counts.skipped],
                        ].map(([label, value]) => (
                          <div key={String(label)}>
                            <dt>{label}</dt>
                            <dd>{formatCount(Number(value))}</dd>
                          </div>
                        ))}
                      </dl>
                    )}

                    {detail.status === 'REJECTED' && detail.stopReason && (
                      <RejectionNotice code={detail.stopReason} message={detail.partialNote} />
                    )}

                    {(detail.partial || detail.stopReason !== null) &&
                      detail.status !== 'REJECTED' && (
                        <PartialResultNotice
                          counts={detail.counts}
                          stopReason={detail.stopReason}
                          partialNote={detail.partialNote}
                          stopping={status.isPolling && detail.stopReason !== null}
                        />
                      )}

                    {status.isPolling && detail.stopReason === null && (
                      <CancelButton
                        searchId={searchId}
                        requesterId={user?.userId}
                        onCancelled={status.refetch}
                      />
                    )}

                    <RetryFailedButton
                      searchId={searchId}
                      failedCount={detail.counts?.failed ?? 0}
                      requesterId={user?.userId}
                      onRetried={status.refetch}
                    />

                  </>
                )}
              </section>
            ) : null}

            {searchId && detail && selection && (
              <>
                {displayedQuery.error && (
                  <p className={styles.error} role="alert">
                    {displayedQuery.error}
                  </p>
                )}
                <SelectionResult
                  selection={selection}
                  selectionRule={detail.config?.selectionRule ?? null}
                  baseline={detail.baseline}
                  config={detail.config}
                  candidates={displayedCandidates}
                  onOpenDetail={setDetailCandidate}
                  comparePicks={comparePickIds}
                  onCompareToggle={toggleComparePick}
                />

                <CompareHandoff
                  picks={comparePicks}
                  config={detail.config}
                  baseline={detail.baseline}
                  onRemove={removeComparePick}
                  onClear={() => setComparePicks([])}
                />

                <CandidateTable
                  searchId={searchId}
                  config={detail.config}
                  onOpenDetail={setDetailCandidate}
                  comparePicks={comparePickIds}
                  onCompareToggle={toggleComparePick}
                />
              </>
            )}

            {searchId ? null : (
              <>
                {previewLoading && !preview && (
                  <p className={styles.note} role="status">
                    실행 전 확인 내용을 불러오는 중입니다.
                  </p>
                )}

                {previewError && (
                  <p className={styles.error} role="alert">
                    {previewError}
                  </p>
                )}

                {preview && (
                  <>
                    <PreflightPanel
                      preview={preview}
                      onRun={() => void startSearch(preview)}
                      running={creating}
                      blockedReason={user ? null : '실행하려면 로그인이 필요합니다.'}
                    />

                    {runError &&
                      (runError.code ? (
                        <RejectionNotice code={runError.code} message={runError.message} />
                      ) : (
                        <p className={styles.error} role="alert">
                          {runError.message}
                        </p>
                      ))}

                    <section className={styles.details}>
                      <button
                        type="button"
                        className={styles.textButton}
                        onClick={() => setShowSpaceEditor((open) => !open)}
                        aria-expanded={showSpaceEditor}
                        aria-controls="space-editor"
                      >
                        {showSpaceEditor ? '상세 변경 접기' : '탐색 범위 상세 변경'}
                      </button>
                      <div id="space-editor" hidden={!showSpaceEditor}>
                        <SpaceEditor
                          spaceSummary={baseSpace?.summary ?? preview.spaceSummary}
                          value={space}
                          onChange={setSpace}
                        />
                      </div>
                    </section>
                  </>
                )}
              </>
            )}
          </>
        )}
      </main>

      {detailCandidate && detail && (
        <CandidateDetail
          candidate={detailCandidate}
          config={detail.config}
          selectionRule={detail.config?.selectionRule ?? null}
          onClose={() => setDetailCandidate(null)}
        />
      )}

      {conflictBase && (
        <RegistryChangedDialog
          open
          previous={conflictBase}
          next={conflictNext}
          loadingNext={conflictLoading}
          reloadError={conflictError}
          onConfirm={confirmConflict}
          onCancel={closeConflict}
          onReload={loadConflictPreview}
        />
      )}
    </div>
  )
}

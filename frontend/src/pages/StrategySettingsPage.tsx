import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import { fetchStrategyRegistry, normalizeStrategyRegistryError } from '@/api/strategyRegistry'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { AppHeader } from '@/components/navigation/AppHeader'
import { ProvisionalBadge } from '@/components/paramsearch/ProvisionalBadge'
import { ActionButtons } from '@/components/strategy/ActionButtons'
import {
  ANALYSIS_VERSIONS_UNAVAILABLE,
  EVALUATION_WINDOW_UNAVAILABLE,
  notConnectedSupport,
  type AnalysisSupport,
  type AnalysisVersions,
} from '@/components/strategy/analysisSupport'
import { ConditionSummaryLine } from '@/components/strategy/ConditionSummaryLine'
import { SettingsStatusBadge } from '@/components/strategy/SettingsStatusBadge'
import { StrategyCardList, type RegistryViewState } from '@/components/strategy/StrategyCardList'
import { useSectorSelection } from '@/hooks/useSectorSelection'
import { useStrategySettings, type InvalidStoredValue } from '@/hooks/useStrategySettings'
import type { BacktestAccepted, BacktestRequestStatus } from '@/types/backtestRequest'
import type { StrategyView } from '@/types/strategyRegistry'
import { buildBacktestRequest } from '@/utils/analysisRequest'
import styles from './StrategySettingsPage.module.css'

interface StrategySettingsPageProps {
  /**
   * 분석을 시작하기 위해 바깥에서 받아야 하는 세 통로(규칙·비용 버전, 워밍업 판정, 요청 전송).
   *
   * 기본값은 아직 아무것도 연결되지 않은 상태다 — '분석하기'가 정직하게 막힌다. B06(T039·T042)이
   * 실제 모듈로 채운다. 검사는 흉내를 넘겨 요청 조립을 고정한다.
   */
  support?: AnalysisSupport
}

/** 접수 결과의 상태 이름. 그대로 보이면 사용자가 읽을 수 없다. */
const statusText: Record<BacktestRequestStatus, string> = {
  QUEUED: '대기 중',
  RUNNING: '실행 중',
  COMPLETED: '완료',
  FAILED: '실패',
  CANCELLED: '취소됨',
}

const REGISTRY_NOT_LOADED = '전략 설정을 불러오기 전에는 분석을 시작할 수 없습니다.'
const SELECT_STRATEGY_FIRST = '전략 카드를 먼저 골라 주세요.'
const REASON_MISSING = '이 전략을 고를 수 없는 이유를 확인하지 못했습니다.'
const INVALID_STORED_VALUES =
  '이전에 저장한 설정값 중 현재 등록표에서 쓸 수 없는 값이 있습니다. 기본값을 복원해 주세요.'
const WARMUP_UNCOMPUTABLE = '현재 설정으로 워밍업을 계산할 수 없습니다.'
const VERSIONS_LOADING = '분석 조건 버전을 확인하는 중입니다.'
const AUTO_COMPARE_NOT_READY = '설정 조합 자동 비교는 아직 준비 중입니다.'
const SUBMIT_FAILED = '분석 요청을 보내지 못했습니다.'

type Submission =
  | { status: 'idle' }
  | { status: 'sending' }
  | { status: 'accepted'; accepted: BacktestAccepted }
  | { status: 'failed'; message: string }

interface InvalidValueListProps {
  /** 이 목록이 무엇에 대한 것인가. 보조기술이 두 목록을 구분할 이름이다 */
  label: string
  entries: InvalidStoredValue[]
  onRestore: (strategyId: string, axisName: string) => void
}

/**
 * 현재 등록표에서 쓸 수 없게 된 보관값을 알린다(FR-017).
 *
 * <p><b>이유를 말하고 복원 선택을 준다. 값을 대신 고치지 않는다.</b> 복원할 수 있는 항목에만
 * 버튼을 두는데, 등록표에서 전략이나 항목 자체가 사라진 경우는 되돌릴 자리가 없어 알리기만
 * 한다.
 */
function InvalidValueList({ label, entries, onRestore }: InvalidValueListProps) {
  return (
    <ul className={styles.invalidList} role="status" aria-label={label}>
      {entries.map((entry) => (
        <li key={`${entry.strategyId ?? '-'}:${entry.axisName ?? '-'}`}>
          <span>{entry.message}</span>
          {entry.restorable && entry.strategyId !== null && entry.axisName !== null ? (
            <button
              type="button"
              className={styles.restoreButton}
              onClick={() => onRestore(entry.strategyId as string, entry.axisName as string)}
            >
              기본값 복원
            </button>
          ) : null}
        </li>
      ))}
    </ul>
  )
}

/**
 * 전략 선택 화면(US1, FR-001·FR-002·FR-018). 흐름은 002 섹터·비중 → <b>003 전략 선택</b> →
 * 004 분석 / 007 자동 비교다.
 *
 * <p><b>기본 화면의 입력은 전략 선택 하나다(SC-005).</b> 카드를 고르면 '기본 설정' 표시, 조건 한 줄,
 * 행동 버튼 둘이 보이고 그 밖의 항목은 접혀 있다. 상세 설정(US2)과 기간·금액·조건(US3)은 자리만
 * 두었고 다음 묶음이 채운다.
 *
 * <p><b>등록표를 받기 전에는 아무것도 시작하지 않는다.</b> 조회 중·실패에는 카드를 고를 수 없고
 * 두 버튼이 막히며, 임시 값이나 예비 등록표로 대신 동작하는 경로가 없다(FR-018, SC-008).
 *
 * <p><b>앞 단계를 마치지 않았으면 기본값을 만들지 않는다.</b> 002가 확정한 섹터·비중은
 * `useSectorSelection` 한 통로로만 읽고, 없거나 손상되었거나 두 개 미만이면 앞 단계로 되돌린다
 * (spec Edge Cases).
 *
 * <p><b>아직 알 수 없는 값은 알 수 없다고 보인다.</b> 규칙·비용 버전(②)과 공통 평가 기간(①)은
 * `support`로 받으며 기본값은 연결되지 않은 상태다. 그때 '분석하기'는 이유와 함께 막히고 카드는
 * 판정하지 못했다는 사실을 보인다 — 값을 지어내 채우지 않는다(원칙 IV·VI).
 */
export function StrategySettingsPage({ support = notConnectedSupport }: StrategySettingsPageProps) {
  const navigate = useNavigate()

  // --- 002 경계: 섹터 목록을 받아 앞 단계의 선택을 확인한다 ---------------------------------
  const [catalog, setCatalog] = useState<SectorCatalogResult | null>(null)
  const [catalogReload, setCatalogReload] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setCatalog(null)
    void fetchSectorCatalog(controller.signal).then((next) => {
      if (active) setCatalog(next)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [catalogReload])

  const reloadCatalog = useCallback(() => setCatalogReload((count) => count + 1), [])

  const sectors = catalog?.status === 'loaded' ? catalog.catalog.sectors : null
  const selection = useSectorSelection(sectors)
  const universeSize = selection.status === 'ready' ? selection.selection.items.length : null

  // --- 등록표 조회. 섹터 수를 알아야 유효성 제약을 미리 적용한 응답을 받는다 ----------------
  const [registryState, setRegistryState] = useState<RegistryViewState>({ status: 'loading' })
  const [registryReload, setRegistryReload] = useState(0)

  useEffect(() => {
    if (universeSize === null) return
    const controller = new AbortController()
    let active = true

    // 실패한 뒤의 다시 시도는 `loading`으로 되돌리지 않는다. 안내가 사라졌다 나타나면 alert가
    // 다시 읽히고 초점이 튄다(StrategyCardList의 상태 설명).
    setRegistryState((current) =>
      current.status === 'failed' ? { ...current, retrying: true } : { status: 'loading' },
    )

    fetchStrategyRegistry(universeSize, controller.signal)
      .then((registry) => {
        if (active) setRegistryState({ status: 'loaded', registry })
      })
      .catch((error: unknown) => {
        if (!active) return
        const normalized = normalizeStrategyRegistryError(error)
        if (normalized.canceled) return
        setRegistryState((current) => ({
          status: 'failed',
          message: normalized.message,
          failureCount: (current.status === 'failed' ? current.failureCount : 0) + 1,
          retrying: false,
        }))
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [universeSize, registryReload])

  const reloadRegistry = useCallback(() => setRegistryReload((count) => count + 1), [])

  const registry = registryState.status === 'loaded' ? registryState.registry : null
  const settings = useStrategySettings(registry, universeSize)

  // --- 규칙·비용 버전(②). 결정 전에는 null이고 '분석하기'가 막힌다 ---------------------------
  const [versions, setVersions] = useState<AnalysisVersions | null | 'loading'>('loading')

  /**
   * 통로 객체의 **최신 값**을 효과가 참조 변화 없이 읽는 자리다.
   *
   * <p>효과가 `support` 객체의 참조에 의존하면, 호출자가 렌더마다 새 객체를 만들어 넘길 때
   * <b>부모가 다시 그려질 때마다 버전을 다시 조회한다.</b> 그때마다 화면이 '확인하는 중'으로
   * 돌아가 '분석하기'가 잠깐씩 막히고, 사용자가 누르려던 순간에 버튼이 비활성으로 바뀐다.
   *
   * <p><b>무한히 도는 것은 아니다.</b> 조회가 바꾸는 것은 이 화면의 상태뿐이라 부모는 다시 그려지지
   * 않고, 그래서 조회가 스스로를 다시 부르지는 못한다. 되풀이는 부모가 제 사정으로 다시 그릴 때만
   * 생긴다 — 드물지만 원인을 짐작하기 어려운 종류다.
   *
   * <p>통로를 `useMemo`로 감싸 달라는 계약을 아래 타입에 적어 두었으나, 계약만으로는 어겼다는
   * 사실이 드러나지 않는다. 그래서 효과는 참조를 보지 않고 이 ref로 최신 통로를 읽는다. 조회는
   * 화면이 열릴 때 한 번 돈다. 다시 조회할 수단은 B06이 등록표 조회처럼 재시도 토큰으로 더한다.
   */
  const supportRef = useRef(support)
  supportRef.current = support

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setVersions('loading')
    supportRef.current
      .fetchAnalysisVersions(controller.signal)
      .then((next) => {
        if (active) setVersions(next)
      })
      .catch(() => {
        if (active) setVersions(null)
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [])

  // --- 워밍업 판정(①). 카드 목록은 기본 설정 기준, 선택 전략은 현재 설정 기준 -----------------
  const minEvaluationYears = settings.ready
    ? settings.analysisCondition.minEvaluationYears
    : Number(registry?.analysisConditions.minEvaluationYears.default)

  const judgeWarmup = useCallback(
    (strategy: StrategyView) =>
      support.judgeWarmup({
        strategy,
        warmupTradingDays: strategy.warmupTradingDays,
        minEvaluationYears,
      }).verdict,
    [support, minEvaluationYears],
  )

  const selectedJudgement = useMemo(() => {
    if (!settings.selectedStrategy || settings.warmupTradingDays === null) return null
    return support.judgeWarmup({
      strategy: settings.selectedStrategy,
      warmupTradingDays: settings.warmupTradingDays,
      minEvaluationYears,
    })
  }, [support, settings.selectedStrategy, settings.warmupTradingDays, minEvaluationYears])

  // --- 다시 확인할 보관값 -------------------------------------------------------------------
  //
  // **두 자리로 가른다.** 전략에 딸린 값은 그 전략을 고른 뒤에 보여야 뜻이 통하지만, 전략과
  // 무관한 값(분석 조건)은 고르기 전에 알려야 한다 — 최소 평가 기간은 카드의 선택 가능 여부를
  // 바꾸는 값이라(FR-011a) 카드를 보기 전에 그 값이 무엇인지 알아야 한다. 한 자리에 묶어 두면
  // 되돌아간 사실을 전략을 고른 뒤에야 알게 되고, 그것은 사용자 입장에서 조용히 바꾼 것과
  // 같다(FR-017).
  const globalInvalid = settings.invalidValues.filter((entry) => entry.strategyId === null)
  const strategyInvalid = settings.invalidValues.filter(
    (entry) => entry.strategyId !== null && entry.strategyId === settings.selectedStrategyId,
  )

  // --- 행동 가능 여부 ---------------------------------------------------------------------
  //
  // **"보낼 수 있는가"는 축이 다 찼는지 하나로만 묻는다.** 앞서는 "복원할 수 있는 안내가 있는가"로
  // 대신 물었는데, 그 둘이 같은 답을 내는 것은 재검증이 비운 축마다 안내를 남기기 때문이지 그렇게
  // 하라고 정해 둔 것이 아니다. 그 대응이 한 줄만 어긋나면 축이 빠진 요청이 서버로 나간다(T048의
  // "등록된 축이 빠져 있으면 400"). 안내를 그릴지는 위 두 목록이 따로 정한다.
  const settingsIncomplete =
    settings.selectedStrategyId !== null && !settings.isComplete(settings.selectedStrategyId)

  const prepared = useMemo(() => {
    if (
      selection.status !== 'ready' ||
      registry === null ||
      !settings.ready ||
      settings.registryVersion === null ||
      !settings.selectedStrategy ||
      !settings.values ||
      !settings.isComplete(settings.selectedStrategy.strategyId) ||
      settings.warmupTradingDays === null ||
      !selectedJudgement?.window ||
      versions === 'loading' ||
      versions === null
    ) {
      return null
    }
    return {
      registry,
      selection: selection.selection,
      strategy: settings.selectedStrategy,
      parameters: settings.values,
      registryVersion: settings.registryVersion,
      warmupTradingDays: settings.warmupTradingDays,
      period: selectedJudgement.window,
      versions,
    }
  }, [selection, registry, settings, selectedJudgement, versions])

  const analyzeBlocker = ((): string | null => {
    if (registry === null || !settings.ready) return REGISTRY_NOT_LOADED
    const strategy = settings.selectedStrategy
    if (!strategy) return SELECT_STRATEGY_FIRST
    if (!strategy.selectable) return strategy.unselectableReason ?? REASON_MISSING
    if (settingsIncomplete) return INVALID_STORED_VALUES
    if (settings.warmupTradingDays === null) return WARMUP_UNCOMPUTABLE
    if (!selectedJudgement) return EVALUATION_WINDOW_UNAVAILABLE
    const verdict = selectedJudgement.verdict
    if (verdict.state === 'blocked' || verdict.state === 'unknown') return verdict.message
    if (!selectedJudgement.window) return EVALUATION_WINDOW_UNAVAILABLE
    if (versions === 'loading') return VERSIONS_LOADING
    if (versions === null) return ANALYSIS_VERSIONS_UNAVAILABLE
    return null
  })()

  // 자동 비교 진입(FR-013)은 B11(T070)이 붙인다. 그 전까지는 이유와 함께 막힌다.
  const autoCompareBlocker =
    registry === null || !settings.ready
      ? REGISTRY_NOT_LOADED
      : !settings.selectedStrategy
        ? SELECT_STRATEGY_FIRST
        : AUTO_COMPARE_NOT_READY

  // --- 요청 조립과 전송 --------------------------------------------------------------------
  const [submission, setSubmission] = useState<Submission>({ status: 'idle' })

  const handleSelect = useCallback(
    (strategyId: string) => {
      settings.select(strategyId)
      setSubmission({ status: 'idle' })
    },
    [settings],
  )

  const handleAnalyze = useCallback(async () => {
    if (analyzeBlocker !== null || prepared === null || submission.status === 'sending') return

    // 값을 만들지 않는다. 시작 금액은 사용자가 아직 바꾸지 않았으면 등록표의 기본값이다(B10이 상태에
    // 채우기 전까지는 등록표에서 직접 읽는다).
    const request = buildBacktestRequest({
      selection: prepared.selection,
      strategy: prepared.strategy,
      parameters: prepared.parameters,
      registryVersion: prepared.registryVersion,
      warmupTradingDays: prepared.warmupTradingDays,
      versions: {
        rulesVersion: prepared.versions.rulesVersion,
        costProfileVersion: prepared.versions.costProfileVersion,
      },
      period: prepared.period,
      initialAmount:
        settings.analysisCondition.initialAmount ??
        prepared.registry.analysisConditions.initialAmount.default,
      scenario: settings.analysisCondition.scenario,
    })

    setSubmission({ status: 'sending' })
    try {
      const accepted = await support.createBacktest(request)
      setSubmission({ status: 'accepted', accepted })
    } catch (error) {
      setSubmission({
        status: 'failed',
        message: error instanceof Error && error.message ? error.message : SUBMIT_FAILED,
      })
    }
  }, [analyzeBlocker, prepared, submission.status, settings.analysisCondition, support])

  // --- 화면 -----------------------------------------------------------------------------
  const selectedId = settings.selectedStrategyId
  const costProfile =
    versions !== 'loading' && versions !== null
      ? { version: versions.costProfileVersion, provisional: versions.costProfileProvisional }
      : null

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="strategy-settings-title">
        <h1 id="strategy-settings-title" className={styles.title}>
          전략 고르기
        </h1>
        <p className={styles.lead}>
          고른 섹터를 어떻게 나눌지 정하는 방식을 하나 고릅니다. 기본 설정으로 바로 분석할 수 있습니다.
        </p>

        {catalog === null ? (
          <p className={styles.loading} role="status">
            앞 단계에서 고른 섹터를 확인하는 중입니다.
          </p>
        ) : null}

        {/*
          섹터 목록을 받지 못하면 앞 단계의 선택이 온전한지 판정할 수 없다. 판정하지 않은 것을
          "마치지 않았다"로 접지 않고, 확인할 수 없다는 사실을 말한다(useSectorSelection의 상태 설명).
        */}
        {catalog !== null && catalog.status !== 'loaded' ? (
          <NotReadyNotice
            className={styles.notice}
            message="섹터 목록을 확인할 수 없어 앞 단계의 선택을 읽지 못했습니다."
            action={
              catalog.status === 'failed' && !catalog.retryable
                ? undefined
                : { label: '다시 불러오기', onAction: reloadCatalog }
            }
          />
        ) : null}

        {selection.status === 'incomplete' ? (
          <NotReadyNotice
            className={styles.notice}
            message="섹터·비중 단계를 먼저 마쳐 주세요. 비교하려면 섹터를 두 개 이상 골라야 합니다."
            action={{ label: '섹터·비중 단계로 이동', onAction: () => navigate('/diagnosis/sectors') }}
          />
        ) : null}

        {selection.status === 'ready' ? (
          <>
            {selection.dropped.length > 0 ? (
              <p className={styles.restoreNotice} role="status">
                앞 단계에서 고른 섹터 {selection.dropped.length}개를 그대로 쓸 수 없어 비교 대상에서
                뺐습니다. 섹터·비중 단계에서 다시 확인해 주세요.
              </p>
            ) : null}

            {/* 임시 등록표는 그 사실을 상시 표시하고 확정값으로 표현하지 않는다(계약 §1 `provisional`). */}
            {registry?.provisional ? (
              <p className={styles.provisional}>
                <ProvisionalBadge label="전략 설정 등록표" />
                <span>
                  이 전략 설정 등록표는 팀 확정 전 임시값입니다. 결과를 확정값으로 읽지 마세요.
                </span>
              </p>
            ) : null}

            {/*
              전략과 무관한 보관값의 안내다. **카드 목록보다 위에, 전략을 고르기 전에 보인다.**
              최소 평가 기간이 그렇다 — 그 값이 카드의 선택 가능 여부를 정하므로(FR-011a) 카드를
              보기 전에 지금 어떤 값이 쓰이고 있는지 알아야 한다.
            */}
            {globalInvalid.length > 0 ? (
              <InvalidValueList
                label="다시 확인할 분석 조건"
                entries={globalInvalid}
                onRestore={settings.resetAxis}
              />
            ) : null}

            <section className={styles.section} aria-labelledby="strategy-cards-title">
              <h2 id="strategy-cards-title" className={styles.sectionTitle}>
                전략 카드
              </h2>
              <StrategyCardList
                state={registryState}
                selectedStrategyId={selectedId}
                onSelect={handleSelect}
                onRetry={reloadRegistry}
                judgeWarmup={judgeWarmup}
              />
            </section>

            {settings.ready && settings.selectedStrategy && selectedId !== null ? (
              <section className={styles.section} aria-labelledby="selected-strategy-title">
                <h2 id="selected-strategy-title" className={styles.sectionTitle}>
                  선택한 전략: {settings.selectedStrategy.name}
                </h2>

                <SettingsStatusBadge isDefault={settings.isDefault(selectedId)} />

                <ConditionSummaryLine
                  period={selectedJudgement?.window ?? null}
                  sectorCount={universeSize ?? 0}
                  costProfile={costProfile}
                />

                {strategyInvalid.length > 0 ? (
                  <InvalidValueList
                    label="다시 확인할 설정값"
                    entries={strategyInvalid}
                    onRestore={settings.resetAxis}
                  />
                ) : null}

                {selectedJudgement && selectedJudgement.verdict.state !== 'ok' ? (
                  <p className={styles.judgement} role="status" data-state={selectedJudgement.verdict.state}>
                    {selectedJudgement.verdict.message}
                  </p>
                ) : null}

                {/* US2·US3가 채우는 자리. 접혀 있고 기본 화면의 입력을 늘리지 않는다(SC-005). */}
                <details className={styles.folded}>
                  <summary>상세 설정</summary>
                  <p>상세 설정은 아직 준비 중입니다. 지금은 등록표의 기본값으로 분석합니다.</p>
                </details>
                <details className={styles.folded}>
                  <summary>기간·금액·조건</summary>
                  <p>기간·금액·조건 변경은 아직 준비 중입니다. 위의 한 줄이 현재 조건입니다.</p>
                </details>
              </section>
            ) : null}

            <ActionButtons
              analyze={{
                disabled: analyzeBlocker !== null,
                reason: analyzeBlocker ?? undefined,
                busy: submission.status === 'sending',
              }}
              autoCompare={{ disabled: true, reason: autoCompareBlocker }}
              onAnalyze={() => {
                void handleAnalyze()
              }}
              onAutoCompare={() => {}}
            />

            {submission.status === 'accepted' ? (
              <p className={styles.result} role="status">
                분석 요청을 접수했습니다. 요청 번호 {submission.accepted.backtestId}, 상태{' '}
                {statusText[submission.accepted.status] ?? submission.accepted.status}. 결과 화면으로의
                이동은 아직 준비 중입니다.
              </p>
            ) : null}
            {submission.status === 'failed' ? (
              <p className={styles.error} role="alert">
                {submission.message}
              </p>
            ) : null}
          </>
        ) : null}
      </main>
    </div>
  )
}

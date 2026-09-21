import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import { fetchStrategyRegistry, normalizeStrategyRegistryError } from '@/api/strategyRegistry'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { AppHeader } from '@/components/navigation/AppHeader'
import { ProvisionalBadge } from '@/components/paramsearch/ProvisionalBadge'
import { ActionButtons } from '@/components/strategy/ActionButtons'
import { AnalysisConditionsPanel } from '@/components/strategy/AnalysisConditionsPanel'
import {
  ANALYSIS_VERSIONS_UNAVAILABLE,
  EVALUATION_WINDOW_UNAVAILABLE,
  BacktestRejectionCode,
  RULES_PENDING_DECISION,
  RULES_VERSION_UNAVAILABLE,
  readBacktestRejection,
  resolveRulesVersion,
  type AnalysisSupport,
  type AnalysisVersions,
  type BacktestRejection,
  type WarmupJudgement,
} from '@/components/strategy/analysisSupport'
import { ConditionSummaryLine } from '@/components/strategy/ConditionSummaryLine'
import { defaultAnalysisSupport } from '@/components/strategy/connectedAnalysisSupport'
import { DetailSettingsPanel } from '@/components/strategy/DetailSettingsPanel'
import { ReducedPeriodNotice } from '@/components/strategy/ReducedPeriodNotice'
import {
  describeReducedPeriod,
  type ReducedPeriodInput,
  type WarmupCause,
} from '@/components/strategy/reducedPeriod'
import type { TargetWeightRow } from '@/components/strategy/ReadOnlyTargetWeights'
import {
  RegistryChangedNotice,
  type RegistryReloadPhase,
} from '@/components/strategy/RegistryChangedNotice'
import { SettingsStatusBadge } from '@/components/strategy/SettingsStatusBadge'
import type { WarmupVerdict } from '@/components/strategy/StrategyCard'
import { StrategyCardList, type RegistryViewState } from '@/components/strategy/StrategyCardList'
import { formatWeightTenths as formatDisplayWeight } from '@/diagnosis/weights'
import { useEvaluationWindow } from '@/hooks/useEvaluationWindow'
import { useSectorSelection } from '@/hooks/useSectorSelection'
import { useStrategySettings, type InvalidStoredValue } from '@/hooks/useStrategySettings'
import type { BacktestAccepted, BacktestRequestStatus } from '@/types/backtestRequest'
import type { RegistryResponse, StrategyView } from '@/types/strategyRegistry'
import { isWithin, meetsMinimumYears, type AnalysisPeriod } from '@/utils/analysisPeriod'
import { buildBacktestRequest } from '@/utils/analysisRequest'
import { evaluationWindowQuery } from '@/utils/evaluationWindow'
import { checkRules } from '@/utils/validationRules'
import styles from './StrategySettingsPage.module.css'

interface StrategySettingsPageProps {
  /**
   * 분석을 시작하기 위해 바깥에서 받아야 하는 세 통로(규칙·비용 버전, 워밍업 판정, 요청 전송).
   *
   * 기본값은 `defaultAnalysisSupport`로, 001 평가 기간 조회와 004 규칙·비용 버전 조회, 요청 전송이 모두 실제
   * 모듈이다(T107·T110). 받지 못한 통로가 있으면 '분석하기'가 그 이유로 막힌다. 검사는 흉내를 넘겨 요청
   * 조립을 고정한다.
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
const RULES_VIOLATED = '설정 규칙을 어기는 값이 있습니다. 상세 설정 아래의 안내를 확인해 주세요.'
const RULES_UNJUDGED = '설정 규칙을 판정하지 못해 분석을 시작할 수 없습니다.'
const VERSIONS_LOADING = '분석 조건 버전을 확인하는 중입니다.'
const SUBMIT_FAILED = '분석 요청을 보내지 못했습니다.'
const STRATEGY_NOT_REGISTERED_MESSAGE =
  '고른 전략을 지금은 분석할 수 없어 요청이 접수되지 않았습니다. 전략 설정을 다시 불러왔으니 전략을 다시 골라 주세요.'
const PARAMETER_REJECTED_GENERAL =
  '설정값이 허용 범위 밖이라 접수되지 않았습니다. 상세 설정과 기간·금액·조건을 확인해 주세요.'
/** 고른 기간이 지금 평가할 수 있는 범위 밖이다. 범위는 전략·워밍업을 따라 바뀌므로 고른 뒤에 벗어날 수 있다 */
function periodOutOfRangeMessage(range: AnalysisPeriod): string {
  return `고른 분석 기간이 지금 평가할 수 있는 범위(${range.start} ~ ${range.end}) 밖입니다. 기간을 다시 고르거나 범위 전체로 되돌려 주세요.`
}

/** 고른 기간이 최소 평가 기간보다 짧다(FR-011a). 후보의 라벨은 등록표에서 그대로 온다 */
function periodTooShortMessage(label: string): string {
  return `고른 분석 기간이 최소 평가 기간(${label})보다 짧습니다. 기간을 늘리거나 최소 평가 기간을 바꿔 주세요.`
}

const RANGE_NEEDS_STRATEGY = '전략을 고르면 평가할 수 있는 범위가 정해지고 그 안에서 기간을 바꿀 수 있습니다.'

const REQUEST_BUILD_FAILED =
  '분석 요청을 만들지 못했습니다. 화면을 새로 고친 뒤 조건을 다시 확인해 주세요.'

/**
 * 비중 합계가 어긋났다는 한 문장. 지금 합계를 함께 적어 얼마나 어긋났는지 보이게 한다.
 * 표시 형식은 002 화면과 같은 `formatWeightTenths`를 쓴다 — 같은 선택이 두 화면에서 다른 숫자로
 * 보이지 않게 한다.
 */
function weightsUnbalancedMessage(totalTenths: number): string {
  return `섹터별 비중의 합계가 100%가 아닙니다(현재 ${formatDisplayWeight(totalTenths)}%). 섹터·비중 단계에서 맞춰 주세요.`
}

/**
 * 허용 범위 밖이라 거절되었다는 한 문장(400 `UNREGISTERED_PARAMETER_VALUE`).
 *
 * <p><b>서버 메시지를 그대로 보이지 않는다.</b> 그 문장은 `strategyId=…, 항목=…`처럼 로그용으로 짜여
 * 사용자가 읽을 수 없다. 응답 `details.axis`로 고른 전략에서 항목을 찾아 등록표의 라벨을 쓰고, 찾지
 * 못하면(시작 금액이거나 `details`가 없으면) 어느 항목인지 지어내지 않고 일반 문장을 쓴다.
 */
function parameterRejectedMessage(rejection: BacktestRejection, strategy: StrategyView): string {
  const axisName = rejection.details?.axis
  const axis =
    typeof axisName === 'string' ? strategy.axes.find((candidate) => candidate.name === axisName) : undefined
  if (!axis) return PARAMETER_REJECTED_GENERAL
  return `'${axis.label}' 값이 허용 범위 밖이라 접수되지 않았습니다. 상세 설정에서 값을 확인해 주세요.`
}

type Submission =
  | { status: 'idle' }
  | { status: 'sending' }
  | { status: 'accepted'; accepted: BacktestAccepted }
  /** `retryable`이면 같은 설정으로 다시 보낼 수 있다고 제안한다. 누르기 전에는 보내지 않는다 */
  | { status: 'failed'; message: string; retryable?: boolean }

interface InvalidValueListProps {
  /** 이 목록이 무엇에 대한 것인가. 보조기술이 두 목록을 구분할 이름이다 */
  label: string
  entries: InvalidStoredValue[]
  /** 복원할 수 있는 항목 하나. 전략 축인지 분석 조건인지는 받는 쪽이 가른다 */
  onRestore: (entry: InvalidStoredValue) => void
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
          {entry.restorable && entry.axisName !== null ? (
            <button type="button" className={styles.restoreButton} onClick={() => onRestore(entry)}>
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
 * 행동 버튼 둘이 보이고 그 밖의 항목은 접혀 있다. 기간·금액·조건(US3)도 접힌 패널이다.
 *
 * <p><b>바꾼 값은 그 자리에서 판정한다(US2, FR-008·FR-011).</b> 상세 설정에서 값을 바꾸면 규칙 위반,
 * 워밍업, 고른 카드의 판정, 기본 설정 표시, '분석하기'의 막힘이 한 렌더 안에서 다시 계산된다. 값을
 * 만지는 동안 서버로 요청이 나가지 않는다. 최종 판정은 접수 시점에 서버가 다시 하고, 그 사이 등록표가
 * 바뀌어 409로 거절되면 다시 받아 알리되 자동으로 다시 보내지 않는다(research R9).
 *
 * <p><b>등록표를 받기 전에는 아무것도 시작하지 않는다.</b> 조회 중·실패에는 카드를 고를 수 없고
 * 두 버튼이 막히며, 임시 값이나 예비 등록표로 대신 동작하는 경로가 없다(FR-018, SC-008).
 *
 * <p><b>앞 단계를 마치지 않았으면 기본값을 만들지 않는다.</b> 002가 확정한 섹터·비중은
 * `useSectorSelection` 한 통로로만 읽고, 없거나 손상되었거나 두 개 미만이면 앞 단계로 되돌린다
 * (spec Edge Cases).
 *
 * <p><b>아직 알 수 없는 값은 알 수 없다고 보인다.</b> 규칙·비용 버전(②)과 공통 평가 기간(①)은
 * `support`로 받는다. 받지 못한 통로가 있으면 '분석하기'는 이유와 함께 막히고 카드는 판정하지 못했다는
 * 사실을 보인다 — 값을 지어내 채우지 않는다(원칙 IV·VI).
 */
export function StrategySettingsPage({ support = defaultAnalysisSupport }: StrategySettingsPageProps) {
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

  // 목록과 그 대응표 버전을 한 덩이로 넘긴다. 따로 넘기면 서로 다른 조회의 결과가 짝지어질 수 있다.
  const selection = useSectorSelection(catalog?.status === 'loaded' ? catalog.catalog : null)
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

  // --- 규칙·비용 버전(②). 조회 실패 시 분석을 막고 사용자가 다시 조회한다 -------------------
  const [versions, setVersions] = useState<AnalysisVersions | null | 'loading'>('loading')
  const [versionsReload, setVersionsReload] = useState(0)
  const [versionsRetrying, setVersionsRetrying] = useState(false)
  const versionsRetryRequested = useRef(false)
  const versionsRetryButtonRef = useRef<HTMLButtonElement>(null)
  const strategyCardsRef = useRef<HTMLElement>(null)

  /**
   * 버전 조회는 **지금 넘겨받은 조회 함수**를 따른다.
   *
   * <p>한때 화면이 열릴 때 한 번만 조회했다(T094). 부모가 다시 그릴 때마다 조회가 되풀이되며
   * '분석하기'가 잠깐씩 막히는 것을 없애려던 것인데, 그렇게 하면 호출자가 통로를 실제로 바꿨을 때도
   * 버전 조회만 옛 통로에 멈춘다. 평가 기간 판정과 요청 전송은 새 통로를 쓰는데 버전은 옛 통로의
   * 결과(예: 연결 전의 `null`)에 머물러 '분석하기'가 계속 막혔다.
   *
   * <p>그래서 두 가지를 갈라 푼다.
   * <ul>
   *   <li><b>무엇을 따를지는 조회 함수로 가른다.</b> 객체 참조가 아니라 함수 참조를 의존성으로 둔다.
   *       통로 객체를 매번 새로 펼쳐 만들어도 같은 함수를 담고 있으면 다시 조회하지 않는다.</li>
   *   <li><b>다시 조회하는 동안 받은 값을 거두지 않는다.</b> '확인하는 중'으로 되돌리지 않아 버튼이
   *       깜빡이지 않는다. 새 결과가 오면 그 값으로 바뀌고, 조회가 실패하면 그때 막힌다.</li>
   * </ul>
   *
   * <p><b>남는 비용</b>: 호출자가 렌더마다 조회 함수 자체를 새로 만들면 부모가 다시 그릴 때마다
   * 요청이 한 번씩 더 나간다. 화면은 깜빡이지 않지만 요청은 헛돈다. 통로를 안정된 참조로 넘기라는
   * 계약(`AnalysisSupport`)이 그 몫을 맡는다.
   *
   * <p><b>409 뒤에는 일부러 두 등록표를 다시 조회한다(`versionsReload`).</b> 003 등록표 버전이
   * 바뀌면 409 `REGISTRY_VERSION_CHANGED`로 거절된다. 004의 옛 계산 규칙 버전 거절은 Phase 10 T029
   * 구현 뒤 같은 복구 경로를 쓴다. 조회 실패 후 사용자 재시도에서도 '확인하는 중'으로 분석을 막는다.
   */
  const fetchAnalysisVersions = support.fetchAnalysisVersions

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    fetchAnalysisVersions(controller.signal)
      .then((next) => {
        if (active) setVersions(next)
      })
      .catch(() => {
        if (active) setVersions(null)
      })
      .finally(() => {
        if (active) setVersionsRetrying(false)
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [fetchAnalysisVersions, versionsReload])

  useEffect(() => {
    if (!versionsRetryRequested.current || versionsRetrying || versions === 'loading') return
    versionsRetryRequested.current = false
    // 재시도로 사라지는 버튼에 초점을 남기지 않는다(NFR-001). 최초 조회에는 이동하지 않는다.
    if (versions === null) versionsRetryButtonRef.current?.focus()
    else strategyCardsRef.current?.focus()
  }, [versions, versionsRetrying])

  // --- 공통 평가 기간(①). 섹터 집합과 등록표의 워밍업 상한이 바뀔 때만 조회한다 ---------------
  //
  // 값을 바꾸는 동안에는 받아 둔 거래일 목록으로 판정하므로 요청이 나가지 않는다(T098, research R7-1).
  const windowQuery = useMemo(() => {
    if (selection.status !== 'ready' || !registry) return null
    return evaluationWindowQuery(
      selection.selection.items.map((item) => item.sectorId),
      registry,
    )
  }, [selection, registry])
  const {
    state: evaluationWindow,
    retry: retryEvaluationWindow,
    retrying: evaluationWindowRetrying,
  } = useEvaluationWindow(support.fetchEvaluationWindow, windowQuery)

  // --- 워밍업 판정(①). 카드 목록은 기본 설정 기준, 선택 전략은 현재 설정 기준 -----------------
  const minEvaluationYears = settings.ready
    ? settings.analysisCondition.minEvaluationYears
    : Number(registry?.analysisConditions.minEvaluationYears.default)

  const selectedStrategyId = settings.selectedStrategyId
  const currentWarmup = settings.warmupTradingDays

  const selectedJudgement = useMemo((): WarmupJudgement | null => {
    if (!settings.selectedStrategy || settings.warmupTradingDays === null) return null
    return support.judgeWarmup({
      strategy: settings.selectedStrategy,
      evaluationWindow,
      warmupTradingDays: settings.warmupTradingDays,
      minEvaluationYears,
    })
  }, [support, evaluationWindow, settings.selectedStrategy, settings.warmupTradingDays, minEvaluationYears])

  /**
   * 고른 전략의 평가 구간이 워밍업 때문에 줄었다는 안내의 재료(FR-011, SC-004). 줄지 않았으면 `null`이다.
   *
   * <p>시작일을 정한 후보는 001이 `instrumentId`로 알려 준다. 사용자가 읽을 섹터 이름은 002 선택과 섹터
   * 목록에서 찾고, 찾지 못하면 식별자를 그대로 보인다. 이름을 지어내지 않는다. 워밍업을 이루는 항목은
   * 등록표 `warmupRule.sum`이 가리키는 축의 라벨·단위와 지금 값이다(FR-005).
   *
   * <p><b>통로가 원인을 주지 않은 줄어든 판정(`reduction: null`)도 버리지 않는다.</b> 그때는 이 값이
   * `null`이고, '분석하기' 위의 안내가 판정 문장을 그대로 보인다(SC-004).
   */
  const reducedPeriod = useMemo((): ReducedPeriodInput | null => {
    const strategy = settings.selectedStrategy
    if (
      !strategy ||
      !selectedJudgement ||
      selectedJudgement.verdict.state !== 'reduced' ||
      !selectedJudgement.window ||
      !selectedJudgement.reduction ||
      settings.warmupTradingDays === null
    ) {
      return null
    }
    const { bindingCandidate, bindingSector } = selectedJudgement.reduction
    // 001이 섹터 코드를 함께 주면 그것을 쓰고, 주지 않으면 002 선택에서 종목 코드로 되짚는다.
    const sectorCode =
      bindingSector ??
      (selection.status === 'ready'
        ? selection.selection.items.find((candidate) => candidate.instrumentId === bindingCandidate)
            ?.sectorId
        : undefined)
    const sectorName =
      sectorCode !== undefined && catalog?.status === 'loaded'
        ? catalog.catalog.sectors.find((sector) => sector.code === sectorCode)?.name
        : undefined
    // 값이 수가 아닌 항목(합성 값 등)은 워밍업에 더해질 수 없으므로 원인에서 뺀다. 이름을 지어 붙이지 않는다.
    const values = settings.values ?? {}
    const causes = (strategy.warmupRule?.sum ?? [])
      .filter((term): term is string => typeof term === 'string')
      .flatMap((name): WarmupCause[] => {
        const axis = strategy.axes.find((candidate) => candidate.name === name)
        const value = values[name]
        return axis && typeof value === 'number'
          ? [{ label: axis.label, value, unit: axis.unit }]
          : []
      })
    return {
      window: selectedJudgement.window,
      reduction: selectedJudgement.reduction,
      bindingName: sectorName ?? bindingCandidate,
      warmupTradingDays: settings.warmupTradingDays,
      causes,
    }
  }, [
    settings.selectedStrategy,
    settings.values,
    settings.warmupTradingDays,
    selectedJudgement,
    selection,
    catalog,
  ])

  /**
   * 카드 하나의 판정. **고른 카드만 지금 설정의 워밍업으로 판정한다**(FR-011).
   *
   * <p>다른 카드는 기본 설정 기준이다. 고른 카드까지 기본 설정으로 판정하면 관측 기간을 늘려 평가 구간이
   * 사라져도 카드는 여전히 고를 수 있다고 말하고, 그 아래 안내와 '분석하기'만 막혀 한 화면이 두 말을
   * 한다. 지금 워밍업을 계산할 수 없으면(비워 둔 축이 있으면) 기본 설정으로 대신 판정하지 않고 판정하지
   * 못했다는 사실(`unknown`)을 카드에 보인다 — 대신 판정하면 화면이 하지 않은 판정의 결과를 말한다(원칙 VI).
   *
   * <p><b>값을 만질 때 서버에 묻지 않는다.</b> 판정은 이미 받은 평가 기간으로 화면 안에서 한다 —
   * 값마다 서버를 오가면 늦게 온 응답이 이미 바뀐 값을 판정한다(research R7-1).
   */
  const judgeWarmup = useCallback(
    (strategy: StrategyView): WarmupVerdict => {
      const selected = strategy.strategyId === selectedStrategyId
      // `unknown`은 고를 수 없다는 뜻이 아니다. 카드는 눌린 채 남고 복원하면 지금 설정으로 다시 판정한다.
      if (selected && currentWarmup === null) {
        return { state: 'unknown', message: WARMUP_UNCOMPUTABLE }
      }
      const verdict = support.judgeWarmup({
        strategy,
        evaluationWindow,
        warmupTradingDays: selected && currentWarmup !== null ? currentWarmup : strategy.warmupTradingDays,
        minEvaluationYears,
      }).verdict
      // 고른 카드에 보이는 줄어든 기간 안내는 '분석하기' 위의 안내와 같은 문장이다(SC-004). 따로 지으면 두
      // 곳이 다른 말을 한다.
      if (selected && verdict.state === 'reduced' && reducedPeriod !== null) {
        return { state: 'reduced', message: describeReducedPeriod(reducedPeriod) }
      }
      return verdict
    },
    [support, evaluationWindow, minEvaluationYears, selectedStrategyId, currentWarmup, reducedPeriod],
  )

  // --- 다시 확인할 보관값 -------------------------------------------------------------------
  //
  // **두 자리로 가른다.** 전략에 딸린 값은 그 전략을 고른 뒤에 보여야 뜻이 통하지만, 전략과
  // 무관한 값(분석 조건)은 고르기 전에 알려야 한다 — 최소 평가 기간은 카드의 선택 가능 여부를
  // 바꾸는 값이라(FR-011a) 카드를 보기 전에 그 값이 무엇인지 알아야 한다. 한 자리에 묶어 두면
  // 되돌아간 사실을 전략을 고른 뒤에야 알게 되고, 그것은 사용자 입장에서 조용히 바꾼 것과
  // 같다(FR-017).
  const globalInvalid = settings.invalidValues.filter((entry) => entry.strategyId === null)

  /**
   * 재검증이 비워 둔 시작 금액이 복원을 기다린다.
   *
   * 이 동안은 금액을 **모르는 것**으로 다룬다. 시작 금액이 비면 등록표 기본값이 쓰이므로, 여기서
   * 기본값을 보이거나 보내면 사용자가 정한 적 없는 금액으로 조용히 바뀐 것과 같다(FR-017).
   */
  const amountAwaitsRestore = globalInvalid.some(
    (entry) => entry.axisName === 'initialAmount' && entry.restorable,
  )

  /** 복원 버튼 하나가 전략 축과 분석 조건을 모두 받는다. 되돌릴 자리가 어디인지 항목이 말한다. */
  const restoreInvalidValue = (entry: InvalidStoredValue) => {
    if (entry.axisName === null) return
    if (entry.strategyId !== null) settings.resetAxis(entry.strategyId, entry.axisName)
    else if (entry.axisName === 'initialAmount') settings.resetAnalysisCondition('initialAmount')
  }
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

  /**
   * 고른 전략의 지금 값이 어기는 규칙(FR-008). 상세 설정 패널이 보이는 안내와 **같은 함수**로 판정한다.
   *
   * 값이 바뀔 때마다 다시 계산하며 서버에 묻지 않는다. 판정하지 못한 경우(`unjudged`)를 통과로 접지
   * 않는다 — 접수 시점에 서버가 같은 규칙으로 거절할 요청을 보내게 된다.
   */
  const ruleCheck = useMemo(() => {
    if (!settings.selectedStrategy || !settings.values || universeSize === null) return null
    return checkRules(settings.selectedStrategy.validationRules, settings.values, {
      UNIVERSE_SIZE: universeSize,
    })
  }, [settings.selectedStrategy, settings.values, universeSize])
  const rulesBlocked =
    ruleCheck !== null && (ruleCheck.unjudged || ruleCheck.violations.length > 0)

  /**
   * 고른 전략이 사용자가 정한 비중을 쓰는데 그 합계가 100%가 아니다(T091).
   *
   * <p><b>그 전략에서만 막는다.</b> 합계는 `targetWeightSource: USER`인 전략에만 뜻이 있고, 004
   * 계약이 그 전략의 목표 비중 합을 1로 요구한다. 자동 배분 전략은 이 비중을 읽지 않으므로 같은
   * 선택으로도 분석할 수 있다 — 앞 단계로 통째로 되돌리면 그 사용자까지 막는다. 전략 ID가 아니라
   * 등록표가 내려 준 출처로 가른다(FR-005).
   */
  const unbalancedUserWeights =
    selection.status === 'ready' &&
    settings.selectedStrategy?.targetWeightSource === 'USER' &&
    !selection.weights.isBalanced

  /**
   * 요청에 실을 시작 금액. **화면에 보이는 값과 보내는 값이 같은 자리에서 나온다(T092).**
   *
   * 사용자가 바꾸지 않았으면 등록표의 기본값이다. 요약 줄과 요청 조립이 이 값 하나를 함께 읽어,
   * 한쪽만 바뀌어 보이는 금액과 보내는 금액이 갈라지는 일이 없게 한다. B10(T067)이 상태에 값을
   * 채우면 그 값이 여기로 온다.
   */
  const initialAmount =
    registry === null || amountAwaitsRestore
      ? null
      : (settings.analysisCondition.initialAmount ?? registry.analysisConditions.initialAmount.default)

  /**
   * 분석 기간(FR-009, T067). **요약 줄·패널·요청이 이 값 하나를 함께 읽는다.**
   *
   * <p>사용자가 고르지 않았으면 평가 가능 범위 전체다. 골랐으면 그 기간이다. 범위가 나중에 바뀌어(다른
   * 워밍업, 다른 전략) 고른 기간이 범위를 벗어나도 <b>잘라 맞추지 않는다.</b> 막고 되돌릴 길을 보인다.
   * 잘라 맞추면 사용자가 고르지 않은 기간으로 분석이 나간다(FR-017).
   */
  const evaluableRange = selectedJudgement?.window ?? null
  const { periodStart, periodEnd } = settings.analysisCondition
  const chosenPeriod = useMemo(
    (): AnalysisPeriod | null =>
      periodStart !== undefined && periodEnd !== undefined ? { start: periodStart, end: periodEnd } : null,
    [periodStart, periodEnd],
  )
  const period = chosenPeriod ?? evaluableRange
  const periodOutOfRange =
    chosenPeriod !== null && evaluableRange !== null && !isWithin(chosenPeriod, evaluableRange)
  /**
   * 고른 기간이 최소 평가 기간보다 짧다(FR-011a). 범위 전체가 짧으면 카드 판정이 이미 막으므로, 여기서는
   * 사용자가 범위 안에서 기간을 좁힌 경우만 잡는다.
   *
   * <p><b>고르지 않은 기간(범위 전체)은 여기서 판정하지 않는다.</b> 그 판정은 001 산식을 따르는 카드 판정이
   * 맡는다. 여기서 달력 규약으로 한 번 더 보면 두 규약이 경계에서 갈려, 카드는 고를 수 있다는데 '분석하기'만
   * 막히고 되돌릴 기간도 없는 상태가 생긴다.
   *
   * <p>지금은 화면만 막는다. 004 요청 계약에 최소 평가 기간 자리가 없어 서버 재확인은 합의 뒤다(data-model
   * §10, "착수 전에 알아둘 것" ⑧).
   */
  const periodTooShort =
    chosenPeriod !== null &&
    Number.isInteger(minEvaluationYears) &&
    !meetsMinimumYears(chosenPeriod, minEvaluationYears)
  const minEvaluationYearsLabel =
    registry?.analysisConditions.minEvaluationYears.choices.find(
      (choice) => Number(choice.value) === minEvaluationYears,
    )?.label ?? String(minEvaluationYears)

  /** 고른 전략의 규칙 버전. 버전을 받지 못했거나 전략을 고르지 않았으면 `null`이다 */
  const rulesVersion = useMemo(() => {
    if (versions === 'loading' || versions === null || !settings.selectedStrategy) return null
    return resolveRulesVersion(versions, settings.selectedStrategy.strategyId)
  }, [versions, settings.selectedStrategy])

  const prepared = useMemo(() => {
    if (
      selection.status !== 'ready' ||
      registry === null ||
      initialAmount === null ||
      unbalancedUserWeights ||
      ruleCheck === null ||
      rulesBlocked ||
      !settings.ready ||
      settings.registryVersion === null ||
      !settings.selectedStrategy ||
      !settings.values ||
      !settings.isComplete(settings.selectedStrategy.strategyId) ||
      settings.warmupTradingDays === null ||
      !selectedJudgement?.window ||
      period === null ||
      periodOutOfRange ||
      periodTooShort ||
      versions === 'loading' ||
      versions === null ||
      rulesVersion?.status !== 'registered'
    ) {
      return null
    }
    return {
      selection: selection.selection,
      strategy: settings.selectedStrategy,
      parameters: settings.values,
      registryVersion: settings.registryVersion,
      warmupTradingDays: settings.warmupTradingDays,
      period,
      initialAmount,
      rulesVersion: rulesVersion.rulesVersion,
      costProfileVersion: versions.costProfileVersion,
    }
  }, [
    selection,
    registry,
    initialAmount,
    unbalancedUserWeights,
    ruleCheck,
    rulesBlocked,
    settings,
    selectedJudgement,
    period,
    periodOutOfRange,
    periodTooShort,
    versions,
    rulesVersion,
  ])

  const analyzeBlocker = ((): string | null => {
    if (registry === null || !settings.ready) return REGISTRY_NOT_LOADED
    const strategy = settings.selectedStrategy
    if (!strategy) return SELECT_STRATEGY_FIRST
    if (!strategy.selectable) return strategy.unselectableReason ?? REASON_MISSING
    if (settingsIncomplete || amountAwaitsRestore) return INVALID_STORED_VALUES
    if (ruleCheck?.unjudged) return RULES_UNJUDGED
    if (ruleCheck && ruleCheck.violations.length > 0) return RULES_VIOLATED
    if (unbalancedUserWeights && selection.status === 'ready') {
      return weightsUnbalancedMessage(selection.weights.totalTenths)
    }
    if (settings.warmupTradingDays === null) return WARMUP_UNCOMPUTABLE
    if (!selectedJudgement) return EVALUATION_WINDOW_UNAVAILABLE
    const verdict = selectedJudgement.verdict
    if (verdict.state === 'blocked' || verdict.state === 'unknown') return verdict.message
    if (!selectedJudgement.window) return EVALUATION_WINDOW_UNAVAILABLE
    if (periodOutOfRange) return periodOutOfRangeMessage(selectedJudgement.window)
    if (periodTooShort) return periodTooShortMessage(minEvaluationYearsLabel)
    if (versions === 'loading') return VERSIONS_LOADING
    if (versions === null) return ANALYSIS_VERSIONS_UNAVAILABLE
    if (rulesVersion?.status === 'pendingDecision') return RULES_PENDING_DECISION
    if (rulesVersion?.status !== 'registered') return RULES_VERSION_UNAVAILABLE
    return null
  })()

  /**
   * 설정 조합 자동 비교로 넘어갈 수 있는가(FR-013).
   *
   * <p><b>'분석하기'보다 묻는 것이 적다.</b> 넘기는 것은 전략 하나와 섹터 후보 집합뿐이고, 설정값·
   * 평가 기간·분석 조건 버전은 007이 확인 화면에서 스스로 정하고 검증한다. 여기서 그것까지 물으면
   * 007이 판정할 일을 이 화면이 미리 막는 셈이 된다. 섹터 선택은 이 버튼이 그려지는 조건
   * (`selection.status === 'ready'`)이 이미 보장한다.
   */
  const autoCompareBlocker = ((): string | null => {
    if (registry === null || !settings.ready) return REGISTRY_NOT_LOADED
    const strategy = settings.selectedStrategy
    if (!strategy) return SELECT_STRATEGY_FIRST
    if (!strategy.selectable) return strategy.unselectableReason ?? REASON_MISSING
    return null
  })()

  /**
   * 007 실행 전 확인 화면으로 넘어간다.
   *
   * <p><b>전략은 하나만 싣는다.</b> 007은 여러 전략을 받을 수 있지만 003은 방금 고른 전략 하나를
   * 넘기기로 정했다(FR-013, Clarifications). 질의 문자열 모양은 007 `ParamSearchPage`가 이미 읽는
   * `strategies`·`instruments` 그대로이며, 후보 숫자나 설정값은 싣지 않는다 — 탐색 범위는 007이
   * 등록표에서 정한다.
   */
  const handleAutoCompare = () => {
    if (autoCompareBlocker !== null || selection.status !== 'ready' || !settings.selectedStrategy) {
      return
    }
    const instrumentIds = selection.selection.items.map((item) => encodeURIComponent(item.instrumentId))
    navigate(
      `/param-search?strategies=${encodeURIComponent(settings.selectedStrategy.strategyId)}` +
        `&instruments=${instrumentIds.join(',')}`,
    )
  }

  // --- 요청 조립과 전송 --------------------------------------------------------------------
  const [submission, setSubmission] = useState<Submission>({ status: 'idle' })

  /**
   * 마지막 요청이 등록표 버전 변경(409)으로 거절되었다면 **그때 화면이 쓰던 등록표**다(research R9,
   * quickstart 10). 거절되지 않았으면 `null`이다.
   *
   * <p>참이면 등록표를 다시 받고 그 결과를 알린다. 다음 '분석하기'를 누를 때 거둔다 — 그 전에 거두면
   * 사용자는 왜 요청이 접수되지 않았는지 모른다.
   *
   * <p><b>옛 등록표를 함께 붙들어 두는 까닭</b>: 409 직후 첫 렌더에는 다시 조회가 아직 시작되지 않아 옛
   * 등록표가 '받음' 상태로 남아 있다. 받았는지만 보고 판정하면, 새 등록표를 보지도 않고 "그대로 두었습니다"를
   * 알린다. 그래서 옛 등록표와 **다른** 등록표를 받은 뒤에만 다시 받았다고 판정한다.
   */
  const [registryChange, setRegistryChange] = useState<{ stale: RegistryResponse } | null>(null)
  const registryChanged = registryChange !== null
  const { clearSelection } = settings

  /**
   * 지금 고른 전략. 요청을 보낸 뒤 사용자가 카드를 옮겼는지를 응답이 온 시점에 묻기 위한 것이다 —
   * `handleAnalyze`가 잡아 둔 값은 누른 시점의 것이라 그 사이의 선택을 모른다.
   */
  const selectedStrategyIdRef = useRef(settings.selectedStrategyId)
  useEffect(() => {
    selectedStrategyIdRef.current = settings.selectedStrategyId
  }, [settings.selectedStrategyId])

  const handleSelect = useCallback(
    (strategyId: string) => {
      settings.select(strategyId)
      setSubmission({ status: 'idle' })
    },
    [settings],
  )

  const handleAnalyze = useCallback(async () => {
    if (analyzeBlocker !== null || prepared === null || submission.status === 'sending') return

    // 값을 만들지 않는다. 시작 금액은 요약 줄에 보인 값을 그대로 싣는다.
    //
    // **조립도 실패를 알리는 길 안에 둔다.** 조립은 금액·비중이 계약 모양으로 옮겨지지 않으면 예외를
    // 던지는데, 앞서는 이것이 `try` 밖이라 거부된 프로미스로 버려졌다. 버튼을 눌러도 진행 표시도
    // 실패 안내도 없이 아무 일도 일어나지 않았다.
    //
    // 조립 실패는 서버가 한 말이 아니라 이 화면의 값이 계약에 맞지 않는다는 뜻이라, 예외 문장을
    // 그대로 보이지 않고 사용자가 할 수 있는 일을 말한다. 원인은 개발 도구에서 볼 수 있게 남긴다.
    let request
    try {
      request = buildBacktestRequest({
        selection: prepared.selection,
        strategy: prepared.strategy,
        parameters: prepared.parameters,
        registryVersion: prepared.registryVersion,
        warmupTradingDays: prepared.warmupTradingDays,
        // 비용 버전은 화면에 보인 기본 비용 조건의 버전을 그대로 싣는다. 비워 보내면 조회와 접수 사이에 서버
        // 기본값이 바뀌었을 때 사용자가 본 적 없는 조건이 적용된다(004 결정, T105).
        versions: {
          rulesVersion: prepared.rulesVersion,
          costProfileVersion: prepared.costProfileVersion,
        },
        period: prepared.period,
        initialAmount: prepared.initialAmount,
        scenario: settings.analysisCondition.scenario,
      })
    } catch (error) {
      console.error('분석 요청을 조립하지 못했습니다.', error)
      setSubmission({ status: 'failed', message: REQUEST_BUILD_FAILED })
      return
    }

    setSubmission({ status: 'sending' })
    setRegistryChange(null)
    try {
      const accepted = await support.createBacktest(request)
      setSubmission({ status: 'accepted', accepted })
    } catch (error) {
      // 거절마다 계약 §2가 정한 처리를 한다. **어느 경우에도 요청을 다시 보내지 않는다** — 다시 보낼지는
      // 사용자가 바뀐 내용을 보고 정한다.
      const serverMessage = error instanceof Error && error.message ? error.message : SUBMIT_FAILED
      const rejection = readBacktestRejection(error)
      switch (rejection.code) {
        case BacktestRejectionCode.REGISTRY_VERSION_CHANGED:
          // 다시 받아 보관값을 새 등록표로 재검증한다(`useStrategySettings`가 등록표를 받을 때마다 한다).
          //
          // **004 규칙 등록표도 함께 다시 받는다.** 같은 409가 003 등록표 버전의 불일치에도, 004가 대체한 옛
          // 규칙 버전에도 오고 화면은 둘을 가를 수 없다(004 결정). 둘 다 받은 뒤에 사용자가 다시 누른다.
          setSubmission({ status: 'idle' })
          if (registry !== null) setRegistryChange({ stale: registry })
          reloadRegistry()
          setVersions('loading')
          setVersionsReload((count) => count + 1)
          return
        case BacktestRejectionCode.STRATEGY_NOT_REGISTERED:
          // 등록표는 늘 다시 받는다. 보낸 전략이 목록에서 빠졌는지는 사용자가 카드를 옮겼어도 보여야 한다.
          reloadRegistry()
          // **보낸 전략을 아직 고르고 있을 때만** 전략 선택으로 되돌리고 알린다. 보내는 동안 다른 카드를
          // 골랐다면 그 선택을 풀거나 옛 전략의 거절로 덮지 않는다 — 새로 고른 전략의 이야기가 아니다.
          // 전략별로 바꿔 둔 값은 어느 경우에도 지우지 않는다.
          if (selectedStrategyIdRef.current !== prepared.strategy.strategyId) return
          clearSelection()
          setSubmission({ status: 'failed', message: STRATEGY_NOT_REGISTERED_MESSAGE })
          return
        case BacktestRejectionCode.UNREGISTERED_PARAMETER_VALUE:
          setSubmission({
            status: 'failed',
            message: parameterRejectedMessage(rejection, prepared.strategy),
          })
          return
        case BacktestRejectionCode.REGISTRY_UNAVAILABLE:
          // 서버 사정이라 같은 설정으로 다시 보낼 수 있다. 제안만 하고 보내는 것은 사용자가 누를 때다.
          setSubmission({ status: 'failed', message: serverMessage, retryable: true })
          return
        default:
          // 422 `VALIDATION_RULE_VIOLATED`는 서버가 규칙의 `detail`을 메시지로 싣는다. 그대로 보인다.
          setSubmission({ status: 'failed', message: serverMessage })
      }
    }
  }, [
    analyzeBlocker,
    prepared,
    submission.status,
    settings.analysisCondition,
    support,
    reloadRegistry,
    clearSelection,
    registry,
  ])

  // --- 화면 -----------------------------------------------------------------------------
  const selectedId = settings.selectedStrategyId

  /** 등록표를 다시 받는 과정의 어디쯤인가. 받았어도 보관값 재검증을 마치기 전이면 아직 받는 중이다. */
  const registryReloadPhase: RegistryReloadPhase =
    registryState.status === 'failed'
      ? registryState.retrying
        ? 'reloading'
        : 'failed'
      : registryState.status === 'loaded' &&
          registryState.registry !== registryChange?.stale &&
          settings.ready &&
          versions !== 'loading'
        ? 'reloaded'
        : 'reloading'

  /** 002 비중을 이름과 함께 읽기 전용 표로. 이름은 섹터 목록에서, 비중은 002가 확정한 값에서 온다. */
  const targetWeights = useMemo((): TargetWeightRow[] | null => {
    if (selection.status !== 'ready' || catalog?.status !== 'loaded') return null
    const names = new Map(catalog.catalog.sectors.map((sector) => [sector.code, sector.name]))
    return selection.selection.items.map((item) => ({
      sectorId: item.sectorId,
      name: names.get(item.sectorId) ?? item.sectorId,
      weightTenths: item.weightTenths,
    }))
  }, [selection, catalog])
  // 조회 중과 조회 실패를 가른다. 409 뒤 다시 받는 동안을 "확인할 수 없음"으로 보이면 곧 끝날 조회가 실패로
  // 읽히고, 같은 화면의 '분석하기' 사유("확인하는 중")와 두 말을 하게 된다.
  const costProfile =
    versions === 'loading'
      ? ('loading' as const)
      : versions !== null
        ? { version: versions.costProfileVersion, provisional: versions.costProfileProvisional }
        : null
  const costAssumption =
    versions === 'loading'
      ? ('loading' as const)
      : versions !== null
        ? {
            version: versions.costProfileVersion,
            provisional: versions.costProfileProvisional,
            rates: versions.costRates,
          }
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
            message={
              // 대응표가 바뀐 경우는 "마치지 않았다"와 까닭이 달라, 같은 문장으로 알리면 사용자가
              // 이미 고른 섹터를 왜 다시 골라야 하는지 모른다.
              selection.problem === 'mapVersionChanged'
                ? '섹터를 고른 뒤 섹터 대응표가 바뀌었습니다. 섹터·비중 단계에서 선택을 다시 확인해 주세요.'
                : '섹터·비중 단계를 먼저 마쳐 주세요. 비교하려면 섹터를 두 개 이상 골라야 합니다.'
            }
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
                onRestore={restoreInvalidValue}
              />
            ) : null}

            <section
              ref={strategyCardsRef}
              className={styles.section}
              aria-labelledby="strategy-cards-title"
              tabIndex={-1}
            >
              <h2 id="strategy-cards-title" className={styles.sectionTitle}>
                전략 카드
              </h2>
              {/*
                평가 기간을 받지 못하면 카드마다 판정하지 못했다는 문장이 붙는다. 그 상태는 자료가 적재되는 중
                (409 EVALUATION_WINDOW_NOT_READY)이거나 잠깐의 연결 오류일 수 있어 다시 불러 달라질 수 있으므로,
                등록표 조회와 같이 다시 시도할 길을 둔다(FR-018과 같은 규약). 다시 받는 동안에는 안내를 거두지
                않아 읽히는 문장이 깜빡이지 않는다.
              */}
              {evaluationWindow.status === 'unavailable' ? (
                <div className={styles.notice} role="status">
                  <p>공통 평가 기간을 불러오지 못해 카드의 워밍업을 판정하지 못했습니다.</p>
                  <button
                    type="button"
                    onClick={retryEvaluationWindow}
                    disabled={evaluationWindowRetrying}
                  >
                    {evaluationWindowRetrying ? '다시 불러오는 중' : '평가 기간 다시 불러오기'}
                  </button>
                </div>
              ) : null}
              <StrategyCardList
                state={registryState}
                selectedStrategyId={selectedId}
                onSelect={handleSelect}
                onRetry={reloadRegistry}
                judgeWarmup={judgeWarmup}
                selectedContent={
                  settings.ready && settings.selectedStrategy && selectedId !== null ? (
                    <section
                      className={`${styles.section} ${styles.selectionPanel}`}
                      aria-labelledby="selected-strategy-title"
                    >
                      <h2 id="selected-strategy-title" className={styles.sectionTitle}>
                        선택한 전략: {settings.selectedStrategy.name}
                      </h2>

                      <SettingsStatusBadge isDefault={settings.isDefault(selectedId)} />

                      <ConditionSummaryLine
                        period={period}
                        sectorCount={universeSize ?? 0}
                        initialAmount={
                          initialAmount === null
                            ? null
                            : {
                                value: initialAmount,
                                unit: registry?.analysisConditions.initialAmount.unit,
                              }
                        }
                        costProfile={costProfile}
                      />

                      {unbalancedUserWeights && selection.status === 'ready' ? (
                        <div className={styles.weightNotice} role="status">
                          <p>{weightsUnbalancedMessage(selection.weights.totalTenths)}</p>
                          <button type="button" onClick={() => navigate('/diagnosis/sectors')}>
                            섹터·비중 단계로 이동
                          </button>
                        </div>
                      ) : null}

                      {strategyInvalid.length > 0 ? (
                        <InvalidValueList
                          label="다시 확인할 설정값"
                          entries={strategyInvalid}
                          onRestore={restoreInvalidValue}
                        />
                      ) : null}

                      {selectedJudgement &&
                      (selectedJudgement.verdict.state === 'blocked' ||
                        selectedJudgement.verdict.state === 'unknown') ? (
                        <p
                          className={styles.judgement}
                          role="status"
                          data-state={selectedJudgement.verdict.state}
                        >
                          {selectedJudgement.verdict.message}
                        </p>
                      ) : null}

                      {periodOutOfRange || periodTooShort ? (
                        <div className={styles.weightNotice} role="status">
                          <p>
                            {periodOutOfRange && evaluableRange
                              ? periodOutOfRangeMessage(evaluableRange)
                              : periodTooShortMessage(minEvaluationYearsLabel)}
                          </p>
                          {chosenPeriod !== null ? (
                            <button
                              type="button"
                              onClick={() => settings.resetAnalysisCondition('period')}
                            >
                              범위 전체로 되돌리기
                            </button>
                          ) : null}
                        </div>
                      ) : null}

                      <ReducedPeriodNotice
                        input={reducedPeriod}
                        fallbackMessage={
                          selectedJudgement?.verdict.state === 'reduced'
                            ? selectedJudgement.verdict.message
                            : undefined
                        }
                      />

                      <ActionButtons
                        analyze={{
                          disabled: analyzeBlocker !== null,
                          reason: analyzeBlocker ?? undefined,
                          busy: submission.status === 'sending',
                        }}
                        autoCompare={{
                          disabled: autoCompareBlocker !== null,
                          reason: autoCompareBlocker ?? undefined,
                        }}
                        onAnalyze={() => {
                          void handleAnalyze()
                        }}
                        onAutoCompare={handleAutoCompare}
                      />

                      {settings.values !== null && universeSize !== null ? (
                        <DetailSettingsPanel
                          strategy={settings.selectedStrategy}
                          values={settings.values}
                          universeSize={universeSize}
                          targetWeights={targetWeights}
                          onChange={(axisName, value) =>
                            settings.setValue(selectedId, axisName, value)
                          }
                          onResetAxis={(axisName) => settings.resetAxis(selectedId, axisName)}
                          onResetAll={() => settings.resetAll(selectedId)}
                        />
                      ) : null}
                    </section>
                  ) : undefined
                }
              />
            </section>

            {/*
              전략을 고르기 전에도 닿는 자리에 둔다(T102). 최소 평가 기간은 카드의 선택 가능 여부를 바꾸는
              값이라, 그 값이 모든 카드를 막으면 선택에 묶인 입력으로는 되돌릴 길이 없다(FR-011a). 접힌 채로
              시작해 기본 화면의 입력을 늘리지 않는다(SC-005). 기간은 고른 전략의 평가 가능 범위가 있어야
              정할 수 있으므로 그 전에는 막고 까닭을 적는다.
            */}
            {registry !== null && settings.ready ? (
              <AnalysisConditionsPanel
                conditions={registry.analysisConditions}
                range={evaluableRange}
                rangeUnavailableMessage={settings.selectedStrategy ? undefined : RANGE_NEEDS_STRATEGY}
                period={period}
                periodChosen={chosenPeriod !== null}
                initialAmount={initialAmount ?? undefined}
                minEvaluationYears={minEvaluationYears}
                cost={costAssumption}
                onChange={(patch) => {
                  // 금액이 등록표 기본값과 같아지면 값을 담지 않고 "없음"으로 둔다. 입력이 무효가 되어
                  // 입력칸이 보이던 기본값으로 되돌릴 때도 이 길로 온다. 기본값을 사용자 값으로 담으면
                  // "없으면 기본값"이 깨져, 등록표 기본값이 바뀌어도 옛 기본값이 사용자가 고른 것처럼 남는다.
                  if (patch.initialAmount === registry.analysisConditions.initialAmount.default) {
                    const rest = { ...patch }
                    delete rest.initialAmount
                    settings.resetAnalysisCondition('initialAmount')
                    if (Object.keys(rest).length > 0) settings.setAnalysisCondition(rest)
                    return
                  }
                  settings.setAnalysisCondition(patch)
                }}
                onReset={settings.resetAnalysisCondition}
              />
            ) : null}

            {!settings.selectedStrategy ? (
              <ActionButtons
                analyze={{
                  disabled: analyzeBlocker !== null,
                  reason: analyzeBlocker ?? undefined,
                  busy: submission.status === 'sending',
                }}
                autoCompare={{
                  disabled: autoCompareBlocker !== null,
                  reason: autoCompareBlocker ?? undefined,
                }}
                onAnalyze={() => {
                  void handleAnalyze()
                }}
                onAutoCompare={handleAutoCompare}
              />
            ) : null}

            {versions === null || versionsRetrying ? (
              <div className={styles.notice} role="status" aria-label="분석 조건 조회 상태">
                <p>
                  {versionsRetrying
                    ? '분석 조건을 다시 불러오는 중입니다.'
                    : '분석 조건을 불러오지 못했습니다. 다시 불러온 뒤 분석할 수 있습니다.'}
                </p>
                <button
                  ref={versionsRetryButtonRef}
                  type="button"
                  disabled={versionsRetrying}
                  onClick={() => {
                    versionsRetryRequested.current = true
                    setVersionsRetrying(true)
                    setVersions('loading')
                    setVersionsReload((count) => count + 1)
                  }}
                >
                  분석 조건 다시 불러오기
                </button>
              </div>
            ) : null}
            {registryChanged ? (
              <RegistryChangedNotice
                phase={registryReloadPhase}
                pendingRestoreCount={
                  [...globalInvalid, ...strategyInvalid].filter((entry) => entry.restorable).length
                }
                removedMessages={settings.invalidValues
                  .filter((entry) => entry.strategyId !== null && entry.axisName === null)
                  .map((entry) => entry.message)}
              />
            ) : null}
            {submission.status === 'accepted' ? (
              <p className={styles.result} role="status">
                분석 요청을 접수했습니다. 요청 번호 {submission.accepted.backtestId}, 상태{' '}
                {statusText[submission.accepted.status] ?? submission.accepted.status}. 결과 화면으로의
                이동은 아직 준비 중입니다.
              </p>
            ) : null}
            {submission.status === 'failed' ? (
              <div className={styles.error} role="alert">
                <p className={styles.errorMessage}>{submission.message}</p>
                {submission.retryable ? (
                  <button
                    type="button"
                    className={styles.restoreButton}
                    disabled={analyzeBlocker !== null}
                    onClick={() => {
                      void handleAnalyze()
                    }}
                  >
                    다시 시도
                  </button>
                ) : null}
              </div>
            ) : null}
          </>
        ) : null}
      </main>
    </div>
  )
}

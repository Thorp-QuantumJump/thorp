import { useEffect, useId, useState } from 'react'
import { fetchAnalysisVersions } from '@/api/analysisVersions'
import { fetchUsedStrategies } from '@/api/myRecords'
import { fetchEvaluationWindow } from '@/api/sectors'
import { fetchStrategyRegistry } from '@/api/strategyRegistry'
import { useAuth } from '@/auth/auth-context'
import { InlineHelp } from '@/components/common/InlineHelp'
import { TARGET_RISK_HELP } from '@/components/strategy/AnalysisConditionsPanel'
import { resolveRulesVersion } from '@/components/strategy/analysisSupport'
import { Button } from '@/components/ui/button'
import type { AxisValues } from '@/hooks/useStrategySettings'
import type { UsedStrategy } from '@/types/myRecords'
import type { PublicResultDetail } from '@/types/result'
import type { NumericCondition } from '@/types/strategyRegistry'
import { type CandidateSource, combinationsFor, describeParameters } from './compareCandidates'
import type { CompareChoice } from './compareRequest'
import styles from './comparison.module.css'

type Candidate = {
  key: string
  choice: CompareChoice
  /** `volatility`는 기준 전략 그대로 변동성 제한만 바꿔 보는 한 줄이다 */
  source: CandidateSource | 'volatility'
  /** 내 전략의 이름표(내 기록 항목 이름). 없으면 `null` */
  recordName: string | null
  /** 설정값 한 줄. 축이 없는 전략은 빈 문자열 */
  settings: string
  reason: string | null
  shortenedDays: number | null
}

/** 후보 묶음 이름. 출처별로 묶는다 — 기본 전략(등록표 기본값)과 내 전략(내가 돌려 본 조합). */
const SOURCE_LABELS: Record<CandidateSource, string> = {
  default: '기본 전략',
  used: '내 전략',
}
const SOURCE_ORDER: readonly CandidateSource[] = ['default', 'used']
const VOLATILITY_KEY = 'volatility'

/** 등록표 범위(min~max, step 간격)의 후보 */
function rangeChoices({ min, max, step }: NumericCondition): number[] {
  const count = Math.round((max - min) / step)
  return Array.from({ length: count + 1 }, (_, i) => Number((min + i * step).toFixed(4)))
}

/**
 * 비교 대상 고르기(FR-004, `S15P21E205-419`).
 *
 * <p><b>후보는 전략이 아니라 전략·설정값 조합이다.</b> 등록표 기본값 조합과, 로그인했으면 내가 써 본 조합
 * (`GET /backtests/used-strategies`)을 '기본 전략'·'내 전략' 두 묶음으로 보인다. 조합마다 쓰일 설정값을 적어 무엇과 비교하는지 보이게 한다.
 * 기준 전략 줄은 전략 이름만 적고, 내 전략 줄은 내 기록 항목 이름 아래에 바탕 전략을 작게 적는다. 설정값은 접근 가능한 이름과 도움말에
 * 남긴다. 기준 결과 자신과 같은 조합은 빼고, 같은 전략이라도 설정값이 다르면 후보다.
 *
 * <p><b>여럿을 골라 한 번에 요청한다.</b> 남은 자리(`room`)만큼 고를 수 있고, 준비 기간이 기준보다 긴 후보는 고르기 전에
 * 비교 기간이 짧아진다는 사실을 적는다(FR-004). 화면은 등록표 값을 읽기만 하고 준비 기간을 계산하지 않는다.
 *
 * <p>비로그인이면 써 본 조합을 부르지 않는다 — 401 을 받아 넘기는 대신 기본값만 후보로 둔다.
 */
export function CompareTargetPicker({
  base,
  room,
  onSelect,
  onClose,
}: {
  base: PublicResultDetail
  /** 더 고를 수 있는 자리 수 */
  room: number
  onSelect: (choices: CompareChoice[]) => void
  onClose: () => void
}) {
  const { status: authStatus } = useAuth()
  const authenticated = authStatus === 'authenticated'
  const [state, setState] = useState<{
    candidates: Candidate[]
    usedUnavailable: boolean
    error: string | null
    /** 변동성 제한 줄의 목표 범위. 기준이 이미 제한을 켰으면 고를 값이 없어 `null`이다 */
    volatility: NumericCondition | null
  } | null>(null)
  const [volatilityPick, setVolatilityPick] = useState<number | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [picked, setPicked] = useState<string[]>([])
  const listId = useId()

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    const load = async () => {
      try {
        const [registry, versions, used] = await Promise.all([
          fetchStrategyRegistry(base.config.items.length, controller.signal),
          fetchAnalysisVersions(controller.signal),
          authenticated
            ? fetchUsedStrategies(controller.signal).then(
                (items): { items: UsedStrategy[]; failed: boolean } => ({ items, failed: false }),
                () => ({ items: [], failed: true }),
              )
            : Promise.resolve({ items: [], failed: false }),
        ])
        if (!versions) throw new Error('전략 규칙 버전을 확인할 수 없습니다.')
        const maxWarmup = Math.max(
          base.config.warmupTradingDays,
          ...registry.strategies.map((strategy) => strategy.warmupTradingDays),
        )
        const window =
          maxWarmup > base.config.warmupTradingDays
            ? await fetchEvaluationWindow(
                {
                  sectors: base.config.items.map((item) => item.sectorId),
                  maxWarmupTradingDays: maxWarmup,
                },
                controller.signal,
              ).catch(() => null)
            : null
        const candidates = registry.strategies.flatMap((strategy): Candidate[] => {
          const rules = resolveRulesVersion(versions, strategy.strategyId)
          const longer = strategy.warmupTradingDays > base.config.warmupTradingDays
          const firstDay = longer ? window?.tradingDaysFromBase[strategy.warmupTradingDays] : null
          const start =
            firstDay && firstDay > base.config.effectiveStart ? firstDay : base.config.effectiveStart
          const end =
            window && window.commonEnd < base.config.effectiveEnd
              ? window.commonEnd
              : base.config.effectiveEnd
          const reason = !strategy.selectable
            ? (strategy.unselectableReason ?? '현재 조건에서는 고를 수 없습니다.')
            : rules.status !== 'registered'
              ? '전략의 계산 규칙을 확인할 수 없습니다.'
              : !base.versions.costProfile
                ? '기준 결과의 비용 기준을 확인할 수 없습니다.'
                : strategy.targetWeightSource === 'USER' &&
                    base.config.items.some((item) => item.targetWeight == null)
                  ? '입력한 목표 비중이 없습니다. 처음부터 다시 분석에서 비중을 먼저 정해 주세요.'
                  : longer && (!firstDay || start > end)
                    ? '이 전략의 공통 평가 기간을 확인할 수 없습니다.'
                    : null
          const shortenedDays =
            longer && firstDay && window
              ? window.tradingDaysFromBase.filter(
                  (date) => date >= base.config.effectiveStart && date < start,
                ).length
              : null
          return combinationsFor(strategy, used.items, {
            strategyId: base.config.strategyId,
            parameters: base.config.parameters,
          }).map((combination, index) => ({
            key: strategy.strategyId + '#' + index,
            source: combination.source,
            recordName: combination.recordName,
            settings: describeParameters(strategy, combination.parameters),
            reason,
            shortenedDays,
            choice: {
              strategy,
              parameters: combination.parameters,
              registryVersion: registry.registryVersion,
              rulesVersion: rules.status === 'registered' ? rules.rulesVersion : '',
              ...(longer && firstDay ? { period: { start, end } } : {}),
            },
          }))
        })
        // 변동성 제한 줄: 기준 전략·설정·규칙 버전 그대로, 제한만 켜거나 끈다. 다른 분석 방식(새 자금 투입 등)에는 두지 않는다.
        const baseStrategy = registry.strategies.find(
          (strategy) => strategy.strategyId === base.config.strategyId,
        )
        const limited = base.config.scenario === 'TARGET_RISK'
        const volatility = registry.analysisConditions.targetVolatility
        if (baseStrategy && (limited || base.config.scenario === 'FULL_PERIOD')) {
          candidates.push({
            key: VOLATILITY_KEY,
            source: 'volatility',
            recordName: null,
            settings: '',
            reason: base.versions.costProfile ? null : '기준 결과의 비용 기준을 확인할 수 없습니다.',
            shortenedDays: null,
            choice: {
              strategy: baseStrategy,
              parameters: { ...base.config.parameters } as AxisValues,
              registryVersion: base.config.registryVersion ?? registry.registryVersion,
              rulesVersion: base.config.rulesVersion,
              volatilityLimit: limited
                ? { scenario: 'FULL_PERIOD' }
                : { scenario: 'TARGET_RISK', targetVolatility: volatility.default },
            },
          })
        }
        if (active) {
          setState({
            candidates,
            usedUnavailable: used.failed,
            error: null,
            volatility: limited ? null : volatility,
          })
          setVolatilityPick(limited ? null : volatility.default)
        }
      } catch (error) {
        if (active)
          setState({
            candidates: [],
            usedUnavailable: false,
            error: error instanceof Error ? error.message : '비교 전략을 불러오지 못했습니다.',
            volatility: null,
          })
      }
    }
    setState(null)
    setPicked([])
    void load()
    return () => {
      active = false
      controller.abort()
    }
  }, [base, attempt, authenticated])

  const toggle = (key: string) =>
    setPicked((known) =>
      known.includes(key) ? known.filter((item) => item !== key) : [...known, key],
    )
  const full = picked.length >= room
  const submit = () => {
    const chosen = (state?.candidates ?? []).filter((candidate) => picked.includes(candidate.key))
    if (chosen.length === 0) return
    onSelect(
      chosen.map(({ choice }) =>
        choice.volatilityLimit?.scenario === 'TARGET_RISK' && volatilityPick !== null
          ? { ...choice, volatilityLimit: { scenario: 'TARGET_RISK', targetVolatility: volatilityPick } }
          : choice,
      ),
    )
  }

  // 출처별로 묶어 보인다. 기본 전략 묶음에는 전략마다 한 줄, 내 전략 묶음에는 돌려 본 조합마다 한 줄이다.
  const groups = SOURCE_ORDER.map((source) => ({
    source,
    candidates: (state?.candidates ?? []).filter((candidate) => candidate.source === source),
  })).filter((group) => group.candidates.length > 0)
  const volatilityRow = state?.candidates.find((candidate) => candidate.source === 'volatility')
  const volatilityName = state?.volatility ? '변동성 제한 걸기' : '변동성 제한 없이'

  return (
    <section className={styles.panel} aria-label="비교 전략 선택">
      <h2>비교 대상 고르기</h2>
      {!state ? <p role="status">비교할 전략을 불러오는 중입니다.</p> : null}
      {state?.error ? (
        <div role="alert">
          <p>{state.error}</p>
          <Button variant="outline" onClick={() => setAttempt((value) => value + 1)}>
            목록 다시 불러오기
          </Button>
        </div>
      ) : null}
      {state && !state.error ? (
        <p id={listId} className={styles.pickerNote}>
          {room > 0
            ? '최대 ' + room + '개까지 고를 수 있습니다. 기본 전략은 등록표의 기본 설정이고, 내 전략은 이전에 돌려 본 설정입니다.'
            : '비교 대상을 더 고를 자리가 없습니다. 하나를 해제한 뒤 고르세요.'}
          {state.usedUnavailable ? ' 내 전략 목록은 지금 불러오지 못했습니다.' : ''}
        </p>
      ) : null}
      <ul className={styles.candidates} aria-describedby={listId}>
        {groups.map(({ source, candidates }) => (
          <li key={source} className={styles.group}>
            <p className={styles.groupTitle}>{SOURCE_LABELS[source]}</p>
            <ul className={styles.combinations}>
              {candidates.map((candidate) => {
                const checked = picked.includes(candidate.key)
                const disabled = !!candidate.reason || room === 0 || (full && !checked)
                // 기본 전략은 전략 이름만, 내 전략은 기록 이름(없으면 설정값)과 그 아래 작은 글씨의 바탕 전략.
                const title =
                  source === 'used'
                    ? (candidate.recordName ?? candidate.settings) || candidate.choice.strategy.name
                    : candidate.choice.strategy.name
                const name =
                  SOURCE_LABELS[source] +
                  ' · ' +
                  title +
                  (source === 'used' ? ' · ' + candidate.choice.strategy.name : '') +
                  (candidate.settings ? ' · ' + candidate.settings : '')
                return (
                  <li key={candidate.key}>
                    <label className={styles.combination}>
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={disabled}
                        aria-label={name}
                        aria-describedby={candidate.key + '-hint'}
                        onChange={() => toggle(candidate.key)}
                      />
                      <span className={styles.rowText}>
                        <span className={styles.rowTitle}>{title}</span>
                        {source === 'used' ? (
                          <span className={styles.rowBase}>{candidate.choice.strategy.name}</span>
                        ) : null}
                      </span>
                    </label>
                    <p id={candidate.key + '-hint'}>
                      {candidate.reason ??
                        (candidate.shortenedDays !== null && candidate.shortenedDays > 0
                          ? '이 전략을 고르면 비교 기간이 ' +
                            candidate.shortenedDays +
                            '거래일 짧아집니다.'
                          : candidate.choice.strategy.warmupTradingDays >
                              base.config.warmupTradingDays
                            ? '준비 기간을 맞춰 결과를 다시 계산합니다.'
                            : candidate.source === 'default'
                              ? candidate.choice.strategy.description
                              : '')}
                    </p>
                  </li>
                )
              })}
            </ul>
          </li>
        ))}
        {volatilityRow ? (
          <li className={styles.group}>
            <p className={styles.groupTitle}>
              변동성 제한{' '}
              <InlineHelp label="변동성 제한" side="bottom">
                {TARGET_RISK_HELP}
              </InlineHelp>
            </p>
            <ul className={styles.combinations}>
              <li>
                <label className={styles.combination}>
                  <input
                    type="checkbox"
                    checked={picked.includes(VOLATILITY_KEY)}
                    disabled={
                      !!volatilityRow.reason ||
                      room === 0 ||
                      (full && !picked.includes(VOLATILITY_KEY))
                    }
                    aria-label={volatilityName + ' · ' + volatilityRow.choice.strategy.name}
                    aria-describedby={VOLATILITY_KEY + '-hint'}
                    onChange={() => toggle(VOLATILITY_KEY)}
                  />
                  <span className={styles.rowText}>
                    <span className={styles.rowTitle}>{volatilityName}</span>
                    <span className={styles.rowBase}>{volatilityRow.choice.strategy.name}</span>
                  </span>
                </label>
                {state?.volatility && volatilityPick !== null ? (
                  <label className={styles.volatilityField}>
                    목표 변동성(연)
                    <select
                      value={volatilityPick}
                      onChange={(event) => setVolatilityPick(Number(event.target.value))}
                    >
                      {rangeChoices(state.volatility).map((value) => (
                        <option key={value} value={value}>
                          {value}%
                        </option>
                      ))}
                    </select>
                  </label>
                ) : null}
                <p id={VOLATILITY_KEY + '-hint'}>
                  {volatilityRow.reason ??
                    (state?.volatility
                      ? '같은 전략에 변동성 제한을 걸어 다시 계산합니다. 값이 낮을수록 더 자주, 더 많이 현금으로 옮깁니다.'
                      : '같은 전략을 변동성 제한 없이 다시 계산합니다.')}
                </p>
              </li>
            </ul>
          </li>
        ) : null}
      </ul>
      <div className={styles.actions}>
        {state && !state.error ? (
          <Button variant="outline" disabled={picked.length === 0} onClick={submit}>
            비교하기{picked.length > 0 ? ' (' + picked.length + ')' : ''}
          </Button>
        ) : null}
        <Button variant="outline" onClick={onClose}>
          목록 닫기
        </Button>
      </div>
    </section>
  )
}

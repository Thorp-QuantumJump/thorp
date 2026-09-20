import { useEffect, useState } from 'react'
import { fetchAnalysisVersions } from '@/api/analysisVersions'
import { fetchEvaluationWindow } from '@/api/sectors'
import { fetchStrategyRegistry } from '@/api/strategyRegistry'
import { resolveRulesVersion } from '@/components/strategy/analysisSupport'
import { Button } from '@/components/ui/button'
import type { PublicResultDetail } from '@/types/result'
import type { CompareChoice } from './compareRequest'
import styles from './comparison.module.css'

type Candidate = { choice: CompareChoice; reason: string | null; shortenedDays: number | null }

export function CompareTargetPicker({
  base,
  onSelect,
  onClose,
}: {
  base: PublicResultDetail
  onSelect: (choice: CompareChoice) => void
  onClose: () => void
}) {
  const [state, setState] = useState<{ candidates: Candidate[]; error: string | null } | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    const load = async () => {
      try {
        const [registry, versions] = await Promise.all([
          fetchStrategyRegistry(base.config.items.length, controller.signal),
          fetchAnalysisVersions(controller.signal),
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
        const candidates = registry.strategies
          .filter((strategy) => strategy.strategyId !== base.config.strategyId)
          .map((strategy): Candidate => {
            const rules = resolveRulesVersion(versions, strategy.strategyId)
            const longer = strategy.warmupTradingDays > base.config.warmupTradingDays
            const firstDay = longer ? window?.tradingDaysFromBase[strategy.warmupTradingDays] : null
            const start =
              firstDay && firstDay > base.config.effectiveStart
                ? firstDay
                : base.config.effectiveStart
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
                    ? '입력한 목표 비중이 없습니다. 다른 조건으로 비교에서 비중을 먼저 정해 주세요.'
                    : longer && (!firstDay || start > end)
                      ? '이 전략의 공통 평가 기간을 확인할 수 없습니다.'
                      : null
            return {
              choice: {
                strategy,
                registryVersion: registry.registryVersion,
                rulesVersion: rules.status === 'registered' ? rules.rulesVersion : '',
                ...(longer && firstDay ? { period: { start, end } } : {}),
              },
              reason,
              shortenedDays:
                longer && firstDay && window
                  ? window.tradingDaysFromBase.filter(
                      (date) => date >= base.config.effectiveStart && date < start,
                    ).length
                  : null,
            }
          })
        if (active) setState({ candidates, error: null })
      } catch (error) {
        if (active)
          setState({
            candidates: [],
            error: error instanceof Error ? error.message : '비교 전략을 불러오지 못했습니다.',
          })
      }
    }
    setState(null)
    void load()
    return () => {
      active = false
      controller.abort()
    }
  }, [base, attempt])

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
      <ul className={styles.candidates}>
        {state?.candidates.map(({ choice, reason, shortenedDays }, index) => (
          <li key={choice.strategy.strategyId}>
            <Button
              variant="outline"
              disabled={!!reason}
              aria-describedby={'compare-candidate-' + index}
              onClick={() => onSelect(choice)}
            >
              {choice.strategy.name}
            </Button>
            <p id={'compare-candidate-' + index}>
              {reason ??
                (shortenedDays !== null && shortenedDays > 0
                  ? '이 전략을 고르면 비교 기간이 ' + shortenedDays + '거래일 짧아집니다.'
                  : choice.strategy.warmupTradingDays > base.config.warmupTradingDays
                    ? '준비 기간을 맞춰 두 결과를 다시 계산합니다.'
                    : choice.strategy.description)}
            </p>
          </li>
        ))}
      </ul>
      <Button variant="outline" onClick={onClose}>
        목록 닫기
      </Button>
    </section>
  )
}

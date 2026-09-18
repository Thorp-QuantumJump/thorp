import type {
  EvaluationWindowState,
  WarmupJudgeInput,
  WarmupJudgement,
} from '@/components/strategy/analysisSupport'
import { EVALUATION_WINDOW_UNAVAILABLE } from '@/components/strategy/analysisSupport'
import type { EvaluationWindowQuery } from '@/types/evaluationWindow'
import type { AxisView, RegistryResponse, StrategyView } from '@/types/strategyRegistry'
import { meetsMinimumYears } from './analysisPeriod'

/**
 * 받아 둔 공통 평가 기간으로 워밍업을 판정하는 순수 함수(FR-011·FR-011a, T042).
 *
 * <p><b>서버에 다시 묻지 않는다.</b> 001 응답이 워밍업 0일 때의 기준일과 그 뒤 거래일 목록을 함께 주므로,
 * 워밍업 N일의 공통 시작일은 `tradingDaysFromBase[N]`이다(0-기준, 001 `firstEvaluable`과 같다). 값을 바꾸는
 * 동안 요청이 나가지 않는 까닭이 이것이다(research R7-1, T098).
 *
 * <p>날짜는 `YYYY-MM-DD` 문자열 그대로 견준다(`analysisPeriod.ts`와 같은 규약).
 */

export const EVALUATION_WINDOW_LOADING = '공통 평가 기간을 확인하는 중입니다.'
export const WARMUP_LIMIT_UNKNOWN =
  '공통 평가 기간 자료의 워밍업 한도를 계산하지 못해 판정할 수 없습니다.'
export const NOT_EVALUABLE =
  '이 섹터 조합에서는 워밍업을 채우고 나면 평가할 기간이 남지 않아 분석할 수 없습니다.'

function beyondRequestMessage(warmup: number, limit: number): string {
  return `워밍업 ${warmup}거래일은 조회한 범위(최대 ${limit}거래일)를 넘어 판정할 수 없습니다.`
}

function tooShortMessage(years: number): string {
  return `워밍업을 채우고 남는 기간이 최소 평가 기간(${years}년)에 못 미칩니다.`
}

function reducedMessage(warmup: number, base: string, start: string): string {
  return `워밍업 ${warmup}거래일을 채우느라 평가 시작일이 ${base}에서 ${start}로 늦춰집니다.`
}

/** 수 후보만 정수로 읽는다. 객체·글 후보는 워밍업에 더해질 수 없으므로 건너뛴다. */
function integerCandidate(value: unknown): number | null {
  const numeric =
    typeof value === 'number'
      ? value
      : typeof value === 'string' && value.trim() !== ''
        ? Number(value)
        : Number.NaN
  return Number.isInteger(numeric) ? numeric : null
}

/** 축이 가질 수 있는 가장 큰 정수 값. 알 수 없으면 `null`이다. */
function axisMaximum(axis: AxisView): number | null {
  if (axis.inputType === 'NUMBER') {
    return axis.range && Number.isFinite(axis.range.max) ? Math.floor(axis.range.max) : null
  }
  const candidates = (axis.choices ?? [])
    .map((choice) => integerCandidate(choice.value))
    .filter((value): value is number => value !== null)
  return candidates.length > 0 ? Math.max(...candidates) : null
}

/** 한 전략의 워밍업 규칙에 축마다 가장 큰 값을 넣은 합. 알 수 없는 항목이 있으면 `null`이다. */
function strategyMaximum(strategy: StrategyView): number | null {
  let total = 0
  for (const term of strategy.warmupRule?.sum ?? []) {
    if (typeof term === 'number') {
      if (!Number.isInteger(term)) return null
      total += term
      continue
    }
    const axis = strategy.axes.find((candidate) => candidate.name === term)
    const maximum = axis ? axisMaximum(axis) : null
    if (maximum === null) return null
    total += maximum
  }
  return total
}

/**
 * 등록표의 어느 전략·설정으로도 넘지 않는 워밍업 상한. 001에 이만큼의 거래일을 달라고 보낸다.
 *
 * <p>전략마다 `warmupRule.sum`에 축별 최대값(입력형은 `range.max`, 목록형은 수 후보 가운데 최대값)을 넣고
 * 그 가운데 가장 큰 값을 쓴다. 적용 조건(`appliesWhen`)은 보지 않는다 — 상한은 넉넉하면 되고, 조건을
 * 따지다 한 축을 빠뜨리면 상한이 모자라 판정하지 못하는 설정이 생긴다.
 *
 * <p><b>한 전략이라도 계산할 수 없으면 `null`이다.</b> 그 전략을 뺀 상한을 보내면 그 전략만 조용히
 * 판정하지 못하게 되므로, 모자란 상한을 짓지 않고 알 수 없다고 말한다(원칙 VI).
 */
export function maxWarmupOfRegistry(registry: RegistryResponse): number | null {
  let maximum = 0
  for (const strategy of registry.strategies) {
    const value = strategyMaximum(strategy)
    if (value === null) return null
    maximum = Math.max(maximum, value)
  }
  return maximum
}

/**
 * 화면이 보낼 평가 기간 조회. 상한을 계산하지 못하면 `'uncomputable'`이다.
 *
 * <p><b>섹터 순서를 바꾸지 않는다.</b> 001 산식은 `bindingCandidate`가 동점일 때 입력 순서로 고르고, 접수
 * 요청도 002 선택 순서대로 섹터를 싣는다. 여기서 정렬하면 화면이 안내한 원인 섹터와 접수 경로의 판정이
 * 갈릴 수 있다. `strategy`는 싣지 않는다 — 재판정 재료는 섹터 집합만으로 정해진다(001 답변).
 */
export function evaluationWindowQuery(
  sectorCodes: readonly string[],
  registry: RegistryResponse,
): EvaluationWindowQuery | 'uncomputable' {
  const maxWarmupTradingDays = maxWarmupOfRegistry(registry)
  if (maxWarmupTradingDays === null) return 'uncomputable'
  return { sectors: [...sectorCodes], maxWarmupTradingDays }
}

function unknown(message: string): WarmupJudgement {
  return { verdict: { state: 'unknown', message }, window: null, reduction: null }
}

function blocked(message: string): WarmupJudgement {
  return { verdict: { state: 'blocked', message }, window: null, reduction: null }
}

/**
 * 워밍업 하나를 받아 둔 평가 기간으로 판정한다.
 *
 * <ul>
 *   <li>아직 받지 못했거나 받지 못했으면 `unknown`이다. 값을 지어내지 않는다.</li>
 *   <li>워밍업이 <b>요청한</b> 상한을 넘으면 받은 자료로는 알 수 없어 `unknown`이다.</li>
 *   <li>요청한 상한 안인데 받은 목록을 넘으면 `blocked`다. 001은 목록을 `commonEnd`까지만 주고 달력이
 *       모자라면 상한을 줄이므로, 목록이 끝났다는 것은 그 워밍업으로 평가할 날이 없다는 뜻이다. 목록이
 *       비었으면(기준일이 이미 `commonEnd`를 넘음) 어떤 워밍업이든 `blocked`다(001 답변 ①).</li>
 *   <li>남는 기간이 최소 평가 기간에 못 미치면 `blocked`다.</li>
 *   <li>워밍업 때문에 시작일이 기준일보다 늦춰졌으면 `reduced`, 아니면 `ok`다.</li>
 * </ul>
 */
export function judgeWarmupFromWindow(
  state: EvaluationWindowState,
  input: Pick<WarmupJudgeInput, 'warmupTradingDays' | 'minEvaluationYears'>,
): WarmupJudgement {
  if (state.status === 'loading') return unknown(EVALUATION_WINDOW_LOADING)
  if (state.status === 'uncomputable') return unknown(WARMUP_LIMIT_UNKNOWN)
  if (state.status === 'unavailable') return unknown(EVALUATION_WINDOW_UNAVAILABLE)

  const { window, requestedMaxWarmup } = state
  const warmup = input.warmupTradingDays
  if (!Number.isInteger(warmup) || warmup < 0) return unknown(EVALUATION_WINDOW_UNAVAILABLE)
  if (warmup > requestedMaxWarmup) return unknown(beyondRequestMessage(warmup, requestedMaxWarmup))

  const days = window.tradingDaysFromBase
  if (warmup >= days.length) return blocked(NOT_EVALUABLE)
  const start = days[warmup]
  // 읽는 층이 마지막 날이 `commonEnd`를 넘지 않음을 이미 확인했다. 그래도 날짜를 견주는 판정은 여기 둔다.
  if (start > window.commonEnd) return blocked(NOT_EVALUABLE)

  const period = { start, end: window.commonEnd }
  if (!meetsMinimumYears(period, input.minEvaluationYears)) {
    return blocked(tooShortMessage(input.minEvaluationYears))
  }

  if (start === window.warmupBaseDate) {
    return { verdict: { state: 'ok' }, window: period, reduction: null }
  }
  return {
    verdict: { state: 'reduced', message: reducedMessage(warmup, window.warmupBaseDate, start) },
    window: period,
    reduction: {
      startWithoutWarmup: window.warmupBaseDate,
      bindingCandidate: window.bindingCandidate,
      bindingSector: window.bindingSector,
    },
  }
}

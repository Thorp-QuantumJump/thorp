import { useId, useRef, useState } from 'react'
import { ProvisionalBadge } from '@/components/paramsearch/ProvisionalBadge'
import type { AnalysisCondition, RestorableCondition } from '@/hooks/useStrategySettings'
import type { AnalysisConditions } from '@/types/strategyRegistry'
import {
  checkPeriodInput,
  isIsoDate,
  isWithin,
  type AnalysisPeriod,
} from '@/utils/analysisPeriod'
import styles from './AnalysisConditionsPanel.module.css'
import fieldStyles from './AxisField.module.css'
import type { CostRates } from './analysisSupport'
import { AxisNumberField } from './AxisNumberField'

/** 화면에 보일 비용 조건. 004가 등록한 현재 버전의 것이다. */
export type CostAssumptionView = {
  version: string
  provisional: boolean
  rates: CostRates
}

export type AnalysisConditionPatch = Partial<Omit<AnalysisCondition, 'scenario'>>

interface AnalysisConditionsPanelProps {
  /** 등록표 `analysisConditions`. 금액 범위와 최소 평가 기간 후보를 여기서 읽는다 */
  conditions: AnalysisConditions
  /** 워밍업을 채운 뒤 평가할 수 있는 공통 범위. 아직 모르면 `null`이고 기간을 바꿀 수 없다 */
  range: AnalysisPeriod | null
  /** `range`가 없을 때 그 까닭. 없으면 "아직 확인하지 못했다"는 일반 문장을 쓴다 */
  rangeUnavailableMessage?: string
  /** 지금 쓰이는 분석 기간. 사용자가 고르지 않았으면 `range`와 같다 */
  period: AnalysisPeriod | null
  /** 사용자가 기간을 직접 골랐는가. 참일 때만 '범위 전체로 되돌리기'를 보인다 */
  periodChosen: boolean
  /** 지금 쓰이는 시작 금액. 재검증이 비워 두고 복원을 기다리면 `undefined`다 */
  initialAmount: number | undefined
  minEvaluationYears: number
  /** 비용 조건을 확인하지 못했으면 `null`이다. 일반 문구로 대신하지 않는다 */
  cost: CostAssumptionView | null
  /** 허용된 값일 때만 불린다. 받는 쪽(`setAnalysisCondition`)이 같은 잣대로 한 번 더 거른다 */
  onChange: (patch: AnalysisConditionPatch) => void
  onReset: (name: RestorableCondition) => void
}

export const COST_UNAVAILABLE =
  '비용 조건을 확인할 수 없습니다. 확인하기 전에는 분석을 시작할 수 없습니다.'
const RANGE_UNAVAILABLE = '평가할 수 있는 범위를 아직 확인하지 못해 기간을 바꿀 수 없습니다.'

function toNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
    return Number(value)
  }
  return null
}

function formatBps(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 4 })}bp`
}

interface PeriodFieldProps {
  range: AnalysisPeriod | null
  rangeUnavailableMessage: string
  period: AnalysisPeriod | null
  periodChosen: boolean
  onChange: (period: AnalysisPeriod) => void
  onReset: () => void
}

/**
 * 분석 기간(FR-009).
 *
 * <p><b>범위 밖 날짜는 반영하지 않는다.</b> 달력의 `min`·`max`는 고르는 것을 돕기만 하고 직접 친 날짜를
 * 막지 못하므로, 바뀔 때마다 `checkPeriodInput`으로 다시 본다. 입력칸에는 사용자가 넣은 날짜를 그대로
 * 남기고 이유를 보인다 — 경계로 잘라 넣으면 사용자가 고르지 않은 날짜로 분석이 나간다.
 *
 * <p><b>입력 도중의 날짜가 남지 않게 한다.</b> 날짜 칸은 한 글자마다 바뀜을 보내므로 `31`을 치는 도중의
 * `03`처럼 범위 안인 중간 날짜가 먼저 반영된다. 마지막 날짜가 무효가 되면 입력을 시작하기 전의 기간으로
 * 되돌린다(`AxisNumberField`와 같은 규약). 되돌리지 않으면 입력칸은 "저장하지 않았습니다"라고 말하는데
 * 요청은 사용자가 의도한 적 없는 중간 날짜로 나간다.
 */
function PeriodField({
  range,
  rangeUnavailableMessage,
  period,
  periodChosen,
  onChange,
  onReset,
}: PeriodFieldProps) {
  const startId = useId()
  const endId = useId()
  const messageId = useId()
  const [draft, setDraft] = useState<AnalysisPeriod>(period ?? { start: '', end: '' })
  const [synced, setSynced] = useState(period)
  /** 입력을 시작할 때의 기간과, 그것이 사용자가 고른 기간이었는지 */
  const atEditStart = useRef<{ period: AnalysisPeriod | null; chosen: boolean }>({
    period,
    chosen: periodChosen,
  })

  // 바깥에서 기간이 바뀌면(범위 전체로 되돌리기, 전략·워밍업이 바뀌어 범위가 달라짐) 입력칸을 맞춘다.
  if (period?.start !== synced?.start || period?.end !== synced?.end) {
    setSynced(period)
    setDraft(period ?? { start: '', end: '' })
  }

  const draftIsCurrent = draft.start === period?.start && draft.end === period?.end
  const problem =
    range === null
      ? rangeUnavailableMessage
      : draftIsCurrent && period !== null && isWithin(period, range)
        ? null
        : checkPeriodInput(draft.start, draft.end, range)

  // 어느 칸이 문제인지 가른다. 순서가 뒤집힌 경우만 둘 다 문제다.
  const badDate = (date: string) =>
    range !== null && (!isIsoDate(date) || date < range.start || date > range.end)
  const reversed = isIsoDate(draft.start) && isIsoDate(draft.end) && draft.start > draft.end
  const startInvalid = problem !== null && range !== null && (badDate(draft.start) || reversed)
  const endInvalid = problem !== null && range !== null && (badDate(draft.end) || reversed)

  const rememberEditStart = () => {
    atEditStart.current = { period, chosen: periodChosen }
  }

  const update = (next: AnalysisPeriod) => {
    setDraft(next)
    if (range === null) return
    if (checkPeriodInput(next.start, next.end, range) !== null) {
      const before = atEditStart.current
      if (before.chosen && before.period) {
        if (before.period.start !== period?.start || before.period.end !== period?.end) {
          // 되돌린 값이 바깥에서 돌아와도 입력칸의 글은 사용자가 친 그대로 둔다.
          setSynced(before.period)
          onChange(before.period)
        }
      } else if (periodChosen) {
        // 고르지 않은 상태에서 시작했으면 되돌릴 곳은 범위 전체다.
        setSynced(range)
        onReset()
      }
      return
    }
    if (next.start === period?.start && next.end === period?.end) return
    onChange(next)
  }

  const hint = range ? `평가할 수 있는 범위는 ${range.start} ~ ${range.end}입니다.` : rangeUnavailableMessage

  return (
    <fieldset className={fieldStyles.field}>
      <legend className={fieldStyles.header}>
        <span className={fieldStyles.label}>분석 기간</span>
      </legend>
      <div className={fieldStyles.inputRow}>
        <label htmlFor={startId} className={styles.dateLabel}>
          시작일
        </label>
        <input
          id={startId}
          className={fieldStyles.input}
          type="date"
          min={range?.start}
          max={range?.end}
          value={draft.start}
          disabled={range === null}
          aria-invalid={startInvalid ? 'true' : 'false'}
          aria-describedby={messageId}
          onFocus={rememberEditStart}
          onChange={(event) => update({ ...draft, start: event.target.value })}
        />
        <label htmlFor={endId} className={styles.dateLabel}>
          종료일
        </label>
        <input
          id={endId}
          className={fieldStyles.input}
          type="date"
          min={range?.start}
          max={range?.end}
          value={draft.end}
          disabled={range === null}
          aria-invalid={endInvalid ? 'true' : 'false'}
          aria-describedby={messageId}
          onFocus={rememberEditStart}
          onChange={(event) => update({ ...draft, end: event.target.value })}
        />
        {periodChosen ? (
          <button
            type="button"
            className={fieldStyles.reset}
            onClick={() => {
              // 이미 범위 전체와 같은 날짜를 골라 두었으면 바깥 값이 바뀌지 않아 입력칸이 맞춰지지 않는다.
              if (range) setDraft(range)
              onReset()
            }}
          >
            범위 전체로 되돌리기
          </button>
        ) : null}
      </div>
      <p id={messageId} className={problem && range ? fieldStyles.problem : fieldStyles.hint} aria-live="polite">
        {problem && range ? `${problem} 이 기간은 저장하지 않았습니다. ${hint}` : hint}
      </p>
    </fieldset>
  )
}

/**
 * 기간·금액·조건(US3, FR-009·FR-010·FR-011a).
 *
 * <p><b>접힌 채로 시작한다.</b> 기본 화면의 입력은 전략 선택 하나이고(SC-005), 지금 적용되는 조건은 요약
 * 한 줄이 이미 보인다. 전략을 고르기 전에도 그린다 — 최소 평가 기간이 카드를 막을 수 있어서다(T102).
 *
 * <p><b>바꿀 수 있는 것은 셋뿐이다.</b> 분석 기간(평가 가능 범위 안), 시작 금액(등록표 범위 안), 최소 평가
 * 기간(등록표 후보 가운데). 비용 가정은 004가 등록한 수치·버전·임시 여부를 <b>읽기 전용</b>으로만 보이고,
 * 운용 방식은 연속 운용으로 고정해 보인다. 수수료·세금을 고치거나 수익이 높아지는 쪽으로 조건을 골라 주는
 * 선택지를 두지 않는다(FR-010, 원칙 IV). 목표 변동성은 '목표 위험 맞춤' 시나리오의 항목이라 여기 없다.
 *
 * <p><b>값을 모른다.</b> 범위·후보·라벨·단위는 모두 props로 받은 등록표와 판정에서 읽는다(FR-005).
 */
export function AnalysisConditionsPanel({
  conditions,
  range,
  rangeUnavailableMessage = RANGE_UNAVAILABLE,
  period,
  periodChosen,
  initialAmount,
  minEvaluationYears,
  cost,
  onChange,
  onReset,
}: AnalysisConditionsPanelProps) {
  const [open, setOpen] = useState(false)
  const bodyId = useId()
  const yearsGroup = useId()
  const amount = conditions.initialAmount
  const years = conditions.minEvaluationYears

  return (
    <section className={styles.panel} aria-label="기간·금액·조건">
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls={open ? bodyId : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        기간·금액·조건
      </button>

      {open ? (
        <div id={bodyId} className={styles.body}>
          <PeriodField
            range={range}
            rangeUnavailableMessage={rangeUnavailableMessage}
            period={period}
            periodChosen={periodChosen}
            onChange={(next) => onChange({ periodStart: next.start, periodEnd: next.end })}
            onReset={() => onReset('period')}
          />

          <AxisNumberField
            label="시작 금액"
            unit={amount.unit}
            range={amount}
            defaultValue={amount.default}
            value={initialAmount}
            onChange={(next) => onChange({ initialAmount: next })}
            onReset={() => onReset('initialAmount')}
          />

          <fieldset className={fieldStyles.field}>
            <legend className={fieldStyles.header}>
              <span className={fieldStyles.label}>최소 평가 기간</span>
            </legend>
            <p className={fieldStyles.description}>
              워밍업을 채운 뒤 남는 평가 기간이 이보다 짧으면 그 전략으로 분석하지 않습니다.
            </p>
            <div className={fieldStyles.choices}>
              {years.choices.map((choice) => {
                const value = toNumber(choice.value)
                if (value === null) return null
                return (
                  <label key={String(choice.value)} className={fieldStyles.choice}>
                    <input
                      type="radio"
                      name={yearsGroup}
                      checked={value === minEvaluationYears}
                      onChange={() => onChange({ minEvaluationYears: value })}
                    />
                    {choice.label}
                  </label>
                )
              })}
            </div>
          </fieldset>

          <div className={fieldStyles.field}>
            <span className={fieldStyles.label}>운용 방식</span>
            <p className={styles.readOnly}>연속 운용 (이 화면에서 바꾸지 않습니다)</p>
          </div>

          <div className={fieldStyles.field} role="group" aria-label="비용 가정">
            <span className={fieldStyles.label}>비용 가정 (읽기 전용)</span>
            {cost ? (
              <p className={styles.readOnly}>
                <span>수수료 {formatBps(cost.rates.commissionBps)}</span>
                <span>호가 차이 {formatBps(cost.rates.spreadBps)}</span>
                <span>슬리피지 {formatBps(cost.rates.slippageBps)}</span>
                <span>세금 {formatBps(cost.rates.taxBps)}</span>
                <span>
                  버전 {cost.version}
                  {cost.provisional ? <ProvisionalBadge label="비용 가정" /> : null}
                </span>
              </p>
            ) : (
              <p className={fieldStyles.problem} role="status">
                {COST_UNAVAILABLE}
              </p>
            )}
            {cost?.provisional ? (
              <p className={fieldStyles.hint}>
                팀 확정 전 임시 비용입니다. 결과를 확정된 비용 기준으로 읽지 마세요.
              </p>
            ) : null}
          </div>
        </div>
      ) : null}
    </section>
  )
}

import type {
  AxisValue,
  CandidateStability,
  CandidateStatus,
  ParamSearchConfig,
  ParamSearchStopReason,
  SearchCandidateItem,
  SelectionFilter,
  SelectionOutcome,
} from '@/types/paramSearch'

/**
 * 후보값 하나를 견주고 목록의 열쇠로 쓸 문자열.
 *
 * **같은 값을 `===`로 견줄 수 없다.** 객체형 후보(`{mode, unit}`)는 응답을 다시 받을 때마다 새
 * 객체라, 켜 둔 값과 목록의 값이 같은 뜻이어도 다른 참조다. 그대로 두면 켠 값이 꺼진 것처럼
 * 보이고 기본 시작값 표시도 붙지 않는다.
 *
 * 필드 순서는 서버 응답의 순서를 그대로 따르므로 같은 값은 같은 문자열이 된다. **이 문자열을
 * 요청에 보내지 않는다** — 보내는 것은 언제나 원래 값이다.
 */
export function axisValueKey(value: AxisValue): string {
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

/** 사람이 읽는 정수. 자릿수가 큰 후보 수·조합 수가 화면에서 흐트러지지 않게 한다. */
export function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

/**
 * 초 단위 한도를 분·초로 읽는다. 60초 미만은 초로 둔다.
 *
 * **`null`은 상한을 두지 않았다는 뜻이다**(계약의 `timeLimitSeconds`). 0초로 접으면 화면이 "0초 안에
 * 끝내야 한다"는 없는 제한을 말하게 된다.
 */
export function formatSeconds(seconds: number | null): string {
  if (seconds === null) return '제한 없음'
  if (seconds < 60) return `${formatCount(seconds)}초`

  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest === 0 ? `${formatCount(minutes)}분` : `${formatCount(minutes)}분 ${rest}초`
}

/**
 * 비율을 퍼센트로. **`null`은 `null`로 돌려준다** — 계산하지 않은 값을 "0%"로 만들지 않는다
 * (FR-011, 원칙 VI). 값이 없을 때 무엇을 보일지는 화면이 사유와 함께 정한다.
 *
 * 계약이 비율을 문자열로 주므로(부동소수 오차) 표시 직전에만 숫자로 바꾼다.
 */
export function formatPercent(value: string | null, options: { sign?: boolean } = {}) {
  if (value === null) return null

  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return null

  const percent = parsed * 100
  const body = percent.toLocaleString('ko-KR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
  return options.sign && percent > 0 ? `+${body}%` : `${body}%`
}

/** 거래비용. 원 단위로 읽고, 없으면 `null`이다. */
export function formatWon(value: string | null) {
  if (value === null) return null

  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return null

  return `${Math.round(parsed).toLocaleString('ko-KR')}원`
}

/**
 * 인접 설정 안정성. 이웃과의 수익 차 절댓값 평균이라 **작을수록 안정**이고 단위는 %p다.
 *
 * `calculable: false`는 "안정성이 나쁘다"가 아니라 **"이웃이 없어 말할 수 없다"**이다(FR-013b).
 * 0으로 채우지 않는다.
 */
export function formatStability(stability: CandidateStability | null) {
  if (stability === null) return { text: null, note: '선정 계산 전입니다' }
  if (!stability.calculable || stability.value === null) {
    return { text: null, note: '이웃한 설정이 없어 계산하지 않았습니다' }
  }

  const percent = formatPercent(stability.value)
  return {
    text: percent === null ? null : `${percent}p`,
    note: `이웃 ${formatCount(stability.neighborCount)}개와 비교`,
  }
}

/** 후보 상태 문구. */
export function candidateStatusText(status: CandidateStatus): string {
  switch (status) {
    case 'PENDING':
      return '대기'
    case 'DISPATCHED':
      return '계산 중'
    case 'COMPLETED':
      return '완료'
    case 'FAILED':
      return '실패'
    case 'EXCLUDED':
      return '계산 전 제외'
    case 'SKIPPED':
      return '미실행'
  }
}

/**
 * 성과가 없는 이유. **"0%"를 그리지 않으려면 대신 보일 문장이 있어야 한다**(FR-011).
 *
 * 서버가 사유를 줬으면 그것을 쓴다 — 유효성 규칙이 돌려준 문장이 화면이 지어낸 문장보다 정확하다.
 */
export function missingPerformanceReason(candidate: SearchCandidateItem): string {
  const given = candidate.selectionReason?.message
  if (given) return given

  switch (candidate.status) {
    case 'EXCLUDED':
      return '유효성 제약에 걸려 계산 전에 제외되었습니다'
    case 'FAILED':
      return '계산이 실패했습니다'
    case 'SKIPPED':
      return '취소·한도로 실행되지 않았습니다'
    case 'PENDING':
      return '아직 발행되지 않았습니다'
    case 'DISPATCHED':
      return '계산 중입니다'
    case 'COMPLETED':
      return '결과에 값이 없습니다'
  }
}

/**
 * 선정 결과 문구.
 *
 * **`FILTERED_OUT`은 탈락이고 `NOT_IN_DISPLAY`는 "떨어진 것이 아니라 표시하지 않은 것"이다** —
 * 두 문구를 같게 쓰면 통과한 후보가 탈락한 것처럼 읽힌다.
 */
export function selectionOutcomeText(outcome: SelectionOutcome): string {
  switch (outcome) {
    case 'SELECTED':
      return '표시 후보'
    case 'NOT_IN_DISPLAY':
      return '기준은 통과했으나 표시 범위 밖'
    case 'FILTERED_OUT':
      return '기준에서 탈락'
    case 'EXCLUDED_BEFORE_RUN':
      return '계산 전 제외'
    case 'NOT_EVALUATED':
      return '비교 대상 아님'
  }
}

/** 필터 이름. 사용자가 읽는 말로 옮긴다. */
export function selectionFilterText(filter: SelectionFilter): string {
  switch (filter) {
    case 'DRAWDOWN':
      return '손실 제약'
    case 'STABILITY':
      return '인접 설정 안정성'
  }
}

/** 정렬 지표 이름. **화면에 무엇으로 정렬했는지 밝히는 것이 설계 제약이다**(plan 설계 제약 3). */
export function sortMetricText(metric: string): string {
  switch (metric) {
    case 'NET_RETURN':
      return '비용 차감 후 수익'
    case 'MAX_DRAWDOWN':
      return '가장 크게 줄었던 폭'
    case 'TRADING_COST':
      return '거래비용'
    case 'STABILITY':
      return '인접 설정 안정성'
    default:
      return metric
  }
}

/**
 * 후보가 어떤 설정인지 한 줄로. **전략 이름도 축 라벨도 응답에서만 읽는다**(FR-025).
 *
 * 상태 응답의 `config.space`가 전략별 축 라벨·단위를 갖고 있다. 그것이 없으면 축 이름을 그대로
 * 쓴다 — 지어내지 않는다.
 */
export function describeCandidateSetting(
  candidate: SearchCandidateItem,
  config: ParamSearchConfig | null,
): { strategyName: string; parameterText: string } {
  const space = config?.space?.[candidate.strategyId] ?? null
  const parameters = describeCandidateParameters(candidate, config)

  return {
    strategyName: space?.strategyName ?? candidate.strategyId,
    parameterText:
      parameters.length === 0
        ? '설정 값 없음'
        : parameters.map((item) => `${item.label} ${item.valueText}`).join(' · '),
  }
}

/** 축 하나가 무슨 값인지. 라벨과 값을 따로 들고 있어 화면이 둘을 나눠 그릴 수 있다. */
export type CandidateParameter = {
  /** 응답의 축 이름. 화면 키로 쓴다 */
  name: string
  /** 사람이 읽는 축 이름. 등록표에 없으면 축 이름을 그대로 쓴다 */
  label: string
  /** 표시 문구와 단위를 붙인 값 */
  valueText: string
}

/**
 * 후보의 설정값을 축마다 라벨과 값으로 나눠 준다.
 *
 * <p><b>한 줄 문자열과 나눠 둔 이유가 있다.</b> 카드처럼 자리가 있는 곳은 축 이름과 값을 위아래로
 * 놓아야 어떤 설정인지 한눈에 읽히고, 표 칸처럼 좁은 곳은 `describeCandidateSetting`의 한 줄이
 * 맞다. 두 화면이 같은 규칙으로 값을 읽도록 여기 한 곳에서만 만든다(FR-025).
 */
export function describeCandidateParameters(
  candidate: SearchCandidateItem,
  config: ParamSearchConfig | null,
): CandidateParameter[] {
  const space = config?.space?.[candidate.strategyId] ?? null

  return Object.entries(candidate.parameters).map(([name, value]) => {
    const axis = space?.axes.find((item) => item.name === name) ?? null
    const valueKey = axisValueKey(value)
    const valueIndex = axis?.values.findIndex((candidateValue) => {
      return axisValueKey(candidateValue) === valueKey
    })
    const valueLabel =
      valueIndex !== undefined && valueIndex >= 0 ? axis?.valueLabels?.[valueIndex] : undefined

    return {
      name,
      label: axis?.label ?? name,
      valueText: `${valueLabel ?? valueKey}${axis?.unit ?? ''}`,
    }
  })
}

/**
 * 묶음이 멈춘 사유. **"실패"와 섞지 않는다** — 취소·한도는 실행을 멈춘 것이지 계산이 틀린 것이
 * 아니다.
 */
export function stopReasonText(reason: ParamSearchStopReason): string {
  switch (reason) {
    case 'CANCELLED':
      return '사용자가 취소했습니다'
    case 'CANDIDATE_LIMIT':
      return '후보 수 상한에 닿았습니다'
    case 'TIME_LIMIT':
      return '실행 시간 한도에 닿았습니다'
    case 'FAILURE_LIMIT':
      return '실패가 이어져 멈췄습니다'
    case 'NO_VALID_CANDIDATE':
      return '돌릴 수 있는 후보가 없었습니다'
    case 'STRATEGY_NOT_REGISTERED':
      return '등록표에 없는 전략이 있었습니다'
    case 'STRATEGY_NOT_EXECUTABLE':
      return '실행을 지원하지 않는 전략이 있었습니다'
  }
}

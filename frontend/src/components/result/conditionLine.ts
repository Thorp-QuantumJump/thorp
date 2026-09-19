import type { PublicResultDetail } from '@/types/result'

/**
 * 조건 한 줄(FR-003)의 세 항목과 비중 출처 문장(FR-014).
 *
 * '한 줄'은 하나의 묶음이라는 뜻이다. 세 항목을 어느 폭에서도 생략하지 않고, 좁은 화면에서는
 * 줄바꿈만 허용한다 — 그래서 문자열 하나가 아니라 항목별로 돌려주고 화면이 한 묶음 안에 나란히 둔다.
 */
export type ConditionLine = {
  /** 분석 기간 */
  period: string
  /** 비교 대상 */
  comparison: string
  /** 비용 적용 여부 */
  cost: string
  /** 규칙이 비중을 계산한 결과일 때만 문장이 있다 */
  weightsNote: string | null
}

export type ConditionLineInput = Pick<
  PublicResultDetail,
  'config' | 'versions' | 'strategyName' | 'weightsSource'
> & {
  /** 비교 대상의 이름. 없으면 `null` */
  targetName?: string | null
  targetWeightsSource?: PublicResultDetail['weightsSource']
}

export const COMPUTED_WEIGHTS_NOTE = '비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다.'

/**
 * 전략 이름. 등록표에서 찾지 못해 `strategyName`이 없으면 `strategyId`를 그대로 보인다.
 * 이름을 지어내지 않는다.
 */
export function strategyLabel(detail: Pick<PublicResultDetail, 'config' | 'strategyName'>): string {
  return detail.strategyName ?? detail.config.strategyId
}

/** 응답의 조건과 버전으로 조건 한 줄을 만든다. 값을 계산하지 않고 고르고 잇기만 한다. */
export function buildConditionLine(input: ConditionLineInput): ConditionLine {
  const { config, versions } = input
  const costProfile = versions.costProfile

  return {
    period: `분석 기간 ${config.effectiveStart} ~ ${config.effectiveEnd}`,
    comparison: `기준 ${strategyLabel(input)} · 비교 대상 ${input.targetName ?? '없음'}`,
    cost: costProfile
      ? `비용 반영 (${costProfile.version}${costProfile.provisional ? ', 임시 비용 기준' : ''})`
      : '비용 반영 여부를 확인할 수 없음',
    weightsNote:
      [
        input.weightsSource === 'COMPUTED' ? COMPUTED_WEIGHTS_NOTE : null,
        input.targetWeightsSource === 'COMPUTED'
          ? '비교 대상의 비중도 선택한 규칙의 계산 결과입니다.'
          : null,
      ]
        .filter(Boolean)
        .join(' ') || null,
  }
}

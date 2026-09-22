import { describe, expect, it } from 'vitest'
import { resultDetailCompleted } from '@/test/resultFixtures'
import type { PublicResultDetail } from '@/types/result'
import { STATUS_SENTENCE_TEMPLATES, selectStatusSentence } from './statusSentences'

/** 005 T076. FR-013, SC-004~SC-005, quickstart 5. */

const forbiddenWords = ['추천', '최적', '승자', '우승', '유리', '좋다', '할 것', '유사', '확률']

function detail(overrides: Partial<PublicResultDetail>): PublicResultDetail {
  return { ...resultDetailCompleted, ...overrides }
}

describe('결과 상태 문장 틀', () => {
  it('틀 전체 문자열에 금지 어휘가 하나도 없다', () => {
    const allTemplates = Object.values(STATUS_SENTENCE_TEMPLATES).join('\n')

    for (const word of forbiddenWords) {
      expect(allTemplates, word).not.toContain(word)
    }
  })

  it('부분 결과는 완료 범위를 날짜로 밝힌다', () => {
    expect(selectStatusSentence(detail({ partial: true }), '2025-03-19')).toContain(
      '2025-03-19까지 계산된 부분 결과입니다.',
    )
    expect(selectStatusSentence(detail({ partial: true }), null)).toContain(
      '완료 범위를 확인할 수 있는 데까지만 계산된 부분 결과입니다.',
    )
  })

  it.each([
    ['CANCELLED_WHILE_RUN_CONTINUED', '취소 시점까지의 지표는 계산되지 않았습니다.'],
    [
      'CANCEL_UNANSWERED',
      '취소 시점까지의 지표는 계산되지 않았습니다. 취소 요청에 대한 실행 상태를 확인하지 못했습니다.',
    ],
    [
      'CANCELLED_BEFORE_START',
      '취소 시점까지의 지표는 계산되지 않았습니다. 계산을 시작하기 전에 취소되어 완료된 거래일이 없습니다.',
    ],
  ] as const)('지표 없음 사유 %s를 0으로 대신하지 않는다', (reason, expected) => {
    expect(
      selectStatusSentence(
        detail({ status: 'CANCELLED', summary: null, summaryUnavailable: reason }),
        null,
      ),
    ).toContain(expected)
  })

  it('계산 시작 전 취소는 부분 결과가 있다고 표현하지 않는다', () => {
    const sentence = selectStatusSentence(
      detail({
        status: 'CANCELLED',
        partial: true,
        summary: null,
        summaryUnavailable: 'CANCELLED_BEFORE_START',
        exceptionKinds: null,
      }),
      null,
    )

    expect(sentence).not.toContain('부분 결과')
    expect(sentence).toContain('계산을 시작하기 전에 취소되어 완료된 거래일이 없습니다.')
    expect(sentence).not.toMatch(/0%|0원/)
  })

  it('자료 부족·계산 제한은 종류와 전체 건수를 함께 밝힌다', () => {
    const sentence = selectStatusSentence(
      detail({ exceptionKinds: ['HALT_DEFERRED', 'MISSING_PRICE'] }),
      null,
    )

    expect(sentence).toContain('자료 부족·계산 제한 기록이 1건 있습니다.')
    expect(sentence).toContain('종류: HALT_DEFERRED, MISSING_PRICE.')
  })

  it('예외가 없으면 자료 부족·계산 제한 문장을 만들지 않는다', () => {
    expect(selectStatusSentence(detail({ exceptionKinds: [], summary: { ...resultDetailCompleted.summary!, exceptionCount: 0 } }), null)).not.toContain(
      '자료 부족',
    )
  })

  it('실행 실패는 코드와 서버가 준 사유를 낮은 수익으로 바꾸지 않는다', () => {
    expect(
      selectStatusSentence(
        detail({
          status: 'FAILED',
          summary: null,
          exceptionKinds: null,
          failure: { code: 'ENGINE_ERROR', message: '계산 엔진이 응답하지 않았습니다.' },
        }),
        null,
      ),
    ).toContain('실행이 끝나지 못했습니다(ENGINE_ERROR): 계산 엔진이 응답하지 않았습니다.')
  })

  it('검증 구분은 문장에 담지 않는다', () => {
    // 구분을 가르려면 기간을 탐색 구간과 잠금 구간으로 나눠야 하는데 그 분할 설계(PD-12)가 아직
    // 없다. 가를 수 없는 것을 화면에 적지 않는다(005 FR-012).
    const sentence = selectStatusSentence(resultDetailCompleted, null)

    expect(sentence).not.toContain('검증')
    expect(sentence).toContain('자료 부족·계산 제한 기록이')
  })

  it('알릴 상태가 없으면 빈 문장을 돌려준다', () => {
    const sentence = selectStatusSentence(
      detail({ status: 'COMPLETED', partial: false, summary: null, exceptionKinds: null }),
      null,
    )

    expect(sentence).toBe('')
  })

})

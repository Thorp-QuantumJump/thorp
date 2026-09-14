import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PartialResultNotice } from './PartialResultNotice'
import { RejectionNotice } from './RejectionNotice'

function sentenceFor(code: string) {
  const { container, unmount } = render(<RejectionNotice code={code} />)
  const text = container.textContent ?? ''
  unmount()
  return text
}

/**
 * 거부 다섯 가지와 부분 결과 표시(T104·T105, FR-026·FR-009).
 *
 * <p>가장 조용히 깨지는 것은 <b>미등록과 실행 미지원을 한 문장으로 합치는 일</b>이다. 화면은
 * 멀쩡해 보이고 둘 다 "안 되는 전략"이라 적혀 있지만, 사용자가 기다려야 할 대상이 달라진다.
 */
describe('RejectionNotice', () => {
  it('다섯 가지 사유에 각각 다른 안내가 있다', () => {
    const sentences = [
      'CANDIDATE_LIMIT_EXCEEDED',
      'NO_VALID_COMBINATION',
      'STRATEGY_NOT_REGISTERED',
      'STRATEGY_NOT_EXECUTABLE',
      'REGISTRY_UNAVAILABLE',
    ].map(sentenceFor)

    expect(new Set(sentences).size).toBe(5)
  })

  it('미등록 전략과 실행 미지원 전략을 다른 문장으로 적는다', () => {
    const notRegistered = sentenceFor('STRATEGY_NOT_REGISTERED')
    const notExecutable = sentenceFor('STRATEGY_NOT_EXECUTABLE')

    expect(notRegistered).toContain('등록표에 없는 전략')
    expect(notExecutable).toContain('실행을 지원하지 않는 전략')
    // 사용자가 기다려야 할 대상이 다르다.
    expect(notRegistered).toContain('전략이 등록되면')
    expect(notExecutable).toContain('실행 지원이 붙으면')
  })

  it('상한 초과는 범위를 좁히라고, 유효 조합 0건은 범위를 바꾸라고 안내한다', () => {
    expect(sentenceFor('CANDIDATE_LIMIT_EXCEEDED')).toContain('후보값을 줄이면')
    expect(sentenceFor('NO_VALID_COMBINATION')).toContain('섹터를 늘리거나')
  })

  it('등록표 조회 실패는 지난 정보로 대신 실행하지 않는다고 밝힌다', () => {
    const text = sentenceFor('REGISTRY_UNAVAILABLE')

    expect(text).toContain('잠시 후 다시')
    expect(text).toContain('지난 정보로 대신 실행하지는 않습니다')
  })

  it('모르는 사유는 서버가 준 문장을 그대로 보인다', () => {
    const { container } = render(
      <RejectionNotice code="RULES_NOT_REGISTERED" message="전략 규칙이 등록되지 않았습니다." />,
    )

    expect(container.textContent).toContain('전략 규칙이 등록되지 않았습니다.')
    expect(container.textContent).toContain('RULES_NOT_REGISTERED')
  })

  it('범위를 자동으로 줄여 대신 실행하지 않는다고 적는다', () => {
    // 원칙 VII — 문턱을 낮춰 채우지 않는다. 거부도 같다.
    expect(sentenceFor('CANDIDATE_LIMIT_EXCEEDED')).toContain('자동으로 줄여 대신 실행하지는 않습니다')
  })
})

describe('PartialResultNotice', () => {
  const counts = {
    expected: 5,
    valid: 5,
    completed: 3,
    failed: 1,
    excluded: 0,
    pending: 0,
    skipped: 1,
  }

  it('완료한 범위를 숫자로 밝힌다', () => {
    const { container } = render(
      <PartialResultNotice counts={counts} stopReason="CANCELLED" partialNote={null} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('부분 결과입니다')
    expect(text).toContain('사용자가 취소했습니다')
    expect(text).toContain('유효 후보')
    expect(text).toContain('미실행')
  })

  it('미실행을 실패와 섞지 않는다', () => {
    render(<PartialResultNotice counts={counts} stopReason="TIME_LIMIT" partialNote={null} />)

    // 실패 1개와 미실행 1개가 각각 제 자리에 있다.
    expect(screen.getByText('실패')).toBeTruthy()
    expect(screen.getByText('미실행')).toBeTruthy()
    expect(screen.getByText('실행 시간 한도에 닿았습니다')).toBeTruthy()
  })

  it('멈추는 중에는 남은 후보가 완주한다는 것을 알린다', () => {
    const { container } = render(
      <PartialResultNotice
        counts={counts}
        stopReason="CANCELLED"
        partialNote={null}
        stopping
      />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('멈추는 중입니다')
    expect(text).toContain('이미 실행에 들어간 후보는 끝까지 계산합니다')
  })

  it('서버가 준 문장이 있으면 함께 보인다', () => {
    const { container } = render(
      <PartialResultNotice
        counts={counts}
        stopReason="CANCELLED"
        partialNote="유효 후보 5개 중 4개까지 계산했습니다"
      />,
    )

    expect(container.textContent).toContain('유효 후보 5개 중 4개까지 계산했습니다')
  })
})

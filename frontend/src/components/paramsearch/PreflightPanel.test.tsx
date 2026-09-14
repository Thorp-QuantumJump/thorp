import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { previewFixture } from '@/test/paramSearchFixtures'
import { PreflightPanel } from './PreflightPanel'

function runButton() {
  return screen.getByRole('button', { name: /실행/ }) as HTMLButtonElement
}

/**
 * 확인 화면은 값을 하나도 받지 않고 한 동작으로 실행된다(SC-001, FR-005a).
 *
 * <p>이것이 깨지는 방식은 대개 "편의 기능"이다 — 후보 수를 직접 넣게 하거나, 실행 전에 무언가를
 * 고르게 만들거나, 버튼을 둘로 나눈다. 그래서 <b>입력 요소가 없는지</b>와 <b>버튼이 하나인지</b>를
 * 함께 본다.
 */
describe('PreflightPanel', () => {
  it('값을 하나도 입력하지 않아도 실행 버튼이 활성이다', () => {
    render(<PreflightPanel preview={previewFixture} onRun={vi.fn()} running={false} />)

    expect(runButton().disabled).toBe(false)

    // 실행 전에 채워야 할 입력이 없다 — 후보 숫자를 받지 않는다.
    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
    expect(screen.queryAllByRole('combobox')).toHaveLength(0)
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it('한 번 누르면 실행이 한 번만 나간다', async () => {
    const onRun = vi.fn()
    const user = userEvent.setup()
    render(<PreflightPanel preview={previewFixture} onRun={onRun} running={false} />)

    await user.click(runButton())

    expect(onRun).toHaveBeenCalledTimes(1)
  })

  it('네 항목을 응답에서 읽어 보인다', () => {
    const { container } = render(
      <PreflightPanel preview={previewFixture} onRun={vi.fn()} running={false} />,
    )
    const text = container.textContent ?? ''

    // 전략 이름·축 라벨·단위는 전부 응답의 값이다.
    expect(text).toContain('알파 기울기')
    expect(text).toContain('캐리 순환')
    expect(text).toContain('실행 미지원')
    expect(text).toContain('기울기 관측 창 3개 (거래일)')
    expect(text).toContain('조합 6개')

    // 예상 작업량과 제외 사유.
    expect(text).toContain('7개')
    expect(text).toContain('전체 조합 8개')
    expect(text).toContain('관측 창이 자료 구간보다 깁니다.')

    // 적용 한도 네 줄.
    expect(text).toContain('후보 수 상한')
    expect(text).toContain('실행 시간 한도')
    expect(text).toContain('2분')
    expect(text).toContain('연속 실패 한도')
  })

  it('limits.provisional 에 든 항목에만 임시값 표시가 붙는다', () => {
    render(<PreflightPanel preview={previewFixture} onRun={vi.fn()} running={false} />)

    // 픽스처는 maxCandidates·dispatchWindow 둘만 임시값이다.
    expect(screen.getAllByText('임시값')).toHaveLength(2)
    expect(screen.getByText('후보 수 상한은(는) 실측 전 임시로 정한 값입니다')).toBeTruthy()
    expect(screen.queryByText('실행 시간 한도은(는) 실측 전 임시로 정한 값입니다')).toBeNull()
  })

  it('미검증을 결과보다 먼저 보인다', () => {
    const { container } = render(
      <PreflightPanel preview={previewFixture} onRun={vi.fn()} running={false} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('미검증')
    expect(text).toContain('평가 분할이 등록되지 않아')
  })

  it('한도를 넘으면 실행을 막고 범위를 좁히라고 안내한다', () => {
    const exceeded = {
      ...previewFixture,
      limits: { ...previewFixture.limits, exceeded: true },
    }
    render(<PreflightPanel preview={exceeded} onRun={vi.fn()} running={false} />)

    expect(runButton().disabled).toBe(true)
    expect(screen.getByRole('alert').textContent).toMatch(/후보값을 줄여/)
  })

  it('화면 사정으로 막을 때는 이유를 함께 보인다', () => {
    render(
      <PreflightPanel
        preview={previewFixture}
        onRun={vi.fn()}
        running={false}
        blockedReason="실행하려면 로그인이 필요합니다."
      />,
    )

    expect(runButton().disabled).toBe(true)
    expect(screen.getByRole('alert').textContent).toContain('로그인이 필요합니다')
  })

  it('순위·추천 문구를 쓰지 않는다', () => {
    const { container } = render(
      <PreflightPanel preview={previewFixture} onRun={vi.fn()} running={false} />,
    )

    const text = container.textContent ?? ''
    for (const banned of ['추천', '최적', '1위', '우승']) {
      expect(text).not.toContain(banned)
    }
  })
})

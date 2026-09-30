import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
} from '@/test/strategyRegistryFixtures'
import type { StrategyView } from '@/types/strategyRegistry'
import type { WarmupVerdict } from './cardNotices'
import { StrategyCardList, type RegistryViewState } from './StrategyCardList'

/**
 * 카드는 응답만 보고 그린다(FR-001, FR-005, FR-018, quickstart 9).
 *
 * <p><b>픽스처의 전략 이름은 등록표에 없는 것이다.</b> 실제 이름을 쓰면 화면이 그 전략을 알고
 * 있어도 검사가 통과해 버린다 — 지어낸 이름이라야 "응답만 보고 그린다"가 증명된다.
 */
const loaded: RegistryViewState = { status: 'loaded', registry: registryFixture }

/**
 * 이름으로 카드를 찾는다.
 *
 * 카드의 접근 가능 이름은 이름·설명·사유를 이어 붙인 글이라 전체 일치로는 찾을 수 없다. 그래서
 * 부분 일치를 쓰되, 정규식이 아니라 함수로 견준다 — 전략 이름에 괄호 같은 정규식 기호가 들어
 * 있으면(`똑같이 나누기(시험용)`) 정규식은 그것을 문법으로 읽어 엉뚱한 것을 찾는다.
 */
function cardNamed(name: string): HTMLElement {
  return screen.getByRole('radio', { name: (accessibleName) => accessibleName.includes(name) })
}

/** 워밍업을 따지지 않는 검사에서 쓰는 판정. 아무 전략에나 통과를 준다. */
const allOk = () => ({ state: 'ok' }) as WarmupVerdict

function renderList(
  state: RegistryViewState,
  overrides: {
    selectedStrategyId?: string | null
    onSelect?: (strategyId: string) => void
    onRetry?: () => void
    judgeWarmup?: (strategy: StrategyView) => WarmupVerdict
  } = {},
) {
  const onSelect = overrides.onSelect ?? vi.fn()
  const onRetry = overrides.onRetry ?? vi.fn()
  const result = render(
    <StrategyCardList
      state={state}
      selectedStrategyId={overrides.selectedStrategyId ?? null}
      onSelect={onSelect}
      onRetry={onRetry}
      judgeWarmup={overrides.judgeWarmup ?? allOk}
    />,
  )
  return { ...result, onSelect, onRetry }
}

/** 카드가 보조기술에 전하는 설명 전체. 사유 문장은 목록 아래 한 곳에 있고 카드는 `aria-describedby`로 가리킨다. */
function descriptionOf(card: HTMLElement): string {
  return (card.getAttribute('aria-describedby') ?? '')
    .split(' ')
    .filter(Boolean)
    .map((id) => document.getElementById(id)?.textContent ?? '')
    .join(' ')
}

/** 카드 줄 아래에 모아 적은 사유 목록의 항목들. */
function sharedItems(): HTMLElement[] {
  return screen.queryAllByRole('listitem')
}

describe('StrategyCardList', () => {
  it('카드에 이름과 한 줄 설명만 보이고 설정 항목은 보이지 않는다', () => {
    const { container } = renderList(loaded)

    expect(container.textContent).toContain(selectableStrategy.name)
    expect(container.textContent).toContain(selectableStrategy.description)

    // 축 라벨·단위·기본값은 응답에 함께 실려 오지만 첫 화면에 나오지 않는다.
    //
    // **이유 문장은 검사에서 뺀다.** 고를 수 없는 사유가 축 라벨을 그대로 담는 것은 등록표의
    // 정상적인 모양이다("고를 섹터 수가 후보 섹터 집합의 크기보다 큽니다"). 카드 전체 글에서
    // 라벨을 금지하면 설정 항목이 아니라 그 사유 문장을 잡게 되어, 검사가 요구사항이 아닌 것을
    // 지키라고 요구하게 된다. 사유가 실제로 보이는지는 아래 별도 검사가 확인한다.
    for (const strategy of registryFixture.strategies) {
      const card = cardNamed(strategy.name)
      const withoutReason = card.cloneNode(true) as HTMLElement
      withoutReason.querySelector(`#strategy-card-notice-${strategy.strategyId}`)?.remove()
      const text = withoutReason.textContent ?? ''

      for (const axis of strategy.axes) {
        expect(text).not.toContain(axis.label)
      }
    }
  })

  it('응답의 strategies 순서를 그대로 쓴다', () => {
    renderList(loaded)

    const names = screen.getAllByRole('radio').map((card) => card.textContent ?? '')
    expect(names).toHaveLength(registryFixture.strategies.length)
    registryFixture.strategies.forEach((strategy, index) => {
      expect(names[index]).toContain(strategy.name)
    })
  })

  it('키보드로 카드를 고를 수 있고 aria-checked 가 바뀐다', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const { rerender } = renderList(loaded, { onSelect })

    const card = cardNamed(selectableStrategy.name)
    expect(card.getAttribute('aria-checked')).toBe('false')

    card.focus()
    await user.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith(selectableStrategy.strategyId)

    rerender(
      <StrategyCardList
        state={loaded}
        selectedStrategyId={selectableStrategy.strategyId}
        onSelect={onSelect}
        onRetry={vi.fn()}
        judgeWarmup={allOk}
      />,
    )
    expect(cardNamed(selectableStrategy.name).getAttribute('aria-checked')).toBe('true')
  })

  it('고를 수 없는 카드는 눌러도 선택되지 않고 이유가 함께 보인다', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    renderList(loaded, { onSelect })

    const card = cardNamed(unselectableStrategy.name)

    // disabled 가 아니라 aria-disabled 다 — 초점을 받아야 이유를 읽을 수 있다.
    expect(card.getAttribute('aria-disabled')).toBe('true')
    expect(card.getAttribute('aria-checked')).toBe('false')
    // 사유는 카드 줄 아래 목록에 전략 이름과 함께 적히고, 카드는 번호와 설명 연결로 그 문장을 가리킨다.
    expect(descriptionOf(card)).toContain(unselectableStrategy.unselectableReason)
    const item = sharedItems().find((node) =>
      node.textContent?.includes(unselectableStrategy.unselectableReason ?? '∅'),
    )
    expect(item?.textContent).toContain(unselectableStrategy.name)
    expect(card.textContent).not.toContain(unselectableStrategy.unselectableReason)

    await user.click(card)
    expect(onSelect).not.toHaveBeenCalled()

    card.focus()
    expect(document.activeElement).toBe(card)
    await user.keyboard('{Enter}')
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('워밍업 판정이 막으면 이유와 함께 고를 수 없다', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    renderList(loaded, {
      onSelect,
      judgeWarmup: (strategy) =>
        strategy.strategyId === selectableStrategy.strategyId
          ? { state: 'blocked', message: '평가할 구간이 남지 않습니다.' }
          : { state: 'ok' },
    })

    const card = cardNamed(selectableStrategy.name)
    expect(card.getAttribute('aria-disabled')).toBe('true')
    expect(descriptionOf(card)).toContain('평가할 구간이 남지 않습니다.')

    await user.click(card)
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('조회에 실패하면 카드 대신 안내와 다시 시도 버튼을 보인다', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    renderList(
      { status: 'failed', message: '전략 설정을 불러오지 못했습니다.', failureCount: 1, retrying: false },
      { onRetry },
    )

    // 카드가 하나도 없다. 임시 값이나 예비 등록표로 대신 동작하지 않는다(quickstart 9).
    for (const strategy of registryFixture.strategies) {
      expect(screen.queryByText(strategy.name)).toBeNull()
    }

    const alert = screen.getByRole('alert')
    expect(alert.textContent).toContain('전략 설정을 불러오지 못했습니다.')

    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('재시도 뒤에도 실패하면 초점이 다시 시도 버튼으로 돌아온다', () => {
    const onRetry = vi.fn()
    const { rerender } = renderList(
      { status: 'failed', failureCount: 1, retrying: false },
      { onRetry },
    )

    // 첫 실패에는 초점을 빼앗지 않는다.
    expect(document.activeElement).not.toBe(screen.getByRole('button', { name: '다시 시도' }))

    rerender(
      <StrategyCardList
        state={{ status: 'failed', failureCount: 2, retrying: false }}
        selectedStrategyId={null}
        onSelect={vi.fn()}
        onRetry={onRetry}
        judgeWarmup={allOk}
      />,
    )

    expect(document.activeElement).toBe(screen.getByRole('button', { name: '다시 시도' }))
  })

  it('재시도에 성공하면 초점이 새 카드 영역으로 옮겨 간다', () => {
    const { rerender } = renderList({ status: 'failed', failureCount: 1, retrying: false })

    rerender(
      <StrategyCardList
        state={loaded}
        selectedStrategyId={null}
        onSelect={vi.fn()}
        onRetry={vi.fn()}
        judgeWarmup={allOk}
      />,
    )

    expect(document.activeElement).toBe(screen.getByRole('radiogroup', { name: '전략 고르기' }))
  })

  it('고른 카드가 고를 수 없게 되어도 고른 사실을 계속 전달한다', () => {
    // 고른 뒤에 최소 평가 기간이 바뀌어 워밍업 판정이 막히는 경우다(FR-011a). 테두리로는 선택
    // 상태가 보이는데 스크린리더에만 전달되지 않으면 같은 사실이 보는 사람에 따라 달라진다.
    renderList(loaded, {
      selectedStrategyId: selectableStrategy.strategyId,
      judgeWarmup: () => ({ state: 'blocked', message: '평가할 구간이 남지 않습니다.' }),
    })

    const card = cardNamed(selectableStrategy.name)
    expect(card.getAttribute('aria-disabled')).toBe('true')
    expect(card.getAttribute('aria-checked')).toBe('true')
    expect(card.getAttribute('data-selected')).toBe('true')
  })

  it('워밍업을 판정하지 못한 것을 통과로 접지 않는다', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    renderList(loaded, {
      onSelect,
      judgeWarmup: () => ({ state: 'unknown', message: '분석 기간을 아직 확인하지 못했습니다.' }),
    })

    const card = cardNamed(selectableStrategy.name)

    // 판정하지 못했다는 사실이 화면에 남는다. 말하지 않으면 사용자는 판정을 통과한 줄로 읽는다.
    // 모든 카드가 같은 문장이라 목록 아래에 한 번 적히고, 카드는 그 문장을 설명으로 가리킨다.
    expect(screen.getByText('분석 기간을 아직 확인하지 못했습니다.')).toBeTruthy()
    const describedBy = (card.getAttribute('aria-describedby') ?? '').split(' ')
    expect(
      describedBy.some(
        (id) =>
          document.getElementById(id)?.textContent === '분석 기간을 아직 확인하지 못했습니다.',
      ),
    ).toBe(true)

    // 다만 "판정하지 못했다"는 "고를 수 없다"가 아니다. 등록표가 내린 판정을 뒤집지 않는다.
    expect(card.getAttribute('aria-disabled')).toBe('false')
    await user.click(card)
    expect(onSelect).toHaveBeenCalledWith(selectableStrategy.strategyId)
  })

  it('이유 없이 고를 수 없는 카드를 조용히 넘기지 않는다', () => {
    // 계약은 selectable 이 false 면 이유를 반드시 싣는다. 싣지 않은 응답은 계약 위반이며,
    // 화면이 이유 없는 비활성 카드로 덮어 버리면 사용자는 무엇을 고쳐야 할지 알 수 없다.
    const withoutReason: StrategyView = { ...unselectableStrategy, unselectableReason: undefined }

    renderList({
      status: 'loaded',
      registry: { ...registryFixture, strategies: [withoutReason] },
    })

    const card = cardNamed(unselectableStrategy.name)
    expect(card.getAttribute('aria-disabled')).toBe('true')
    expect(descriptionOf(card)).toContain('고를 수 없는 이유를 확인하지 못했습니다.')
  })

  it('사유는 카드마다 되풀이하지 않고 번호를 붙여 목록 아래에 한 번씩 적는다', () => {
    const shared = '같은 사유 문장입니다.'
    renderList(loaded, {
      judgeWarmup: () => ({ state: 'blocked', message: shared }),
    })

    // 같은 문장은 한 번만 적히고, 걸린 전략 이름이 앞에 선다.
    const items = sharedItems().filter((node) => node.textContent?.includes(shared))
    expect(items).toHaveLength(1)
    // 고를 수 없는 사유는 경고 표시다. 판정 불가(중립)와 섞이지 않는다.
    expect(items[0].querySelector('[data-tone]')?.getAttribute('data-tone')).toBe('blocking')
    for (const strategy of registryFixture.strategies) {
      expect(items[0].textContent).toContain(strategy.name)
      const card = cardNamed(strategy.name)
      expect(card.textContent).not.toContain(shared)
      expect(descriptionOf(card)).toContain(shared)
    }
  })

  it('판정하지 못했을 뿐인 사유는 경고가 아니라 중립 표시로 둔다', () => {
    renderList(loaded, {
      judgeWarmup: () => ({ state: 'unknown', message: '평가 기간을 아직 모릅니다.' }),
    })

    const item = sharedItems().find((node) =>
      node.textContent?.includes('평가 기간을 아직 모릅니다.'),
    )
    expect(item?.querySelector('[data-tone]')?.getAttribute('data-tone')).toBe('pending')
    // 고를 수 있다는 판정은 바뀌지 않는다.
    expect(cardNamed(selectableStrategy.name).getAttribute('aria-disabled')).toBe('false')
    expect(
      cardNamed(selectableStrategy.name).querySelector('[data-tone]')?.getAttribute('data-tone'),
    ).toBe('pending')
  })

  it('불러오는 중에는 고를 수 있는 카드가 없다', () => {
    renderList({ status: 'loading' })

    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(screen.getByRole('status').textContent).toContain('불러오는 중')
  })
})

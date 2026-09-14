import { readFileSync } from 'node:fs'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { previewFixture } from '@/test/paramSearchFixtures'
import type { SearchSpaceSelection, StrategySpaceSummary } from '@/types/paramSearch'
import { SpaceEditor } from './SpaceEditor'

/** 등록표에 없는, 이 테스트에서 지어낸 전략과 축. 화면이 이것을 알 방법은 응답뿐이다. */
const inventedSpace: StrategySpaceSummary[] = [
  {
    strategyId: 'MOON_PHASE_BLEND',
    strategyName: '달 위상 혼합',
    axes: [
      {
        axis: 'phaseDepth',
        label: '위상 깊이',
        unit: '단계',
        values: [1, 2, 3, 4],
        defaultValue: 2,
        allowedRange: { min: 1, max: 9, step: 1, values: null },
      },
    ],
    combinations: 4,
  },
]

/** 007 운영 코드(프론트). 테스트와 픽스처는 FR-025의 명시적 예외라 뺀다. */
const productionSources = [
  'src/api/paramSearch.ts',
  'src/types/paramSearch.ts',
  'src/hooks/useParamSearchStatus.ts',
  'src/utils/paramSearchLabels.ts',
  'src/pages/ParamSearchPage.tsx',
  'src/components/paramsearch/PreflightPanel.tsx',
  'src/components/paramsearch/ProvisionalBadge.tsx',
  'src/components/paramsearch/RegistryChangedDialog.tsx',
  'src/components/paramsearch/SpaceEditor.tsx',
]

/**
 * 등록표의 전략 ID와 이름. 원본은
 * `backend/src/main/resources/registry/parameter-registry-provisional.yml`이고 여기 적은 것은
 * **화면 코드에 나타나면 안 되는 낱말 목록**이다. 등록표에 전략이 늘면 이 목록에도 더한다.
 */
const registryWords = [
  'EQUAL_WEIGHT',
  'FIXED_WEIGHT',
  'INVERSE_VOLATILITY',
  'SECTOR_MOMENTUM',
  'MINIMUM_VARIANCE',
  '동일 비중',
  '고정 비중',
  '역변동성',
  '섹터 모멘텀',
  '최소분산',
]

/** 전략 분기의 흔한 형태. */
const branchingPatterns = [
  /switch\s*\(\s*[\w.]*[sS]trategyId/,
  /[\w.]*[sS]trategyId\s*===?\s*['"]/,
  /['"][A-Z][A-Z0-9_]{3,}['"]\s*===?\s*[\w.]*[sS]trategyId/,
]

/** 제어 컴포넌트를 여러 번 눌러 보기 위한 최소 껍데기. */
function Harness({ spaceSummary }: { spaceSummary: StrategySpaceSummary[] }) {
  const [value, setValue] = useState<SearchSpaceSelection>({})
  return <SpaceEditor spaceSummary={spaceSummary} value={value} onChange={setValue} />
}

/**
 * 전략이 늘어도 화면 코드를 고치지 않는다(T136, SC-010, FR-025).
 *
 * <p>동작만 보는 테스트로는 이것을 잡을 수 없다. 전략 ID 분기가 하나 생겨도 그 전략을 쓰는
 * 테스트는 여전히 통과한다. 그래서 <b>지어낸 전략으로 렌더되는지</b>와 <b>소스에 전략 지식이
 * 있는지</b>를 함께 본다.
 */
describe('SpaceEditor', () => {
  it('응답에만 있는 전략과 축도 코드 변경 없이 렌더된다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={vi.fn()} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('달 위상 혼합')
    expect(text).toContain('위상 깊이')
    expect(text).toContain('단계')
    expect(text).toContain('등록 허용 범위 1~9 · 간격 1')
    for (const value of ['1', '2', '3', '4']) {
      expect(screen.getByRole('button', { name: new RegExp(`^${value}`) })).toBeTruthy()
    }
  })

  it('축이 없는 전략은 조합이 1개라고 알린다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={previewFixture.spaceSummary} value={{}} onChange={vi.fn()} />,
    )

    expect(container.textContent).toContain('탐색 축이 없는 전략입니다. 조합은 1개입니다.')
  })

  it('처음에는 등록된 후보값이 모두 켜져 있다', () => {
    render(<SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={vi.fn()} />)

    for (const value of ['1', '2', '3', '4']) {
      const button = screen.getByRole('button', { name: new RegExp(`^${value}`) })
      expect(button.getAttribute('aria-pressed')).toBe('true')
    }
  })

  it('끈 값만 빠지고 남는 값은 모두 등록된 후보값이다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: /^3/ }))

    expect(onChange).toHaveBeenCalledTimes(1)
    const next = onChange.mock.calls[0][0] as SearchSpaceSelection
    expect(next.MOON_PHASE_BLEND.phaseDepth).toEqual([1, 2, 4])
    // 등록되지 않은 값이 끼어들 자리가 없다.
    for (const picked of next.MOON_PHASE_BLEND.phaseDepth) {
      expect(inventedSpace[0].axes[0].values).toContain(picked)
    }
  })

  it('마지막 하나는 끌 수 없다 — 축이 비면 돌릴 조합이 없다', async () => {
    const user = userEvent.setup()
    render(<Harness spaceSummary={inventedSpace} />)

    for (const value of ['1', '2', '3']) {
      await user.click(screen.getByRole('button', { name: new RegExp(`^${value}`) }))
    }
    const last = screen.getByRole('button', { name: /^4/ })
    expect(last.getAttribute('aria-pressed')).toBe('true')

    await user.click(last)

    expect(screen.getByRole('button', { name: /^4/ }).getAttribute('aria-pressed')).toBe('true')
  })

  it('전부 다시 켜면 좁히지 않은 것과 같아진다', async () => {
    const user = userEvent.setup()
    render(<Harness spaceSummary={inventedSpace} />)

    const target = () => screen.getByRole('button', { name: /^2/ })
    await user.click(target())
    expect(target().getAttribute('aria-pressed')).toBe('false')

    await user.click(target())
    expect(target().getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByText('4/4개 선택 · 등록 허용 범위 1~9 · 간격 1')).toBeTruthy()
  })

  it('기본 시작값을 표시하되 추천·최적이라고 쓰지 않는다', () => {
    const { container } = render(
      <SpaceEditor spaceSummary={inventedSpace} value={{}} onChange={vi.fn()} />,
    )
    const text = container.textContent ?? ''

    expect(text).toContain('기본 시작값')
    for (const banned of ['추천', '최적', '1위', '우승']) {
      expect(text).not.toContain(banned)
    }
  })

  it('007 화면 코드에 등록표의 전략 ID·전략명이 없다', () => {
    const violations: string[] = []

    for (const source of productionSources) {
      const content = readFileSync(source, 'utf-8')
      for (const word of registryWords) {
        if (content.includes(word)) violations.push(`${source} → ${word}`)
      }
    }

    expect(violations).toEqual([])
  })

  it('007 화면 코드에 strategyId 분기가 없다', () => {
    const violations: string[] = []

    for (const source of productionSources) {
      const content = readFileSync(source, 'utf-8')
      for (const pattern of branchingPatterns) {
        const matched = pattern.exec(content)
        if (matched) violations.push(`${source} → ${matched[0]}`)
      }
    }

    expect(violations).toEqual([])
  })
})

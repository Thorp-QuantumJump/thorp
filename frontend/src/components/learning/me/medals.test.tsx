import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { learnerOverview } from '@/test/learningMeFixtures'
import { ArticleStamp } from './ArticleStamp'
import { BadgeMedal } from './BadgeMedal'
import { LevelEmblem } from './LevelEmblem'
import { LevelPath, LevelPathMove } from './LevelPath'
import { StageMedal } from './StageMedal'

/**
 * 013 T193·T205. 레벨 표장·레벨 길·단계 완주 메달·배지 메달(design-notes §3.3·§4·§5·§6), 글 도장의 '방금 찍힌
 * 도장'과 빛 점.
 */

const MEDALS_CSS = 'components/learning/me/medals.module.css'
const STAMP_CSS = 'components/learning/me/ArticleStamp.module.css'

describe('메달과 표장', () => {
  it('모두 그림이라 보조 기술에 읽히지 않는다', () => {
    const { container } = render(
      <>
        <LevelEmblem level={3} size={96} />
        <StageMedal stageNumber={1} size={96} state="earned" />
        <BadgeMedal kind="read" size={76} state="notYet" progress={60} />
        <BadgeMedal kind="all" size={32} state="preparing" />
      </>,
    )
    const svgs = container.querySelectorAll('svg')
    expect(svgs).toHaveLength(4)
    for (const svg of svgs) expect(svg.getAttribute('aria-hidden')).toBe('true')
  })

  it('같은 그림을 여러 번 그려도 그라디언트 식별자가 겹치지 않는다(useId)', () => {
    const { container } = render(
      <>
        <LevelEmblem level={2} size={64} />
        <LevelEmblem level={2} size={64} />
        <BadgeMedal kind="terms" size={76} state="earned" />
        <BadgeMedal kind="terms" size={76} state="earned" />
      </>,
    )
    const ids = Array.from(
      container.querySelectorAll('linearGradient, radialGradient, clipPath'),
    ).map((el) => el.id)
    expect(ids.length).toBeGreaterThan(4)
    expect(new Set(ids).size).toBe(ids.length)
    for (const id of ids) expect(id).toMatch(/^[a-zA-Z0-9_-]+$/)
  })

  it("'준비 중'만 점선이고 '아직 받지 않음'은 같은 선 규칙의 실선이며 진행 호가 있다", () => {
    const { container } = render(
      <>
        <BadgeMedal kind="first" size={76} state="notYet" progress={30} />
        <BadgeMedal kind="first" size={76} state="preparing" />
      </>,
    )
    const [notYet, preparing] = container.querySelectorAll('svg')
    const outline = (svg: Element) => svg.querySelector('path[stroke-width]')!
    expect(outline(notYet).getAttribute('stroke-dasharray')).toBeNull()
    expect(outline(preparing).getAttribute('stroke-dasharray')).not.toBeNull()
    expect(outline(notYet).getAttribute('class')).toBe(outline(preparing).getAttribute('class'))
    expect(
      notYet.querySelector('[stroke-dasharray$=" 100"]')?.getAttribute('stroke-dasharray'),
    ).toBe('30 100')
    expect(preparing.querySelector('[stroke-dasharray$=" 100"]')).toBeNull()
  })

  it('레벨 표장은 재질이 같고 레벨에 따라 안쪽 고리·눈금만 는다', () => {
    const lines = (level: number) => {
      const { container, unmount } = render(<LevelEmblem level={level} size={96} />)
      const count = container.querySelectorAll('line').length
      unmount()
      return count
    }
    expect(lines(2)).toBe(0)
    expect(lines(3)).toBe(12)
    expect(lines(4)).toBe(24)
  })

  it('단계 완주 메달은 받으면 단계 색 유리 조각, 아니면 실선 회색 아치와 진행 호다', () => {
    const { container } = render(
      <>
        <StageMedal stageNumber={2} size={96} state="earned" />
        <StageMedal stageNumber={2} size={96} state="notYet" progress={75} />
      </>,
    )
    const [earned, notYet] = container.querySelectorAll('svg')
    expect(earned.getAttribute('data-medal')).toBe('stage-earned')
    expect(earned.innerHTML.toLowerCase()).toContain('#b7dd6e')
    expect(notYet.getAttribute('data-medal')).toBe('stage-notYet')
    expect(notYet.textContent).toBe('2')
  })

  it('움직임은 줄이기 설정이 아닐 때만 있고, 강제 색 모드에서도 실선·점선이 남는다', () => {
    expect(
      cssMediaDeclarations(MEDALS_CSS, '(prefers-reduced-motion: no-preference)', '.animateArc'),
    ).toMatch(/animation:\s*arc-fill 700ms/)
    expect(cssDeclarations(MEDALS_CSS, '.arc')).not.toMatch(/animation/)
    expect(cssMediaDeclarations(MEDALS_CSS, '(forced-colors: active)', '.outline')).toMatch(
      /stroke:\s*CanvasText/,
    )
  })
})

describe('레벨 길', () => {
  it('보이는 가장 높은 레벨까지 이정표를 세우고 그 뒤는 점선과 사유 문장이며 도착 표시가 없다(FR-029)', () => {
    const { container } = render(<LevelPath level={learnerOverview.level} />)

    expect(screen.getByText('레벨 4부터는 단계가 더 공개된 뒤 닿을 수 있습니다.')).not.toBeNull()
    expect(container.querySelector('[aria-hidden="true"]')?.textContent).toMatch(
      /레벨 1.*레벨 2.*레벨 3/,
    )
    expect(container.textContent).not.toMatch(/레벨 4\s*600|도착|최고/)
  })

  it('완료 카드의 길은 받은 경험치만큼 점을 옮기고 그 움직임은 줄이기 설정이 아닐 때만 있다', () => {
    const { container } = render(
      <LevelPathMove level={learnerOverview.level} expBefore={180} moving />,
    )
    const svg = container.querySelector('svg')!
    expect(svg.getAttribute('aria-hidden')).toBe('true')
    expect(svg.getAttribute('style')).toMatch(/--from:\s*-/)
    expect(
      cssMediaDeclarations(
        'components/learning/me/LevelPath.module.css',
        '(prefers-reduced-motion: no-preference)',
        '.moving .mover',
      ),
    ).toMatch(/animation:\s*move 400ms 300ms/)
  })
})

describe('글 도장의 B13 변형', () => {
  it("'방금 찍힌 도장'은 더 기울고 속이 진하며 바깥 후광 한 겹을 두른다", () => {
    const { container } = render(
      <>
        <ArticleStamp state="done" size={32} disc checkOnly />
        <ArticleStamp state="done" size={32} disc checkOnly fresh />
      </>,
    )
    const [plain, fresh] = container.querySelectorAll('svg')
    expect(plain.querySelector('[data-part="halo"]')).toBeNull()
    expect(fresh.querySelector('[data-part="halo"]')).not.toBeNull()
    expect(fresh.hasAttribute('data-fresh')).toBe(true)
    expect(fresh.querySelector('g[transform]')?.getAttribute('transform')).toBe('rotate(-8 22 22)')
    expect(fresh.querySelector('[data-part="ink"]')?.getAttribute('fill-opacity')).toBe('0.45')
  })

  it('지금 이어 읽을 글의 도장은 진행 호 끝에 빛 점이 있고 읽는 중이 아니면 없다', () => {
    const { container } = render(
      <>
        <ArticleStamp state="reading" size={48} percent={62} tip />
        <ArticleStamp state="unread" size={48} tip />
      </>,
    )
    const [reading, unread] = container.querySelectorAll('svg')
    expect(reading.querySelector('[data-part="tip"]')).not.toBeNull()
    expect(unread.querySelector('[data-part="tip"]')).toBeNull()
    expect(
      cssMediaDeclarations(STAMP_CSS, '(prefers-reduced-motion: no-preference)', '.tip'),
    ).toMatch(/animation/)
  })
})

describe('레벨 길의 글자', () => {
  it('문턱이 앞쪽에 몰리면 가까운 이정표의 글자를 빼서 겹치지 않게 한다', () => {
    const thresholds = [0, 100, 300, 600, 1000, 1500, 2100, 2800, 3600]
    const level = {
      ...learnerOverview.level,
      current: 2,
      exp: 150,
      visibleMax: 9,
      unreachableFrom: 10,
      unreachableReason: 'MORE_ARTICLES',
      milestones: thresholds.map((threshold, index) => ({ level: index + 1, threshold })),
    }
    const { container } = render(<LevelPath level={level} />)

    const labels = container.textContent?.match(/레벨 \d/g) ?? []
    // 이정표는 아홉 개이고, 글자는 겹치지 않는 것만 남는다. 가장 높은 레벨의 글자는 늘 있다.
    expect(container.querySelectorAll('svg[class*="flagArt"]')).toHaveLength(9)
    expect(labels.length).toBeLessThan(9)
    expect(labels).toContain('레벨 9')
    expect(
      cssMediaDeclarations(
        'components/learning/me/LevelPath.module.css',
        '(max-width: 719px)',
        '.firstFlag .flagLabel',
      ),
    ).toMatch(/display:\s*none/)
  })

  it('완료 카드의 길은 지금 닿을 수 없는 레벨에 이정표와 글자를 두지 않는다(FR-029)', () => {
    const level = {
      ...learnerOverview.level,
      current: 3,
      exp: 420,
      visibleMax: 3,
      unreachableFrom: 4,
    }
    const { container } = render(<LevelPathMove level={level} expBefore={390} moving={false} />)

    expect(container.textContent).not.toContain('레벨 4')
    expect(container.querySelector('[stroke-dasharray="6 6"]')).not.toBeNull()
  })
})

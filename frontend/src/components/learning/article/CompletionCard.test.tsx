import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import type { ComponentProps } from 'react'
import { allDone, completedRecord, completionResult, learnerOverview, resumeNext } from '@/test/learningMeFixtures'
import { CompletionCard } from './CompletionCard'
import {
  expLabel,
  gainedNotice,
  stageCompletedSentence,
  type GainedEntry,
} from './completionText'

/**
 * 013 T187·T190. 완료 카드의 문구 분기와 목업 '글 끝'의 글자(FR-021·FR-030, design-notes §3.4,
 * quickstart 10-1·10-6).
 */

let probedState: unknown

function Probe() {
  const location = useLocation()
  probedState = location.state
  return <p>도착 {location.pathname}</p>
}

type CardProps = ComponentProps<typeof CompletionCard>

/** 이번 완료로 2단계를 다 읽고 '2단계 완주' 배지와 레벨 3을 받았다. */
const completionEntry: GainedEntry = {
  gained: completionResult.gained,
  receivedAt: '2026-11-20T03:10:00Z',
  stageProgress: completionResult.stageProgress,
}

const baseProps: CardProps = {
  completedAt: completedRecord.completedAt!,
  stageProgress: completedRecord.stageProgress,
  continueReading: resumeNext,
  gained: [],
  terms: ['너울 높이'],
  mastered: false,
  quiz: <p>확인 문제 자리</p>,
  next: { articleId: 'tide-reading', number: '2-4', title: '물때 읽기' },
}

function renderCard(overrides: Partial<CardProps> = {}) {
  return render(
    <MemoryRouter initialEntries={['/learn/swell-reading']}>
      <Routes>
        <Route
          path="/learn/swell-reading"
          element={<CompletionCard {...baseProps} {...overrides} />}
        />
        <Route path="*" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CompletionCard', () => {
  it('제목은 서울 날짜의 완료 날짜이고, 단계 진행을 n편 중 m편 읽음으로 보인다', () => {
    renderCard()

    const card = screen.getByRole('region', { name: '완료 · 2026-11-20' })
    expect(within(card).getByRole('heading', { level: 2 }).textContent).toBe('완료 · 2026-11-20')
    expect(within(card).getByText('2단계 · 4편 중 2편 읽음')).not.toBeNull()
  })

  it('단계를 다 읽었으면 완주 · n편이다', () => {
    renderCard({ stageProgress: completionResult.stageProgress })

    expect(screen.getByText('2단계 · 완주 · 4편')).not.toBeNull()
  })

  it('용어가 있는 글만 익힐 용어 줄이 있고 익힘이면 표시를 붙인다', () => {
    const { unmount } = renderCard({ mastered: true })
    expect(screen.getByText('이 글에서 익힐 용어')).not.toBeNull()
    expect(screen.getByText('· 익힘')).not.toBeNull()
    unmount()

    renderCard({ terms: [] })
    expect(screen.queryByText('이 글에서 익힐 용어')).toBeNull()
  })

  it('다시 연 완료 글처럼 받은 것이 없으면 얻은 것 구역과 상태 문장을 그리지 않는다', () => {
    renderCard()

    expect(screen.queryByText('이번에 받은 경험치')).toBeNull()
    expect(screen.queryByText('새 배지')).toBeNull()
    expect(screen.queryByText(/새 배지:|되었습니다/)).toBeNull()
  })

  it('받은 것이 있으면 새 배지·레벨 칸과 받은 경험치, 단계 완주로 시작하는 상태 문장을 보인다', () => {
    renderCard({ stageProgress: completionResult.stageProgress, gained: [completionEntry] })

    const badgeColumn = screen.getByText('새 배지').parentElement as HTMLElement
    expect(within(badgeColumn).getByText('2단계 완주')).not.toBeNull()
    expect(within(badgeColumn).getByText('받음 2026-11-20')).not.toBeNull()
    expect(within(badgeColumn).getByText('새로 받음')).not.toBeNull()

    const levelColumn = screen.getByText('레벨', { selector: 'span' }).parentElement as HTMLElement
    expect(within(levelColumn).getByText('레벨 3')).not.toBeNull()

    const list = screen.getByRole('list', { name: '이번에 받은 경험치' })
    expect(within(list).getByText('글 완료')).not.toBeNull()
    expect(within(list).getByText('2단계 완주')).not.toBeNull()
    expect(within(list).getByText('+70')).not.toBeNull()

    expect(
      screen.getByText('2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다.'),
    ).not.toBeNull()
  })

  it('카드 안의 상태 문장은 보이는 글자일 뿐 알림 영역이 아니다', () => {
    renderCard({ gained: [completionEntry] })

    // 알림 영역은 글 끝(ArticleEnd)이 카드보다 먼저 둔다. 카드가 따로 두면 같은 알림이 두 번 읽힌다.
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('다음 글이 있으면 다음 글 카드다', () => {
    renderCard()

    expect(screen.getByRole('link', { name: /다음 글.*2-4.*물때 읽기/ })).not.toBeNull()
    expect(screen.queryByText(/모두 읽었습니다/)).toBeNull()
  })

  it('다음 글이 없고 읽는 중 글이 있으면 이어서 읽기가 learningResume: true를 싣는다', async () => {
    const user = userEvent.setup()
    renderCard({
      next: null,
      continueReading: resumeNext,
      learningReturn: { path: '/market-flow', label: '' },
    })

    await user.click(screen.getByRole('link', { name: /이어서 읽기.*2-4.*물때 읽기/ }))

    expect(await screen.findByText('도착 /learn/tide-reading')).not.toBeNull()
    // 위치는 싣지 않는다. 글 화면이 그 글의 기록에서 읽는다(data-model §15).
    expect(probedState).toEqual({
      learningResume: true,
      learningReturn: { path: '/market-flow', label: '' },
    })
  })

  it('처음 읽을 글이면 위치 표시 없이 맨 위에서 연다', async () => {
    const user = userEvent.setup()
    renderCard({
      next: null,
      continueReading: { ...resumeNext, kind: 'START', percent: null },
    })

    await user.click(screen.getByRole('link', { name: /이어서 읽기/ }))

    expect(await screen.findByText('도착 /learn/tide-reading')).not.toBeNull()
    expect(probedState).toBeNull()
  })

  it('공개된 글을 모두 읽었으면 그 문장만 보이고 배우기 목록 연결을 두지 않는다', () => {
    const { unmount } = renderCard({ next: null, continueReading: allDone })
    expect(
      screen.getByText('지금 공개된 글을 모두 읽었습니다. 다음 단계는 준비 중입니다.'),
    ).not.toBeNull()
    expect(screen.queryByRole('link', { name: /이어서 읽기/ })).toBeNull()
    // '배우기 목록으로'는 비로그인 글 끝의 연결이다(FR-021).
    expect(screen.queryByRole('link', { name: '배우기 목록으로' })).toBeNull()
    unmount()

    renderCard({ next: null, continueReading: { ...allDone, moreStagesPreparing: false } })
    expect(screen.getByText('지금 공개된 글을 모두 읽었습니다.')).not.toBeNull()
  })

  it('폭죽·소리·화면을 덮는 요소가 없다', () => {
    const { container } = renderCard({ gained: [completionEntry] })

    expect(container.querySelector('audio, video, canvas, dialog, [role="dialog"]')).toBeNull()
  })
})

describe('완료 카드 글자', () => {
  it('장부 줄 이름을 가르고 모르는 종류도 버리지 않는다', () => {
    expect(expLabel({ kind: 'ANSWER_CORRECT', points: 30 })).toBe('첫 답 정답')
    expect(expLabel({ kind: 'ALL_CORRECT', points: 20 })).toBe('모두 맞힘')
    expect(expLabel({ kind: 'NEW_KIND', points: 5 })).toBe('경험치')
  })

  it('레벨 숫자의 끝소리에 맞춰 이·가를 붙이고, 새로 얻은 것이 없으면 빈 문장이다', () => {
    const entry = (levelBefore: number, levelAfter: number): GainedEntry => ({
      gained: { ...completionResult.gained, badges: [], exp: [], levelBefore, levelAfter },
      receivedAt: '2026-11-20T03:10:00Z',
      stageProgress: null,
    })
    expect(gainedNotice([entry(3, 4)])).toBe('레벨 4가 되었습니다.')
    expect(gainedNotice([entry(5, 6)])).toBe('레벨 6이 되었습니다.')
    expect(gainedNotice([entry(3, 3)])).toBe('')
  })

  it('단계를 다 읽은 문장은 그 단계의 완주 경험치가 생긴 완료 응답에서만 짓는다', () => {
    expect(stageCompletedSentence(completionEntry)).toBe('2단계 글 4편을 모두 읽었습니다.')
    // 이미 다 읽었던 단계의 글을 다시 열었다. 새로 일어난 일이 아니다.
    expect(
      stageCompletedSentence({
        ...completionEntry,
        gained: { ...completionEntry.gained, exp: [{ kind: 'ARTICLE_COMPLETED', points: 20 }] },
      }),
    ).toBeNull()
    expect(stageCompletedSentence({ ...completionEntry, stageProgress: null })).toBeNull()
  })

  it('여러 응답에서 받은 것을 받은 순서대로 잇고 레벨은 마지막에 한 번만 말한다', () => {
    const later: GainedEntry = {
      gained: {
        exp: [],
        expTotal: 0,
        badges: [{ badgeKey: 'persistence', family: 'SPECIAL', name: '끈기' }],
        levelBefore: 3,
        levelAfter: 3,
      },
      receivedAt: '2026-11-20T03:20:00Z',
      stageProgress: null,
    }

    expect(gainedNotice([completionEntry, later])).toBe(
      '2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 새 배지: 끈기. 레벨 3이 되었습니다.',
    )
  })
})

/**
 * 013 T193·T204·T208·T209. 완료 카드의 그림 장면과 다음 목표(design-notes §3.4·§5, 목업 '글 끝', quickstart 10-6).
 */
describe('CompletionCard — 그림 장면과 다음 목표', () => {
  it("다시 받은 '나의 학습'으로 레벨 칸 아래 경험치와 다음 레벨까지, 새 배지 칸 아래 다음 배지를 둔다(T208)", () => {
    renderCard({
      gained: [completionEntry],
      stageProgress: completionResult.stageProgress,
      overview: learnerOverview,
      freshCompletion: true,
    })

    const badgeColumn = screen.getByText('새 배지').parentElement!
    expect(within(badgeColumn).getByText('다음 배지')).not.toBeNull()
    expect(badgeColumn.textContent).toContain('모두 맞힘 1편')
    expect(within(badgeColumn).getByText('1편 중 0편')).not.toBeNull()
    const levelColumn = screen.getByText('레벨', { selector: 'span' }).parentElement!
    expect(levelColumn.textContent).toContain('경험치 250 · 다음 레벨까지 50')
    expect(
      screen.getByText(
        '2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다. 다음 레벨까지 50, 다음 배지(모두 맞힘 1편)까지 1편입니다.',
      ),
    ).not.toBeNull()
  })

  it("'나의 학습'을 받지 못하면 다음 목표의 그림과 글자만 빠지고 B12의 글자는 그대로다(T204)", () => {
    renderCard({ gained: [completionEntry], stageProgress: completionResult.stageProgress, overview: null })

    expect(screen.queryByText('다음 배지')).toBeNull()
    expect(screen.queryByText(/다음 레벨까지/)).toBeNull()
    expect(screen.getByText('2단계 글 4편을 모두 읽었습니다. 새 배지: 2단계 완주. 레벨 3이 되었습니다.')).not.toBeNull()
    expect(screen.getByText('레벨 3')).not.toBeNull()
  })

  it('레벨이 오르지 않았고 새 배지도 없으면 다음 목표를 따로 한 칸에 둔다', () => {
    const answered: GainedEntry = {
      gained: { exp: [{ kind: 'ANSWER_CORRECT', points: 30 }], expTotal: 30, badges: [], levelBefore: 2, levelAfter: 2 },
      receivedAt: '2026-11-20T03:12:00Z',
      stageProgress: null,
    }
    renderCard({ gained: [answered], overview: learnerOverview })

    const goal = screen.getByRole('group', { name: '다음 목표' })
    expect(goal.textContent).toContain('경험치 250 · 다음 레벨까지 50')
    expect(goal.textContent).toContain('모두 맞힘 1편')
  })

  it('그림 장면은 모두 보조 기술에 읽히지 않고, 방금 완료했을 때만 마지막 칸이 방금 찍힌 도장이다', () => {
    const { unmount } = renderCard({
      gained: [completionEntry],
      stageProgress: completionResult.stageProgress,
      overview: learnerOverview,
      freshCompletion: true,
    })
    const card = screen.getByRole('region', { name: '완료 · 2026-11-20' })
    for (const svg of card.querySelectorAll('svg')) {
      expect(svg.closest('[aria-hidden="true"]')).not.toBeNull()
    }
    const path = card.querySelector('[data-stage-path="2"]')!
    expect(path.querySelectorAll('[data-stamp="done"]')).toHaveLength(4)
    expect(path.querySelectorAll('[data-fresh]')).toHaveLength(1)
    expect(path.querySelector('[data-medal="stage-earned"]')).not.toBeNull()
    unmount()

    // 다시 연 완료 글은 최종 모습 그대로다.
    renderCard({ overview: learnerOverview })
    const reopened = screen.getByRole('region', { name: '완료 · 2026-11-20' })
    expect(reopened.querySelectorAll('[data-fresh]')).toHaveLength(0)
    expect(reopened.querySelector('[data-stage-path="2"]')?.querySelectorAll('[data-stamp="done"]')).toHaveLength(2)
    expect(reopened.querySelector('[data-medal="stage-notYet"]')).not.toBeNull()
  })

  it('카드 뒤에 그 단계 색의 옅은 빛을 깐다(T209)', () => {
    renderCard()
    const card = screen.getByRole('region', { name: '완료 · 2026-11-20' })
    expect(card.getAttribute('style')).toMatch(/--stage:\s*#B7DD6E/i)
    expect(cssDeclarations('components/learning/article/CompletionCard.module.css', '.card::before')).toMatch(
      /var\(--stage/,
    )
  })

  it('움직임은 줄이기 설정이 아닐 때만 있고 950ms 안에 끝나며 되풀이하지 않는다(design-notes §5)', () => {
    const css = 'components/learning/article/CompletionScene.module.css'
    const media = '(prefers-reduced-motion: no-preference)'
    const timings: [string, number][] = [
      ['.fresh svg', 540],
      ['.planted svg', 700],
      ['.flipFrom', 450],
      ['.flipTo', 650],
      ['.rolling', 700],
    ]
    for (const [selector, end] of timings) {
      const declaration = cssMediaDeclarations(css, media, selector)
      const [duration, delay] = [...declaration.matchAll(/(\d+)ms/g)].map((match) => Number(match[1]))
      expect(duration + delay).toBe(end)
      expect(duration + delay).toBeLessThanOrEqual(950)
      expect(declaration).not.toMatch(/infinite/)
    }
    expect(cssDeclarations(css, '.rollStrip')).not.toMatch(/animation/)
  })
})

describe('CompletionCard — 레벨 갈래 배지와 단계 완주 메달', () => {
  it('레벨이 오른 응답의 레벨 갈래 배지는 새 배지로 다시 말하지 않고, 큰 그림은 단계 완주 배지가 맡는다', () => {
    const entry: GainedEntry = {
      gained: {
        ...completionResult.gained,
        badges: [
          { badgeKey: 'level-3', family: 'LEVEL', name: '레벨 3' },
          { badgeKey: 'read-5', family: 'READ', name: '읽은 글 5편' },
          { badgeKey: 'stage-swell-and-risk', family: 'STAGE', name: '2단계 완주' },
        ],
      },
      receivedAt: '2026-11-20T03:10:00Z',
      stageProgress: completionResult.stageProgress,
    }
    renderCard({ gained: [entry], stageProgress: completionResult.stageProgress })

    const badgeColumn = screen.getByText('새 배지').parentElement!
    expect(badgeColumn.textContent).not.toContain('레벨 3')
    const items = badgeColumn.querySelectorAll('b')
    expect([...items].map((item) => item.textContent)).toEqual(['2단계 완주', '읽은 글 5편'])
    expect(badgeColumn.querySelector('[data-medal="stage-earned"]')).not.toBeNull()
    expect(
      screen.getByText(
        '2단계 글 4편을 모두 읽었습니다. 새 배지: 읽은 글 5편. 새 배지: 2단계 완주. 레벨 3이 되었습니다.',
      ),
    ).not.toBeNull()
  })

  it('단계 완주 배지를 받은 단계면 지금 다 읽지 않았어도 메달은 받은 모습이다(US6 시나리오 8)', () => {
    const overview = {
      ...learnerOverview,
      badges: learnerOverview.badges.map((badge) =>
        badge.badgeKey === 'stage-swell-and-risk'
          ? { ...badge, status: 'EARNED', earnedAt: '2026-11-18T00:00:00Z', progress: null }
          : badge,
      ),
    }
    renderCard({
      stageProgress: { stageNumber: 2, completedCount: 4, publishedCount: 5, stageCompleted: false },
      overview,
    })

    const path = document.querySelector('[data-stage-path="2"]')!
    expect(path.querySelector('[data-medal="stage-earned"]')).not.toBeNull()
    expect(screen.getByText('2단계 · 5편 중 4편 읽음')).not.toBeNull()
  })

  it('다음 레벨에 지금 닿을 수 없으면 레벨 길 아래에 FR-029 문장을 두고 다음 목표로 말하지 않는다', () => {
    const answered: GainedEntry = {
      gained: { exp: [{ kind: 'ANSWER_CORRECT', points: 30 }], expTotal: 30, badges: [], levelBefore: 3, levelAfter: 3 },
      receivedAt: '2026-11-20T03:12:00Z',
      stageProgress: null,
    }
    renderCard({
      gained: [answered],
      overview: { ...learnerOverview, level: { ...learnerOverview.level, current: 3, visibleMax: 3, unreachableFrom: 4 } },
    })

    const goal = screen.getByRole('group', { name: '다음 목표' })
    expect(goal.textContent).toContain('레벨 4부터는 단계가 더 공개된 뒤 닿을 수 있습니다.')
    expect(goal.querySelector('svg')?.textContent).not.toContain('레벨 4')
    expect(screen.getByText('다음 배지(모두 맞힘 1편)까지 1편입니다.', { exact: false }).textContent).not.toContain(
      '다음 레벨까지 50,',
    )
  })

  it("'나의 학습'이 늦게 오면 레벨 길의 점을 옮기지 않는다(design-notes §5)", () => {
    const answered: GainedEntry = {
      gained: { exp: [{ kind: 'ANSWER_CORRECT', points: 30 }], expTotal: 30, badges: [], levelBefore: 2, levelAfter: 2 },
      receivedAt: '2026-11-20T03:12:00Z',
      stageProgress: null,
    }
    const { unmount } = renderCard({ gained: [answered], overview: learnerOverview })
    expect(document.querySelector('[class*="moving"]')).not.toBeNull()
    unmount()

    renderCard({ gained: [answered], overview: learnerOverview, animateGoal: false })
    expect(document.querySelector('[class*="moving"]')).toBeNull()
  })

  it('좁은 화면에서는 칸을 한 줄씩 쌓고 큰 그림을 72px로 줄이며 도장 칸이 한 줄에 들어가게 줄어든다(design-notes §6)', () => {
    expect(
      cssMediaDeclarations('components/learning/article/CompletionCard.module.css', '(max-width: 719px)', '.expColumn'),
    ).toMatch(/flex:\s*1 1 100%/)
    expect(
      cssMediaDeclarations('components/learning/article/CompletionScene.module.css', '(max-width: 719px)', '.roll'),
    ).toMatch(/width:\s*72px/)
    expect(cssDeclarations('components/learning/article/CompletionScene.module.css', '.stamps')).toMatch(
      /grid-auto-columns:\s*minmax\(14px, 32px\)/,
    )
  })
})

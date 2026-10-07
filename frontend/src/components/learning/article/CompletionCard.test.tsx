import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { ComponentProps } from 'react'
import { allDone, completedRecord, completionResult, resumeNext } from '@/test/learningMeFixtures'
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

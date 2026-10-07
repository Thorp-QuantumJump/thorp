import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { cssDeclarations } from '@/test/cssRule'
import { stageOneArticleAdded, stageTwoReleased } from '@/test/learningFixtures'
import { renderWithRouter } from '@/test/renderWithRouter'
import { ReleaseNotice } from './ReleaseNotice'

/** 013 T139·T141. 공개 안내(FR-031 '공개 안내', quickstart 11-2). */

describe('ReleaseNotice', () => {
  it('합친 한 줄과 단계 보기·변경 기록 연결, [닫기]를 두고 상태 알림이 아니다', () => {
    renderWithRouter(
      <ReleaseNotice
        notices={[stageTwoReleased, stageOneArticleAdded]}
        signedIn={false}
        onDismiss={() => {}}
        onOpenChangeLog={() => {}}
      />,
    )

    // 어느 화면에서 그려도 배우기 목록의 그 단계 자리를 가리킨다.
    expect(screen.getByRole('link', { name: '2단계 보기' }).getAttribute('href')).toBe('/learn#stage-2')
    expect(screen.getByRole('link', { name: '변경 기록' })).not.toBeNull()
    expect(screen.getByRole('button', { name: '공개 안내 닫기' }).textContent).toBe('닫기')
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.querySelector('[aria-live]')).toBeNull()
  })

  it('[닫기]는 합친 줄에 든 안내를 모두 닫는다', async () => {
    const user = userEvent.setup()
    const onDismiss = vi.fn()
    renderWithRouter(
      <ReleaseNotice
        notices={[stageTwoReleased, stageOneArticleAdded]}
        signedIn
        onDismiss={onDismiss}
        onOpenChangeLog={() => {}}
      />,
    )

    await user.click(screen.getByRole('button', { name: '공개 안내 닫기' }))

    expect(onDismiss).toHaveBeenCalledWith(['stage:swell-and-risk', 'article:dock-fees'])
  })

  it('안내 줄이 없으면 아무것도 그리지 않는다', () => {
    const { container } = renderWithRouter(
      <ReleaseNotice notices={[]} signedIn onDismiss={() => {}} onOpenChangeLog={() => {}} />,
    )

    expect(container.textContent).toBe('')
  })

  it('움직임을 넣지 않는다', () => {
    expect(cssDeclarations('components/learning/curriculum/ReleaseNotice.module.css', '.notice')).not.toMatch(
      /animation|transition/,
    )
  })
})

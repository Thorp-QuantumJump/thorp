import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { LearningExample } from '@/types/learning'
import { harborArticle, tideArticle } from '@/test/learningFixtures'
import { ExampleEntry } from './ExampleEntry'

/**
 * 013 T032. 예시가 002 selection-handoff §2 모양 그대로 넘어가는지 본다(quickstart 4-3의 013 쪽·4-4).
 * 002가 그 값을 받아 채우는 것은 `pages/SectorSelectionLearningEntry.test.tsx`가 본다.
 */

let arrived: { pathname: string; state: unknown } | null = null

function Arrival() {
  const location = useLocation()
  arrived = { pathname: location.pathname, state: location.state }
  return <p>섹터 선택 화면</p>
}

function renderEntry(articleId: string, example: LearningExample) {
  arrived = null
  return render(
    <MemoryRouter initialEntries={[`/learn/${articleId}`]}>
      <Routes>
        <Route
          path="/learn/:articleId"
          element={<ExampleEntry articleId={articleId} example={example} />}
        />
        <Route path="/diagnosis/sectors" element={<Arrival />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ExampleEntry', () => {
  it('누르면 /diagnosis/sectors로 가고 state가 계약 모양 그대로다', async () => {
    const user = userEvent.setup()
    renderEntry('tide-reading', tideArticle.example)

    const button = screen.getByRole('button', { name: '이 예시로 전략 진단하기' })
    expect(button.tagName).toBe('BUTTON')
    await user.click(button)

    expect(await screen.findByText('섹터 선택 화면')).not.toBeNull()
    expect(arrived?.pathname).toBe('/diagnosis/sectors')
    // toEqual이 아니라 JSON 동치로 본다. `name` 키가 undefined로라도 남으면 잡아야 한다.
    expect(JSON.stringify(arrived?.state)).toBe(
      JSON.stringify({
        sectorSelectionExample: {
          source: 'learning',
          topicId: 'tide-reading',
          selected: [
            { code: 'ALPHA_DOCK', weightTenths: 625 },
            { code: 'BETA_DOCK', weightTenths: 375 },
          ],
        },
      }),
    )
  })

  it('대응표에 없는 섹터가 있으면 버튼 없이 빠진 코드를 알리고 state 없는 연결을 준다', async () => {
    const user = userEvent.setup()
    renderEntry('harbor-notes', harborArticle.example)

    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText(/GAMMA_DOCK/)).not.toBeNull()

    await user.click(screen.getByRole('link', { name: '빈 섹터 선택 화면으로 가기' }))

    expect(await screen.findByText('섹터 선택 화면')).not.toBeNull()
    expect(arrived?.state).toBeNull()
  })

  it('대응표를 확인할 수 없으면 그 사실을 알리고 버튼을 그리지 않는다', () => {
    renderEntry('harbor-notes', {
      available: false,
      sectors: [{ code: 'ALPHA_DOCK', name: null, weightTenths: 1000 }],
      missingSectorCodes: [],
      unavailableReason: 'SECTOR_MAP_NOT_READY',
    })

    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText(/섹터 목록을 확인할 수 없어/)).not.toBeNull()
    expect(
      screen.getByRole('link', { name: '빈 섹터 선택 화면으로 가기' }).getAttribute('href'),
    ).toBe('/diagnosis/sectors')
  })
})

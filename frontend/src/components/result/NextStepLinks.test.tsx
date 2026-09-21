import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { resultDetailCompleted } from '@/test/resultFixtures'
import { NextStepLinks } from './NextStepLinks'

const base = 'a'.repeat(32)
const target = 'b'.repeat(32)

function renderLinks(props: Partial<Parameters<typeof NextStepLinks>[0]> = {}) {
  return render(
    <MemoryRouter>
      <NextStepLinks config={resultDetailCompleted.config} token={base} {...props} />
    </MemoryRouter>,
  )
}

describe('결과 화면의 011 진입', () => {
  it('비교 대상이 열람 가능하면 compare를 함께 싣는다', () => {
    renderLinks({ compareToken: target, compareReady: true })
    expect(
      screen.getByRole('link', { name: '경제환경이 비슷했던 과거 보기' }).getAttribute('href'),
    ).toBe(`/regime-link?base=${base}&compare=${target}`)
  })

  it('비교 대상이 아직 준비되지 않았으면 base만 싣는다', () => {
    renderLinks({ compareToken: target, compareReady: false })
    expect(
      screen.getByRole('link', { name: '경제환경이 비슷했던 과거 보기' }).getAttribute('href'),
    ).toBe(`/regime-link?base=${base}`)
  })
})

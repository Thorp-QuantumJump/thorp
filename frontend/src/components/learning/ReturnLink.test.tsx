import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { ReturnLink } from './ReturnLink'

/** 013 T150. 보조 연결로 보이고 누르면 그 경로로 이동한다(FR-009, term-help-ui §3). */

function Destination() {
  const location = useLocation()
  return (
    <p>
      돌아온 화면 {location.pathname}
      {location.search}
    </p>
  )
}

function renderLink(state: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/learn/synthetic-article', state }]}>
      <Routes>
        <Route path="/learn/:articleId" element={<ReturnLink state={state} />} />
        <Route path="*" element={<Destination />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ReturnLink', () => {
  it('돌아갈 자리가 없으면 아무것도 그리지 않는다', () => {
    const { container } = renderLink({ learningReturn: { path: '/login', label: '로그인' } })

    expect(container.textContent).toBe('')
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('버튼이 아닌 연결로 표의 문구를 보이고 누르면 쿼리 문자열까지 그대로 돌아간다', async () => {
    const user = userEvent.setup()
    renderLink({ learningReturn: { path: '/param-search?bundle=x', label: '무시되는 이름' } })

    const link = screen.getByRole('link', { name: /자동 탐색으로 돌아가기/ })
    expect(link.tagName).toBe('A')
    expect(link.getAttribute('href')).toBe('/param-search?bundle=x')
    expect(link.textContent).toBe('← 자동 탐색으로 돌아가기')
    expect(link.querySelector('[aria-hidden="true"]')?.textContent).toBe('←')
    expect(screen.queryByRole('button')).toBeNull()

    await user.click(link)

    expect(await screen.findByText('돌아온 화면 /param-search?bundle=x')).not.toBeNull()
  })
})

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { resolveLearningReturn, ReturnToDiagnosisLink } from './ReturnToDiagnosisLink'

/** 013 T041. term-help-ui §3의 세 조건, FR-009. */

function withReturn(path: unknown, label: unknown = '돌아갈 화면') {
  return { learningReturn: { path, label } }
}

describe('resolveLearningReturn', () => {
  it.each([['/strategy'], ['/diagnosis/sectors'], ['/results/BT-1']])(
    '허용 목록의 경로 %s는 돌아갈 자리다',
    (path) => {
      expect(resolveLearningReturn(withReturn(path))).toEqual({
        path,
        label: '돌아갈 화면',
      })
    },
  )

  it.each([
    ['state 없음', undefined],
    ['learningReturn 없음', { other: true }],
    ['경로가 문자열이 아님', withReturn(42)],
    ['이름이 문자열이 아님', withReturn('/strategy', null)],
    ['인증 화면', withReturn('/login')],
    ['허용 목록 밖', withReturn('/learn')],
    ['프로토콜 상대 주소', withReturn('//evil.example')],
    ['외부 주소', withReturn('https://evil.example/strategy')],
    ['역슬래시', withReturn('/results/\\evil.example')],
    ['결과 번호 없음', withReturn('/results/')],
    ['정규화하면 달라지는 경로', withReturn('/results/../login')],
    ['질의가 붙은 경로', withReturn('/strategy?next=/login')],
  ])('%s면 돌아갈 자리가 없다', (_, state) => {
    expect(resolveLearningReturn(state)).toBeNull()
  })
})

function Destination() {
  return <p>돌아온 화면 {useLocation().pathname}</p>
}

function renderLink(state: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/learn/synthetic-article', state }]}>
      <Routes>
        <Route path="/learn/:articleId" element={<ReturnToDiagnosisLink state={state} />} />
        <Route path="*" element={<Destination />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ReturnToDiagnosisLink', () => {
  it('돌아갈 자리가 없으면 아무것도 그리지 않는다', () => {
    const { container } = renderLink(withReturn('/login'))
    expect(container.textContent).toBe('')
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('버튼이 아닌 연결로 보이고 누르면 그 경로로 이동한다', async () => {
    const user = userEvent.setup()
    renderLink(withReturn('/strategy', '전략 선택'))

    const link = screen.getByRole('link', { name: /하던 진단으로 돌아가기/ })
    expect(link.tagName).toBe('A')
    expect(link.getAttribute('href')).toBe('/strategy')
    expect(link.textContent).toContain('전략 선택')
    expect(screen.queryByRole('button')).toBeNull()

    await user.click(link)

    expect(await screen.findByText('돌아온 화면 /strategy')).not.toBeNull()
  })
})

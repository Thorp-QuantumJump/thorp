import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FAB_POSITION_KEY } from './fabPosition'
import { clearMatchMedia, mockMatchMedia, renderAssistant } from './assistantTestSupport'

const api = vi.hoisted(() => ({
  fetchAssistantCapabilities: vi.fn(),
  askAssistant: vi.fn(),
}))
vi.mock('@/api/assistant', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/assistant')>()),
  fetchAssistantCapabilities: api.fetchAssistantCapabilities,
  askAssistant: api.askAssistant,
}))

/** spec 015 US3·FR-001·FR-002(T040). 버튼은 여백의 보조 요소이고, 끌어 옮기면 가까운 여백에 붙는다. */
describe('AssistantFab', () => {
  beforeEach(() => {
    window.localStorage.clear()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 })
    api.fetchAssistantCapabilities.mockReset()
    api.askAssistant.mockReset()
    api.fetchAssistantCapabilities.mockResolvedValue({ enabled: true, scopes: ['RESULT'] })
  })

  afterEach(() => {
    clearMatchMedia()
  })

  it('접근 이름은 질문하기이고 보이는 글자 없이 로봇 아이콘만 있으며 주 모양 버튼이 아니다', async () => {
    renderAssistant()

    const fab = await screen.findByRole('button', { name: '질문하기' })
    expect(fab.textContent).toBe('')
    expect(fab.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
    expect(fab.getAttribute('aria-expanded')).toBe('false')
    expect(document.querySelectorAll('[data-slot="button"][data-variant="default"]')).toHaveLength(0)
  })

  it('기능이 꺼진 환경에서는 버튼이 없다', async () => {
    api.fetchAssistantCapabilities.mockResolvedValue({ enabled: false, scopes: [] })
    renderAssistant()

    await waitFor(() => expect(api.fetchAssistantCapabilities).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: '질문하기' })).toBeNull()
  })

  it('로그인·관리 경로에서는 맥락이 있어도 버튼이 없다', async () => {
    renderAssistant({ path: '/login' })
    await waitFor(() => expect(api.fetchAssistantCapabilities).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: '질문하기' })).toBeNull()
  })

  it('맥락을 등록한 화면이 없으면 버튼이 없고 서버에 묻지도 않는다', () => {
    renderAssistant({ path: '/learn' })

    expect(screen.queryByRole('button', { name: '질문하기' })).toBeNull()
    expect(api.fetchAssistantCapabilities).not.toHaveBeenCalled()
  })

  it('기본 자리는 우측 하단이다', async () => {
    renderAssistant()

    const fab = await screen.findByRole('button', { name: '질문하기' })
    expect(fab.getAttribute('data-side')).toBe('right')
    expect(fab.style.top).toBe('720px')
    expect(fab.style.right).not.toBe('')
  })

  it('끌어서 왼쪽 여백 가까이 놓으면 그쪽에 붙고 자리가 저장되며, 그 끌기는 말풍선을 열지 않는다', async () => {
    renderAssistant()
    const fab = await screen.findByRole('button', { name: '질문하기' })

    fireEvent.pointerDown(fab, { pointerId: 1, button: 0, clientX: 1240, clientY: 740 })
    fireEvent.pointerMove(fab, { pointerId: 1, clientX: 400, clientY: 300 })
    fireEvent.pointerUp(fab, { pointerId: 1, clientX: 400, clientY: 300 })
    fireEvent.click(fab)

    expect(fab.getAttribute('data-side')).toBe('left')
    expect(fab.style.top).toBe('280px')
    expect(JSON.parse(window.localStorage.getItem(FAB_POSITION_KEY) ?? '{}')).toEqual({
      side: 'left',
      y: 280,
    })
    expect(screen.queryByRole('dialog', { name: '질문하기' })).toBeNull()
  })

  it('다시 그려도 저장한 자리에 있다', async () => {
    window.localStorage.setItem(FAB_POSITION_KEY, JSON.stringify({ side: 'left', y: 120 }))
    renderAssistant()

    const fab = await screen.findByRole('button', { name: '질문하기' })
    expect(fab.getAttribute('data-side')).toBe('left')
    expect(fab.style.top).toBe('120px')
  })

  it('살짝 눌렀다 떼는 것은 끌기가 아니라 열기다', async () => {
    renderAssistant()
    const fab = await screen.findByRole('button', { name: '질문하기' })

    fireEvent.pointerDown(fab, { pointerId: 1, button: 0, clientX: 1240, clientY: 740 })
    fireEvent.pointerMove(fab, { pointerId: 1, clientX: 1241, clientY: 741 })
    fireEvent.pointerUp(fab, { pointerId: 1, clientX: 1241, clientY: 741 })
    fireEvent.click(fab)

    expect(await screen.findByRole('dialog', { name: '질문하기' })).not.toBeNull()
    expect(fab.getAttribute('data-side')).toBe('right')
  })

  it('좁은 화면(≤600px)에서는 끌기가 무시되고 우측 하단 고정이다', async () => {
    mockMatchMedia(true)
    renderAssistant()
    const fab = await screen.findByRole('button', { name: '질문하기' })

    fireEvent.pointerDown(fab, { pointerId: 1, button: 0, clientX: 500, clientY: 700 })
    fireEvent.pointerMove(fab, { pointerId: 1, clientX: 40, clientY: 100 })
    fireEvent.pointerUp(fab, { pointerId: 1, clientX: 40, clientY: 100 })

    expect(fab.getAttribute('data-narrow')).toBe('true')
    expect(fab.style.top).toBe('')
    expect(window.localStorage.getItem(FAB_POSITION_KEY)).toBeNull()
  })

  it('Tab 으로 닿고 Enter 로 연다', async () => {
    const user = userEvent.setup()
    renderAssistant()
    await screen.findByRole('button', { name: '질문하기' })

    await user.tab()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('질문하기')
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('dialog', { name: '질문하기' })).not.toBeNull()
  })
})

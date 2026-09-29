import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import type { SectorCatalog, SectorSummary } from '@/types/sector'
import { SectorSelectionPage } from './SectorSelectionPage'

const apiMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchPriceSeries: vi.fn(),
}))

vi.mock('@/api/sectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/sectors')>('@/api/sectors')
  return { ...actual, ...apiMocks }
})

function sector(
  code: string,
  name: string,
  classification: SectorSummary['classification'],
): SectorSummary {
  return {
    code,
    name,
    classification,
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn: '2018-09-12',
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

function catalog(isSample: boolean): SectorCatalog {
  return {
    mapVersion: 'SEM-2026-09-10-001',
    isSample,
    sectors: [
      sector('BATTERY', '2차전지', 'THEME'),
      sector('AUTO', '자동차', 'INDUSTRY'),
      sector('SEMICONDUCTOR', '반도체', 'INDUSTRY'),
    ],
  }
}

/** AppHeader가 인증 상태를 읽으므로 화면 검증에 필요한 최소 컨텍스트만 준다. */
function renderPage() {
  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  return render(
    <MemoryRouter initialEntries={['/diagnosis/sectors']}>
      <AuthContext.Provider value={authValue}>
        <SectorSelectionPage />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

function loaded(isSample: boolean): SectorCatalogResult {
  return { status: 'loaded', catalog: catalog(isSample) }
}

const networkFailure: SectorCatalogResult = {
  status: 'failed',
  kind: 'network',
  code: null,
  message: '연결 실패',
  retryable: true,
}

describe('SectorSelectionPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 카드는 접힌 채로도 가격을 부른다. 그림 자체는 다른 검사가 본다.
    apiMocks.fetchPriceSeries.mockResolvedValue({
      status: 'notReady',
      message: '준비 중',
    })
  })

  it('목록을 표시 순서대로 보인다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()

    const items = await screen.findAllByRole('listitem')
    // 업종이 먼저, 그 안에서 한국어 이름 순이다. 응답 배열 순서와 다르다.
    expect(items.map((item) => item.getAttribute('data-sector-code'))).toEqual([
      'SEMICONDUCTOR',
      'AUTO',
      'BATTERY',
    ])
  })

  it('목록에는 대표 ETF 이름을, 보고 있는 대상에는 섹터 이름까지 보인다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()

    // 목록은 고를 대상을 가려내는 자리라 이름 하나로 짧게 둔다.
    const items = await screen.findAllByRole('listitem')
    expect(within(items[0]).getByText('KODEX 반도체')).toBeTruthy()

    // 어느 섹터의 상품인지는 '포함 종목'을 펼쳤을 때 보고 있는 대상을 설명하는 자리에서 말한다.
    expect(document.querySelector('[data-slot="main-subtitle"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '포함 종목 보기' }))
    const subtitle = await waitFor(() => document.querySelector('[data-slot="main-subtitle"]'))
    expect(subtitle?.textContent).toContain('반도체')
  })

  it('목록 역할을 명시해 스타일이 의미를 지우지 못하게 한다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()

    // `list-style: none`이 걸린 `<ul>`은 일부 브라우저·보조 기술 조합에서 목록 역할을 잃는다.
    const list = await screen.findByRole('list')
    expect(list.tagName).toBe('UL')
    expect(list.getAttribute('role')).toBe('list')
  })

  it('화면 진입에 섹터 목록을 한 번만 부른다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()

    await screen.findAllByRole('listitem')
    expect(apiMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1)
  })

  it('isSample이 true일 때만 예시 대응표 표시가 보인다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(true))
    const { unmount } = renderPage()

    expect(await screen.findByText(/예시 대응표/)).toBeTruthy()
    unmount()

    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()

    await screen.findAllByRole('listitem')
    expect(screen.queryByText(/예시 대응표/)).toBeNull()
  })

  it('대응표가 예시라는 표시에 비중 이야기를 섞지 않는다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(true))
    renderPage()

    const sampleNotice = await screen.findByText(/예시 대응표/)
    // 이 문장이 가리키는 것은 목록이 어디서 왔는지다. 비중은 이 화면이 다루지 않는다.
    expect(sampleNotice.textContent).not.toMatch(/비중/)
  })

  it('KRX 출처를 목록 화면에 표시한다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()

    expect(await screen.findByText(/한국거래소 통계정보/)).toBeTruthy()
  })

  it('대응표 미발행에서 빈 목록도 오류 화면도 아닌 한 문장과 행동 하나를 보인다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue({ status: 'mapNotReady', message: '준비 중' })
    renderPage()

    expect(await screen.findByRole('button', { name: '다시 불러오기' })).toBeTruthy()
    expect(screen.queryAllByRole('list')).toHaveLength(0)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryAllByRole('alert')).toHaveLength(0)
  })

  it('대응표 미발행 상태에서는 다음 단계로 가는 수단을 두지 않는다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue({ status: 'mapNotReady', message: '준비 중' })
    renderPage()

    await screen.findByRole('button', { name: '다시 불러오기' })
    expect(screen.queryByRole('button', { name: /다음/ })).toBeNull()
    expect(screen.queryByRole('link', { name: /다음/ })).toBeNull()
  })

  it('발행된 대응표에 섹터가 0개면 빈 목록 대신 한 문장과 행동 하나를 보인다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue({
      status: 'emptyCatalog',
      catalog: { mapVersion: 'SEM-2026-09-10-001', isSample: false, sectors: [] },
    })
    renderPage()

    expect(await screen.findByRole('button', { name: '다시 불러오기' })).toBeTruthy()
    // 빈 `<ul>`도, 목록 제목도, 출처 문장만 남은 화면도 그리지 않는다.
    expect(screen.queryAllByRole('list')).toHaveLength(0)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByText(/한국거래소 통계정보/)).toBeNull()
  })

  it('연결 실패에서도 같은 형태로 처리한다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(networkFailure)
    renderPage()

    expect(await screen.findByRole('button', { name: '다시 불러오기' })).toBeTruthy()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })

  it('다시 불러도 달라지지 않는 실패에는 행동을 두지 않는다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_MAP_UNUSABLE',
      message: '대응표를 읽을 수 없다',
      retryable: false,
    })
    renderPage()

    // 문장은 보이되 버튼은 없다. 눌러도 달라지지 않는 상태이기 때문이다.
    expect(await screen.findByRole('status')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  it('일시적 실패와 영구적 실패의 문장이 서로 다르다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(networkFailure)
    const { unmount } = renderPage()
    const retryableMessage = (await screen.findByRole('status')).textContent
    unmount()

    apiMocks.fetchSectorCatalog.mockResolvedValue({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_MAP_UNUSABLE',
      message: '대응표를 읽을 수 없다',
      retryable: false,
    })
    renderPage()
    const unretryableMessage = (await screen.findByText(/서버에 저장된/)).textContent

    expect(unretryableMessage).not.toBe(retryableMessage)
  })

  it('다시 불러오기를 누르면 목록을 한 번 더 받는다', async () => {
    apiMocks.fetchSectorCatalog
      .mockResolvedValueOnce(networkFailure)
      .mockResolvedValueOnce(loaded(false))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '다시 불러오기' }))

    expect(await screen.findAllByRole('listitem')).toHaveLength(3)
    await waitFor(() => expect(apiMocks.fetchSectorCatalog).toHaveBeenCalledTimes(2))
  })

  it('공통 헤더의 메뉴가 첫 화면과 같은 넷이고 목적지도 같다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()
    await screen.findAllByRole('listitem')

    const navigation = screen.getByRole('navigation', { name: '주요 화면' })
    const links = within(navigation).getAllByRole('link')

    expect(
      links.map((link) => [link.textContent, link.getAttribute('href')]),
    ).toEqual([
      ['전략 진단', '/diagnosis/sectors'],
      ['시장 흐름', '/market-flow'],
      ['배우기', '/learn'],
      ['내 기록', '/records'],
    ])
  })

  it('헤더에 같은 이름의 내 기록 링크를 둘 두지 않는다', async () => {
    apiMocks.fetchSectorCatalog.mockResolvedValue(loaded(false))
    renderPage()
    await screen.findAllByRole('listitem')

    expect(screen.getAllByRole('link', { name: '내 기록' })).toHaveLength(1)
  })

  it('불러오는 동안에도 빈 화면이 아니라 상태를 알린다', async () => {
    apiMocks.fetchSectorCatalog.mockReturnValue(new Promise(() => {}))
    renderPage()

    expect(screen.getByRole('status').textContent).toContain('불러오는 중')
  })

  it('불러오는 동안에는 다시 불러오기를 누를 수 없다', async () => {
    apiMocks.fetchSectorCatalog.mockReturnValue(new Promise(() => {}))
    renderPage()

    // 요청이 겹칠 수 있는 첫 번째 길목을 화면 구조가 막는다. 앞선 요청이 끝나야 행동이 나타난다.
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  it('다시 불러오면 앞선 요청을 취소한다', async () => {
    const signals: AbortSignal[] = []
    apiMocks.fetchSectorCatalog.mockImplementation((signal?: AbortSignal) => {
      if (signal) signals.push(signal)
      return Promise.resolve(networkFailure)
    })

    renderPage()
    const retry = await screen.findByRole('button', { name: '다시 불러오기' })
    expect(signals).toHaveLength(1)
    expect(signals[0].aborted).toBe(false)

    await userEvent.click(retry)
    await waitFor(() => expect(signals).toHaveLength(2))

    // 요청을 정리하는 일은 화면이 사라질 때만 하는 것이 아니다. 다시 불러올 때마다 앞선 요청이
    // 먼저 취소되고 새 요청이 시작된다. 이 순서가 깨지면 앞선 응답이 뒤늦게 도착해 새 결과를
    // 덮을 수 있다.
    expect(signals[0].aborted).toBe(true)
    expect(signals[1].aborted).toBe(false)
  })

  it('취소된 요청의 응답이 뒤늦게 와도 화면을 덮지 않는다', async () => {
    // Promise를 만드는 함수는 그 자리에서 실행되므로 이 값은 아래에서 반드시 채워져 있다.
    let settleSecond!: (result: SectorCatalogResult) => void
    const secondCall = new Promise<SectorCatalogResult>((resolve) => {
      settleSecond = resolve
    })

    apiMocks.fetchSectorCatalog
      .mockImplementationOnce(() => Promise.resolve(networkFailure))
      .mockImplementationOnce(() => secondCall)
      .mockImplementationOnce(() => Promise.resolve(loaded(false)))

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: '다시 불러오기' }))

    // 두 번째 요청이 답하지 않는 사이에 화면을 다시 마운트해 세 번째 요청이 결과를 채우게 한다.
    const { unmount } = renderPage()
    await screen.findAllByRole('listitem')

    // 취소된 두 번째 요청이 이제야 답한다. 그 값이 화면에 반영되면 목록이 사라진다.
    settleSecond(networkFailure)
    await waitFor(() => expect(screen.getAllByRole('listitem').length).toBeGreaterThan(0))

    unmount()
  })
})

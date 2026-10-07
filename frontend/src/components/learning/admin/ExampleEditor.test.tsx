import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminDraftExample } from '@/types/learningAdmin'
import { ExampleEditor } from './ExampleEditor'

/**
 * 013 T089. 비중 칸이 사용자가 친 글자를 몰래 바꾸지 않는다(constitution 원칙 VI).
 *
 * 편집기는 값을 부모에게 올리고 부모가 다시 내려 준다. 그 왕복을 거쳐야 "글자마다 다시 그려져
 * 입력이 바뀌는" 결함이 드러나므로, 상태를 가진 부모를 두고 그 안에서 친다.
 */

const sectorMocks = vi.hoisted(() => ({ fetchSectorCatalog: vi.fn() }))
vi.mock('@/api/sectors', () => sectorMocks)

let latest: AdminDraftExample | null = null

function Harness({ initial }: { initial: AdminDraftExample | null }) {
  const [example, setExample] = useState<AdminDraftExample | null>(initial)
  return (
    <ExampleEditor
      example={example}
      onChange={(next) => {
        latest = next
        setExample(next)
      }}
    />
  )
}

const oneRow: AdminDraftExample = { sectors: [{ code: 'ALPHA_DOCK', weightTenths: 0 }] }

describe('ExampleEditor 비중 칸', () => {
  beforeEach(() => {
    latest = null
    sectorMocks.fetchSectorCatalog.mockReset().mockResolvedValue({
      status: 'loaded',
      catalog: {
        mapVersion: 'SYN-1',
        isSample: true,
        sectors: [
          {
            code: 'ALPHA_DOCK',
            name: '알파 부두',
            classification: 'INDUSTRY',
            representativeEtf: {
              instrumentId: 'SYN-ETF-1',
              name: '합성 ETF',
              listedOn: '2020-01-02',
              benchmarkIndex: '합성 지수',
            },
          },
        ],
      },
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('"50"을 치면 50.0%가 된다', async () => {
    const user = userEvent.setup()
    render(<Harness initial={oneRow} />)

    const field = await screen.findByLabelText('비중 (%)')
    await user.clear(field)
    await user.type(field, '50')

    expect(field).toHaveProperty('value', '50')
    expect(latest?.sectors[0].weightTenths).toBe(500)
  })

  it('"12.5"를 치면 12.5%가 되고 칸을 벗어나면 확정된 표기로 돌아간다', async () => {
    const user = userEvent.setup()
    render(<Harness initial={oneRow} />)

    const field = await screen.findByLabelText('비중 (%)')
    await user.clear(field)
    await user.type(field, '12.5')
    expect(latest?.sectors[0].weightTenths).toBe(125)

    await user.tab()
    expect(field).toHaveProperty('value', '12.5')
  })

  it('소수 둘째 자리는 받지 않고 그 자리에 이유를 알리며 확정된 값을 바꾸지 않는다', async () => {
    const user = userEvent.setup()
    render(<Harness initial={oneRow} />)

    const field = await screen.findByLabelText('비중 (%)')
    await user.clear(field)
    await user.type(field, '12.34')

    // 친 글자는 그대로 보인다. 화면이 잘라 내거나 반올림하지 않는다.
    expect(field).toHaveProperty('value', '12.34')
    expect(screen.getByRole('alert').textContent).toBe(
      '비중은 소수 첫째 자리까지 입력할 수 있습니다.',
    )
    // 마지막으로 받아들인 값은 "12.3"의 123이다.
    expect(latest?.sectors[0].weightTenths).toBe(123)
  })

  it('열린 대응표에 섹터가 없으면 새로 고를 수 없는 이유를 알리고 기존 행은 둔다', async () => {
    sectorMocks.fetchSectorCatalog.mockResolvedValue({
      status: 'emptyCatalog',
      catalog: { mapVersion: 'SYN-EMPTY', isSample: true, sectors: [] },
    })
    render(<Harness initial={{ sectors: [{ code: 'ALPHA_DOCK', weightTenths: 500 }] }} />)

    expect(
      await screen.findByText('열린 대응표에 섹터가 없어 새로 고를 수 없습니다. 이미 넣은 행은 그대로 둡니다.'),
    ).not.toBeNull()
    expect(screen.getByRole('button', { name: '섹터 추가' })).toHaveProperty('disabled', true)
    // 대응표를 확인하지 못한 것과 같은 문장으로 묶지 않는다.
    expect(screen.queryByText(/대응표를 확인할 수 없어/)).toBeNull()
    // 기존 행은 지우지 않는다.
    expect(screen.getByLabelText('비중 (%)')).toHaveProperty('value', '50.0')
    expect(latest).toBeNull()
  })

  it('빈 칸은 0으로 올리지 않는다', async () => {
    const user = userEvent.setup()
    render(<Harness initial={{ sectors: [{ code: 'ALPHA_DOCK', weightTenths: 400 }] }} />)

    const field = await screen.findByLabelText('비중 (%)')
    await user.clear(field)

    expect(latest).toBeNull()
    await user.tab()
    expect(field).toHaveProperty('value', '40.0')
  })
})

describe('ExampleEditor 예시 없음 (2판)', () => {
  beforeEach(() => {
    sectorMocks.fetchSectorCatalog.mockReset().mockResolvedValue({
      status: 'mapNotReady',
      message: '준비 중',
    })
  })

  it('V1처럼 예시가 있어야 하면 게시에 예시가 필요하다고 알린다', () => {
    render(<ExampleEditor example={null} onChange={() => undefined} />)

    expect(screen.getByText('예시가 없습니다. 게시하려면 예시가 있어야 합니다.')).not.toBeNull()
  })

  it('V2처럼 예시가 선택이면 넣지 않아도 된다고 알린다(013 FR-003)', () => {
    render(<ExampleEditor example={null} onChange={() => undefined} required={false} />)

    expect(screen.getByText(/예시는 넣지 않아도 되며/)).not.toBeNull()
    expect(screen.queryByText(/게시하려면 예시가 있어야 합니다/)).toBeNull()
  })
})

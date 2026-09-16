import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
} from './selectionStorage'
import {
  type SectorSelectionContextValue,
  useSectorSelection,
} from './sector-selection-context'
import { SectorSelectionProvider } from './SectorSelectionProvider'
import type { SectorSummary } from '@/types/sector'

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

/** 표시 순서는 반도체 → 자동차 → 조선 → 2차전지다. 업종이 먼저, 그 안에서 한국어 이름 순이다. */
const catalog = [
  sector('BATTERY', '2차전지', 'THEME'),
  sector('SHIPBUILDING', '조선', 'INDUSTRY'),
  sector('AUTO', '자동차', 'INDUSTRY'),
  sector('SEMICONDUCTOR', '반도체', 'INDUSTRY'),
]

const MAP_VERSION = 'SEM-2026-09-10-001'

let latest: SectorSelectionContextValue | null = null

function Probe() {
  const selection = useSectorSelection()
  latest = selection

  return (
    <dl>
      <dt>selected</dt>
      <dd data-testid="selected">{selection.selectedCodes.join(',')}</dd>
      <dt>weights</dt>
      <dd data-testid="weights">
        {selection.selectedCodes.map((code) => `${code}=${selection.weightTenths[code]}`).join(',')}
      </dd>
      <dt>touched</dt>
      <dd data-testid="touched">{String(selection.weightsTouched)}</dd>
      <dt>derived</dt>
      <dd data-testid="derived">
        {[
          selection.totalTenths,
          selection.isBalanced,
          selection.remainderTenths,
          selection.canProceed,
          selection.hasZeroWeight,
        ].join('/')}
      </dd>
      <dt>mapVersion</dt>
      <dd data-testid="mapVersion">{selection.mapVersion}</dd>
      <dt>dropped</dt>
      <dd data-testid="dropped">
        {selection.dropped.map((item) => `${item.code}:${item.reason}`).join(',')}
      </dd>
      <dt>problem</dt>
      <dd data-testid="problem">{String(selection.restoreProblem)}</dd>
    </dl>
  )
}

function renderProvider(
  sectors: readonly SectorSummary[] = catalog,
  mapVersion: string = MAP_VERSION,
) {
  return render(
    <SectorSelectionProvider sectors={sectors} mapVersion={mapVersion}>
      <Probe />
    </SectorSelectionProvider>,
  )
}

/** 컨텍스트를 붙잡아 상태 전이를 그대로 부른다. 화면 모양에 기대지 않고 전이만 본다. */
function selection(): SectorSelectionContextValue {
  if (!latest) throw new Error('Provider가 아직 그려지지 않았다')
  return latest
}

function run(action: (value: SectorSelectionContextValue) => void): void {
  act(() => action(selection()))
}

function selected(): string {
  return screen.getByTestId('selected').textContent ?? ''
}

function weights(): string {
  return screen.getByTestId('weights').textContent ?? ''
}

function touched(): string {
  return screen.getByTestId('touched').textContent ?? ''
}

function storedPayload(): Record<string, unknown> {
  const raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)
  return JSON.parse(raw ?? '{}') as Record<string, unknown>
}

describe('SectorSelectionProvider — data-model.md 「상태 전이」', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    latest = null
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
  })

  it('1행. 아직 고치지 않았으면 섹터를 체크할 때마다 전체를 동일 비중으로 다시 채운다', () => {
    renderProvider()

    run((value) => value.toggleSector('AUTO', true))
    expect(weights()).toBe('AUTO=1000')

    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    expect(weights()).toBe('SEMICONDUCTOR=500,AUTO=500')

    run((value) => value.toggleSector('BATTERY', true))
    // 고른 순서와 무관하게 표시 순서로 정규화되고 잔여분은 그 첫 번째에 붙는다.
    expect(selected()).toBe('SEMICONDUCTOR,AUTO,BATTERY')
    expect(weights()).toBe('SEMICONDUCTOR=334,AUTO=333,BATTERY=333')
    expect(touched()).toBe('false')
  })

  it('2행. 한 번이라도 고쳤으면 기존 값을 건드리지 않고 새 섹터에만 0을 준다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.setWeight('AUTO', 400))

    const before = weights()
    run((value) => value.toggleSector('SHIPBUILDING', true))

    // SC-009: 기존 섹터의 비중이 바뀐 사례 0건, 새 섹터가 0%가 아닌 사례 0건.
    expect(weights()).toBe(`${before},SHIPBUILDING=0`)
    expect(weights()).toBe('SEMICONDUCTOR=500,AUTO=400,SHIPBUILDING=0')
    expect(touched()).toBe('true')
  })

  it('3행. 아직 고치지 않았으면 섹터를 해제할 때도 남은 섹터를 동일 비중으로 다시 채운다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.toggleSector('BATTERY', true))

    run((value) => value.toggleSector('AUTO', false))

    expect(selected()).toBe('SEMICONDUCTOR,BATTERY')
    expect(weights()).toBe('SEMICONDUCTOR=500,BATTERY=500')
    expect(touched()).toBe('false')
  })

  it('4행. 고친 뒤 섹터를 해제하면 남은 값을 그대로 둔다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.toggleSector('BATTERY', true))
    run((value) => value.setWeight('SEMICONDUCTOR', 400))

    run((value) => value.toggleSector('BATTERY', false))

    // 합계가 100% 아래로 내려가는 것은 오류가 아니라 FR-007이 안내하는 정상 상태다.
    expect(weights()).toBe('SEMICONDUCTOR=400,AUTO=333')
    expect(screen.getByTestId('derived').textContent).toBe('733/false/267/false/false')
    expect(touched()).toBe('true')
  })

  it('5행. 비중 수정은 그 섹터만 바꾸고 수정 여부를 true로 만든다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.toggleSector('BATTERY', true))

    run((value) => value.setWeight('AUTO', 0))

    // 0%를 거부하지 않는다(FR-016, SC-010). 다른 섹터의 값은 그대로다(SC-002).
    expect(weights()).toBe('SEMICONDUCTOR=334,AUTO=0,BATTERY=333')
    expect(touched()).toBe('true')
    expect(screen.getByTestId('derived').textContent).toBe('667/false/333/false/true')
  })

  it('6행. 동일하게 나누기는 값을 다시 채우면서 수정 여부를 false로 되돌린다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.toggleSector('BATTERY', true))
    run((value) => value.setWeight('AUTO', 600))
    expect(touched()).toBe('true')

    run((value) => value.distributeEvenly())

    expect(weights()).toBe('SEMICONDUCTOR=334,AUTO=333,BATTERY=333')
    // research.md 결정 10. 되돌린 결과가 값만 같고 이후 동작이 다르면 되돌린 것이 아니다.
    expect(touched()).toBe('false')

    run((value) => value.toggleSector('SHIPBUILDING', true))
    expect(weights()).toBe('SEMICONDUCTOR=250,AUTO=250,SHIPBUILDING=250,BATTERY=250')
  })

  it('9행. 세션 복원은 검증을 통과한 코드만 비중·수정 여부와 함께 되살린다', () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION,
        mapVersion: MAP_VERSION,
        selected: [
          { code: 'AUTO', weightTenths: 400 },
          { code: 'SEMICONDUCTOR', weightTenths: 333 },
          { code: 'GONE', weightTenths: 267 },
        ],
        weightsTouched: true,
      }),
    )

    renderProvider()

    // SC-006: 새로고침으로 화면이 다시 떠도 입력값이 그대로 남는다.
    expect(selected()).toBe('SEMICONDUCTOR,AUTO')
    expect(weights()).toBe('SEMICONDUCTOR=333,AUTO=400')
    expect(touched()).toBe('true')
    // 조용히 버리지 않는다. 빠졌다는 사실과 그 까닭이 화면까지 올라온다.
    expect(screen.getByTestId('dropped').textContent).toBe('GONE:notInCatalog')
    expect(screen.getByTestId('problem').textContent).toBe('null')
  })

  it('화면이 뜨는 것만으로는 보관된 값을 건드리지 않는다', () => {
    const stored = JSON.stringify({
      version: SECTOR_SELECTION_PAYLOAD_VERSION + 1,
      shape: '아직 모르는 형식',
    })
    window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, stored)

    renderProvider()

    // 읽지 못한 형식을 지키는 것이 그 분기의 목적이다. 화면이 뜨면서 빈 상태로 덮어쓰면 뒤로
    // 가기로 옛 화면이 열리는 것만으로 새 형식의 값이 사라진다.
    expect(screen.getByTestId('problem').textContent).toBe('newerFormat')
    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBe(stored)
  })

  it('읽지 못한 형식이라도 사용자가 값을 넣으면 그 값을 보관한다', () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({ version: SECTOR_SELECTION_PAYLOAD_VERSION + 1, shape: '아직 모르는 형식' }),
    )

    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))

    // 읽기를 거부하는 것과 쓰기를 아주 막는 것은 다른 일이다. 지키려는 것은 화면이 열리는
    // 것만으로 남의 값이 지워지는 일이지, 사용자가 방금 한 행동이 아니다. 여기서 쓰기를 막으면
    // 사용자가 넣은 값이 새로고침에 조용히 사라진다.
    const stored = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)
    expect(stored).not.toBeNull()
    const payload = JSON.parse(stored as string)
    expect(payload.version).toBe(SECTOR_SELECTION_PAYLOAD_VERSION)
    expect(payload.selected).toEqual([{ code: 'SEMICONDUCTOR', weightTenths: 1000 }])
  })

  it('아무것도 고르지 않은 채 화면만 열면 저장소에 아무것도 쓰지 않는다', () => {
    renderProvider()

    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBeNull()
  })

  it('복원한 값은 만들 당시의 대응표 버전을 그대로 들고 간다', () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION,
        mapVersion: 'SEM-2026-08-01-001',
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 600 },
          { code: 'AUTO', weightTenths: 400 },
        ],
        weightsTouched: true,
      }),
    )

    // 그 사이 대응표가 새 버전으로 발행되었다.
    renderProvider(catalog, 'SEM-2026-09-10-001')

    // 값은 예전 대응표에서 만들어졌다. 지금 버전으로 도장을 다시 찍으면 003·004의 재현 기록이
    // "새 버전에서 만든 값"이라고 잘못 말하게 된다(원칙 VIII).
    expect(screen.getByTestId('mapVersion').textContent).toBe('SEM-2026-08-01-001')
    expect(weights()).toBe('SEMICONDUCTOR=600,AUTO=400')
  })

  it('사용자가 값을 바꾸면 그때 받은 대응표 버전으로 다시 찍는다', () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION,
        mapVersion: 'SEM-2026-08-01-001',
        selected: [{ code: 'SEMICONDUCTOR', weightTenths: 1000 }],
        weightsTouched: true,
      }),
    )
    renderProvider(catalog, 'SEM-2026-09-10-001')

    run((value) => value.setWeight('SEMICONDUCTOR', 600))

    // 지금 값은 지금 대응표에서 만들어진 것이다. 코드도 현재 목록에 비추어 검증된 것들이다.
    expect(screen.getByTestId('mapVersion').textContent).toBe('SEM-2026-09-10-001')
    expect(storedPayload().mapVersion).toBe('SEM-2026-09-10-001')
  })

  it('알 수 없는 형식은 빈 상태로 시작하되 그 사실을 알린다', () => {
    window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, '{ not json')
    renderProvider()

    expect(selected()).toBe('')
    expect(screen.getByTestId('problem').textContent).toBe('unreadable')
  })

  it('해제했다가 다시 고르면 이전 비중을 되살리지 않는다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.setWeight('AUTO', 400))
    run((value) => value.toggleSector('AUTO', false))

    run((value) => value.toggleSector('AUTO', true))

    // 기억해 두었다가 되살리면 사용자가 지운 값이 말없이 되돌아온다. '섹터 체크'와 같게 다룬다.
    expect(weights()).toBe('SEMICONDUCTOR=500,AUTO=0')
  })

  it('상태가 바뀔 때마다 계약이 정한 형태로 보관한다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.setWeight('AUTO', 400))

    expect(storedPayload()).toEqual({
      version: 1,
      mapVersion: MAP_VERSION,
      selected: [
        { code: 'SEMICONDUCTOR', weightTenths: 500 },
        { code: 'AUTO', weightTenths: 400 },
      ],
      weightsTouched: true,
    })
  })

  it('합계가 1000이 아닌 상태도 그대로 보관한다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.setWeight('AUTO', 100))

    // 저장 시점에 맞추면 사용자 입력을 몰래 바꾸는 것이 된다(계약 불변식 3).
    expect(storedPayload().selected).toEqual([
      { code: 'SEMICONDUCTOR', weightTenths: 500 },
      { code: 'AUTO', weightTenths: 100 },
    ])
  })

  it('0%인 섹터를 선택 목록에서 빼지 않는다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    run((value) => value.setWeight('AUTO', 0))
    run((value) => value.setWeight('SEMICONDUCTOR', 1000))

    // SC-010: 003이 선택 섹터 개수로 유효성 제약을 계산하므로 빼면 003의 계산과 어긋난다.
    expect(selected()).toBe('SEMICONDUCTOR,AUTO')
    expect(storedPayload().selected).toEqual([
      { code: 'SEMICONDUCTOR', weightTenths: 1000 },
      { code: 'AUTO', weightTenths: 0 },
    ])
  })

  it('저장소가 막혀 있어도 화면이 죽지 않고 그 안에서는 동작한다', () => {
    const blocked = () => {
      throw new Error('저장소가 막혀 있다')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked)

    expect(() => renderProvider()).not.toThrow()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))

    expect(weights()).toBe('SEMICONDUCTOR=500,AUTO=500')
  })

  it('목록에 없는 코드는 선택 상태에 들어오지 못한다', () => {
    renderProvider()

    run((value) => value.toggleSector('GONE', true))

    // 불변식 3을 상태에 들어오는 길목에서 지킨다.
    expect(selected()).toBe('')
  })

  it('받을 수 없는 비중은 상태에 들어오지 못한다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))

    for (const invalid of [-1, 1001, 33.4]) {
      run((value) => value.setWeight('AUTO', invalid))
    }

    expect(weights()).toBe('SEMICONDUCTOR=500,AUTO=500')
    expect(touched()).toBe('false')
  })

  it('파생값을 저장하지 않고 매번 계산한다', () => {
    renderProvider()
    run((value) => value.toggleSector('SEMICONDUCTOR', true))
    run((value) => value.toggleSector('AUTO', true))
    expect(screen.getByTestId('derived').textContent).toBe('1000/true/0/true/false')

    run((value) => value.setWeight('AUTO', 600))
    expect(screen.getByTestId('derived').textContent).toBe('1100/false/-100/false/false')
  })
})

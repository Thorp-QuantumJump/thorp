import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
  clearStoredSelection,
  readStoredSelection,
  writeStoredSelection,
} from './selectionStorage'
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

/** 표시 순서는 반도체 → 자동차 → 2차전지다. 업종이 먼저, 그 안에서 한국어 이름 순이다. */
const catalog = [
  sector('BATTERY', '2차전지', 'THEME'),
  sector('AUTO', '자동차', 'INDUSTRY'),
  sector('SEMICONDUCTOR', '반도체', 'INDUSTRY'),
]

function store(payload: unknown): void {
  window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, JSON.stringify(payload))
}

function validPayload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    version: SECTOR_SELECTION_PAYLOAD_VERSION,
    mapVersion: 'SEM-2026-09-10-001',
    selected: [
      { code: 'SEMICONDUCTOR', weightTenths: 334 },
      { code: 'AUTO', weightTenths: 333 },
      { code: 'BATTERY', weightTenths: 333 },
    ],
    weightsTouched: false,
    ...overrides,
  }
}

describe('selectionStorage', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
  })

  it('저장한 값을 그대로 되읽는다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-2026-09-10-001',
      selectedCodes: ['SEMICONDUCTOR', 'AUTO', 'BATTERY'],
      weightTenths: { SEMICONDUCTOR: 334, AUTO: 333, BATTERY: 333 },
      weightsTouched: true,
    })

    const result = readStoredSelection(catalog)

    expect(result.problem).toBeNull()
    expect(result.dropped).toEqual([])
    expect(result.snapshot).toEqual({
      mapVersion: 'SEM-2026-09-10-001',
      selectedCodes: ['SEMICONDUCTOR', 'AUTO', 'BATTERY'],
      weightTenths: { SEMICONDUCTOR: 334, AUTO: 333, BATTERY: 333 },
      weightsTouched: true,
    })
  })

  it('계약이 정한 키 하나에 계약이 정한 형태로 담는다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-2026-09-10-001',
      selectedCodes: ['SEMICONDUCTOR', 'AUTO'],
      weightTenths: { SEMICONDUCTOR: 500, AUTO: 500 },
      weightsTouched: false,
    })

    const raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)
    expect(raw).not.toBeNull()
    expect(JSON.parse(raw ?? '{}')).toEqual({
      version: 1,
      mapVersion: 'SEM-2026-09-10-001',
      selected: [
        { code: 'SEMICONDUCTOR', weightTenths: 500 },
        { code: 'AUTO', weightTenths: 500 },
      ],
      weightsTouched: false,
    })
  })

  it('보관된 값이 없으면 빈 상태이고 알릴 것도 없다', () => {
    expect(readStoredSelection(catalog)).toEqual({
      snapshot: null,
      dropped: [],
      problem: null,
    })
  })

  it('보관된 배열 순서와 무관하게 표시 순서로 정규화한다', () => {
    store(
      validPayload({
        selected: [
          { code: 'BATTERY', weightTenths: 333 },
          { code: 'SEMICONDUCTOR', weightTenths: 334 },
          { code: 'AUTO', weightTenths: 333 },
        ],
      }),
    )

    expect(readStoredSelection(catalog).snapshot?.selectedCodes).toEqual([
      'SEMICONDUCTOR',
      'AUTO',
      'BATTERY',
    ])
  })

  it('현재 목록에 없는 코드를 빼되 빠졌다는 사실을 함께 돌려준다', () => {
    store(
      validPayload({
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 500 },
          { code: 'GONE', weightTenths: 500 },
        ],
      }),
    )

    const result = readStoredSelection(catalog)

    // 조용히 버리지 않는다. 무엇을 제안할지는 006이 소유하므로 002는 빠졌다는 사실까지만 전한다.
    expect(result.dropped).toEqual([{ code: 'GONE', reason: 'notInCatalog' }])
    expect(result.snapshot?.selectedCodes).toEqual(['SEMICONDUCTOR'])
    expect(result.problem).toBeNull()
  })

  it('비중이 0 이상 1000 이하 정수가 아니면 그 항목을 받지 않는다', () => {
    for (const weightTenths of [-1, 1001, 33.4, '334', null]) {
      window.sessionStorage.clear()
      store(
        validPayload({
          selected: [
            { code: 'SEMICONDUCTOR', weightTenths: 500 },
            { code: 'AUTO', weightTenths },
          ],
        }),
      )

      const result = readStoredSelection(catalog)
      expect(result.dropped, String(weightTenths)).toEqual([{ code: 'AUTO', reason: 'unusableWeight' }])
      expect(result.snapshot?.selectedCodes, String(weightTenths)).toEqual(['SEMICONDUCTOR'])
    }
  })

  it('0은 유효한 비중이므로 그대로 받는다', () => {
    store(
      validPayload({
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 1000 },
          { code: 'AUTO', weightTenths: 0 },
        ],
      }),
    )

    const result = readStoredSelection(catalog)

    expect(result.dropped).toEqual([])
    expect(result.snapshot?.weightTenths).toEqual({ SEMICONDUCTOR: 1000, AUTO: 0 })
  })

  it('같은 코드가 두 번 실려 오면 하나만 남긴다', () => {
    store(
      validPayload({
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 500 },
          { code: 'SEMICONDUCTOR', weightTenths: 400 },
        ],
      }),
    )

    const result = readStoredSelection(catalog)

    expect(result.snapshot?.selectedCodes).toEqual(['SEMICONDUCTOR'])
    expect(result.snapshot?.weightTenths).toEqual({ SEMICONDUCTOR: 500 })
    // 그 섹터는 선택에 남았으므로 잃은 것이 아니다. 여기에 담으면 실제로 잃은 수보다 큰 숫자가 나온다.
    expect(result.dropped).toEqual([])
  })

  it('중복된 코드 가운데 하나가 받아들여지면 빠진 것으로 세지 않는다', () => {
    store(
      validPayload({
        selected: [
          { code: 'SEMICONDUCTOR', weightTenths: 2000 },
          { code: 'SEMICONDUCTOR', weightTenths: 400 },
        ],
      }),
    )

    const result = readStoredSelection(catalog)

    // 앞엣것은 비중 때문에 빠지고 뒤엣것이 받아들여진다. 그 섹터는 목록에 멀쩡히 있고 선택까지
    // 되어 있으므로 "목록에 없어 빠졌다"고 말하면 사실과 다르다.
    expect(result.snapshot?.selectedCodes).toEqual(['SEMICONDUCTOR'])
    expect(result.snapshot?.weightTenths).toEqual({ SEMICONDUCTOR: 400 })
    expect(result.dropped).toEqual([])
  })

  it('같은 코드가 여러 번 빠져도 한 번만 센다', () => {
    store(
      validPayload({
        selected: [
          { code: 'GONE', weightTenths: 300 },
          { code: 'GONE', weightTenths: 300 },
          { code: 'SEMICONDUCTOR', weightTenths: 400 },
        ],
      }),
    )

    expect(readStoredSelection(catalog).dropped).toEqual([
      { code: 'GONE', reason: 'notInCatalog' },
    ])
  })

  it('빠진 까닭을 서로 다른 값으로 구분해 돌려준다', () => {
    store(
      validPayload({
        selected: [
          { code: 'GONE', weightTenths: 300 },
          { code: 'AUTO', weightTenths: -5 },
          { code: 'SEMICONDUCTOR', weightTenths: 400 },
        ],
      }),
    )

    expect(readStoredSelection(catalog).dropped).toEqual([
      { code: 'GONE', reason: 'notInCatalog' },
      { code: 'AUTO', reason: 'unusableWeight' },
    ])
  })

  it('합계가 1000이 아닌 상태도 그대로 보관하고 그대로 되읽는다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-2026-09-10-001',
      selectedCodes: ['SEMICONDUCTOR', 'AUTO'],
      weightTenths: { SEMICONDUCTOR: 400, AUTO: 333 },
      weightsTouched: true,
    })

    // 저장 시점에 맞추면 사용자 입력을 몰래 바꾸는 것이 된다(계약 불변식 3).
    expect(readStoredSelection(catalog).snapshot?.weightTenths).toEqual({
      SEMICONDUCTOR: 400,
      AUTO: 333,
    })
  })

  it('아무것도 고르지 않은 상태도 보관한다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-2026-09-10-001',
      selectedCodes: [],
      weightTenths: {},
      weightsTouched: false,
    })

    const result = readStoredSelection(catalog)

    expect(result.snapshot).toEqual({
      mapVersion: 'SEM-2026-09-10-001',
      selectedCodes: [],
      weightTenths: {},
      weightsTouched: false,
    })
  })

  it('version이 아는 값보다 크면 그 페이로드를 쓰지 않고 알린다', () => {
    store(validPayload({ version: SECTOR_SELECTION_PAYLOAD_VERSION + 1 }))

    expect(readStoredSelection(catalog)).toEqual({
      snapshot: null,
      dropped: [],
      problem: 'newerFormat',
    })
  })

  it('읽을 수 없거나 알 수 없는 형식이면 빈 상태로 시작하되 알린다', () => {
    const broken: unknown[] = [
      undefined, // JSON이 아닌 문자열
      validPayload({ version: '1' }),
      validPayload({ mapVersion: 42 }),
      validPayload({ weightsTouched: 'false' }),
      validPayload({ selected: 'SEMICONDUCTOR' }),
      validPayload({ selected: [{ weightTenths: 500 }] }),
      validPayload({ selected: ['SEMICONDUCTOR'] }),
    ]

    for (const [index, payload] of broken.entries()) {
      window.sessionStorage.clear()
      if (payload === undefined) {
        window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, '{ not json')
      } else {
        store(payload)
      }

      const result = readStoredSelection(catalog)
      expect(result.problem, `${index}번 페이로드`).toBe('unreadable')
      expect(result.snapshot, `${index}번 페이로드`).toBeNull()
    }
  })

  it('저장소 접근이 막혀도 예외를 밖으로 던지지 않는다', () => {
    const blocked = () => {
      throw new Error('저장소가 막혀 있다')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked)

    expect(() =>
      writeStoredSelection({
        mapVersion: 'SEM-2026-09-10-001',
        selectedCodes: ['SEMICONDUCTOR'],
        weightTenths: { SEMICONDUCTOR: 1000 },
        weightsTouched: false,
      }),
    ).not.toThrow()

    // 저장소를 쓸 수 없는 것은 사용자가 넣은 값을 잃은 것이 아니므로 알림도 붙이지 않는다.
    expect(readStoredSelection(catalog)).toEqual({
      snapshot: null,
      dropped: [],
      problem: null,
    })
    expect(() => clearStoredSelection()).not.toThrow()
  })

  it('지우면 다음 읽기에서 빈 상태가 된다', () => {
    store(validPayload())
    clearStoredSelection()

    expect(readStoredSelection(catalog).snapshot).toBeNull()
  })
})

import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
  writeStoredSelection,
} from '@/diagnosis/selectionStorage'
import type { SectorSummary } from '@/types/sector'
import { useSectorSelection } from './useSectorSelection'

/**
 * 002 → 003 인계 규약을 지키는지 고정한다.
 *
 * <p>규약의 원본은 `specs/002-sector-weight-selection/contracts/selection-handoff.md` 1절이다.
 * <b>이 파일의 첫 검사가 002의 쓰기 함수로 보관한 값을 003의 훅으로 읽는다</b> — 저장 키나 형식이
 * 갈라지면 003이 002의 선택을 한 번도 읽지 못하게 되는데, 두 쪽을 각자 검사하면 그 어긋남이
 * 어느 쪽에서도 드러나지 않는다.
 */

function sector(code: string, name: string): SectorSummary {
  return {
    code,
    name,
    classification: 'INDUSTRY',
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn: '2018-09-12',
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const catalog: SectorSummary[] = [
  sector('ALPHA', '가섹터'),
  sector('BETA', '나섹터'),
  sector('GAMMA', '다섹터'),
]

function renderSelection(sectors: readonly SectorSummary[] | null = catalog) {
  return renderHook(() => useSectorSelection(sectors)).result
}

afterEach(() => {
  vi.restoreAllMocks()
  window.sessionStorage.clear()
})

describe('useSectorSelection', () => {
  it('002가 보관한 값을 그대로 읽는다', () => {
    // 002의 쓰기 함수를 그대로 쓴다. 키나 형식이 갈라지면 여기서 드러난다.
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA', 'BETA'],
      weightTenths: { ALPHA: 600, BETA: 400 },
      weightsTouched: true,
    })

    const result = renderSelection()

    expect(result.current.status).toBe('ready')
    if (result.current.status !== 'ready') return
    expect(result.current.selection.mapVersion).toBe('SEM-TEST-1')
    expect(result.current.selection.items).toEqual([
      { sectorId: 'ALPHA', instrumentId: 'ALPHA.XKRX', weightTenths: 600 },
      { sectorId: 'BETA', instrumentId: 'BETA.XKRX', weightTenths: 400 },
    ])
  })

  it('비중을 0.1%p 단위 정수 그대로 옮긴다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA', 'BETA', 'GAMMA'],
      weightTenths: { ALPHA: 334, BETA: 333, GAMMA: 333 },
      weightsTouched: true,
    })

    // 소수로 바꾸면 합계 판정(sum === 1000)이 받는 쪽에서 흔들린다(계약 값 표현 규칙).
    const result = renderSelection()
    if (result.current.status !== 'ready') throw new Error('ready 가 아니다')
    expect(result.current.selection.items.map((item) => item.weightTenths)).toEqual([
      334, 333, 333,
    ])
  })

  it('비중 합계가 100%면 맞았다고 판정한다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA', 'BETA', 'GAMMA'],
      weightTenths: { ALPHA: 334, BETA: 333, GAMMA: 333 },
      weightsTouched: true,
    })

    const result = renderSelection()
    if (result.current.status !== 'ready') throw new Error('ready 가 아니다')
    expect(result.current.weights).toEqual({ totalTenths: 1000, isBalanced: true })
  })

  it('비중 합계가 어긋나도 앞 단계 미완료로 보지 않고 합계만 알린다', () => {
    // T091. 002 인계 계약 불변식 3이 합계 100%를 보장하지 않는다 — 입력을 마치지 못하고 떠난 상태도
    // 정상으로 보관된다. 합계는 사용자 비중을 쓰는 전략에만 뜻이 있어, 막을지는 전략을 아는 화면이
    // 정한다. 여기서 `incomplete`로 접으면 자동 배분 전략을 쓰려던 사용자까지 되돌린다.
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA', 'BETA', 'GAMMA'],
      weightTenths: { ALPHA: 333, BETA: 333, GAMMA: 333 },
      weightsTouched: true,
    })

    const result = renderSelection()
    expect(result.current.status).toBe('ready')
    if (result.current.status !== 'ready') return
    // 허용 오차를 두지 않는다. 0.1%p 모자라도 맞지 않은 것이다(원칙 VI).
    expect(result.current.weights).toEqual({ totalTenths: 999, isBalanced: false })
  })

  it('종목은 페이로드가 아니라 섹터 목록에서 찾는다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA', 'BETA'],
      weightTenths: { ALPHA: 500, BETA: 500 },
      weightsTouched: false,
    })

    // 보관된 페이로드에는 종목이 없다. 계약이 003은 GET /sectors 로 직접 받으라고 정한다.
    const raw = window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY) ?? ''
    expect(raw).not.toContain('instrumentId')

    const result = renderSelection()
    if (result.current.status !== 'ready') throw new Error('ready 가 아니다')
    expect(result.current.selection.items[0].instrumentId).toBe('ALPHA.XKRX')
  })

  it('섹터 목록을 받기 전에는 마치지 않았다고 말하지 않는다', () => {
    const result = renderSelection(null)

    // 조회가 끝나기도 전에 "앞 단계를 마쳐 주세요"가 번쩍이면 판정하지 않은 결과를 말하는 것이다.
    expect(result.current.status).toBe('pending')
  })

  it('보관된 선택이 없으면 앞 단계를 마치지 않은 것으로 다룬다', () => {
    const result = renderSelection()

    expect(result.current.status).toBe('incomplete')
    if (result.current.status !== 'incomplete') return
    expect(result.current.problem).toBeNull()
  })

  it('고른 섹터가 두 개 미만이면 앞 단계를 마치지 않은 것으로 다룬다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA'],
      weightTenths: { ALPHA: 1000 },
      weightsTouched: true,
    })

    // 섹터가 하나면 어떤 배분 전략을 골라도 그 하나에 전액이 가서 다섯이 같은 결과를 낸다.
    expect(renderSelection().current.status).toBe('incomplete')
  })

  it('목록에서 사라진 섹터가 빠지면 그 사실을 함께 전한다', () => {
    writeStoredSelection({
      mapVersion: 'SEM-TEST-1',
      selectedCodes: ['ALPHA', 'BETA', 'DELTA'],
      weightTenths: { ALPHA: 400, BETA: 300, DELTA: 300 },
      weightsTouched: true,
    })

    const result = renderSelection()

    // 조용히 버리면 사용자는 자기가 고른 섹터가 사라진 줄 모른 채 분석을 시작한다(원칙 VI).
    if (result.current.status !== 'ready') throw new Error('ready 가 아니다')
    expect(result.current.selection.items.map((item) => item.sectorId)).toEqual(['ALPHA', 'BETA'])
    expect(result.current.dropped).toEqual([{ code: 'DELTA', reason: 'notInCatalog' }])
  })

  it('아는 것보다 새로운 형식은 읽지 않고 그 사실을 전한다', () => {
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION + 1,
        mapVersion: 'SEM-TEST-1',
        selected: [{ code: 'ALPHA', weightTenths: 1000 }],
        weightsTouched: true,
      }),
    )

    const result = renderSelection()

    expect(result.current.status).toBe('incomplete')
    if (result.current.status !== 'incomplete') return
    expect(result.current.problem).toBe('newerFormat')
  })

  it('저장소 접근이 막혀도 화면을 오류로 막지 않는다', () => {
    // 사생활 보호 모드 등에서는 접근 자체가 예외를 던진다. 002의 읽기 함수가 그것을 삼키므로
    // 003은 앞 단계를 마치지 않은 상태로 보면 되고, 예외가 화면까지 올라오지 않아야 한다.
    vi.spyOn(window.sessionStorage, 'getItem').mockImplementation(() => {
      throw new Error('storage is not available')
    })

    expect(renderSelection().current.status).toBe('incomplete')
  })
})

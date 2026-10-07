import { describe, expect, it } from 'vitest'
import { medalPath, stageArchProgressPath, type MedalKind } from './medalShapes'

/** 013 T140. 일곱 갈래 메달 실루엣(design-notes §3.3). 견본 그림과 B13 메달이 같은 경로를 쓴다. */

const KINDS: MedalKind[] = ['level', 'read', 'first', 'all', 'stage', 'terms', 'special']

function coordinates(d: string): number[] {
  return (d.match(/-?\d+(\.\d+)?/g) ?? []).map(Number)
}

describe('medalPath', () => {
  it('일곱 갈래가 모두 서로 다른 닫힌 실루엣이다', () => {
    const paths = KINDS.map((kind) => medalPath(kind))
    expect(new Set(paths).size).toBe(KINDS.length)
    for (const d of paths) {
      expect(d.startsWith('M')).toBe(true)
      expect(d.endsWith('Z')).toBe(true)
    }
  })

  it('0~100 정사각형 안에 그리고 안쪽으로 줄이면 경로가 바뀐다', () => {
    for (const kind of KINDS) {
      for (const value of coordinates(medalPath(kind))) {
        expect(value).toBeGreaterThanOrEqual(0)
        expect(value).toBeLessThanOrEqual(100)
      }
      expect(medalPath(kind, 4)).not.toBe(medalPath(kind))
    }
  })

  it('단계 완주 아치의 진행 호는 창턱을 돌지 않는 열린 경로다', () => {
    expect(stageArchProgressPath(-4).endsWith('Z')).toBe(false)
  })
})

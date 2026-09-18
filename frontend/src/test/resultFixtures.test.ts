import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { findDrawdownWindow } from '@/components/result/drawdownWindow'
import {
  makeEquitySeries,
  resultDetailCancelledNoSummary,
  resultDetailCompleted,
  resultDetailRunning,
  resultExceptions,
  resultExpiredEnvelope,
  resultPositions,
  resultTimeseries,
  resultTradePage,
  resultWeights,
} from './resultFixtures'

/**
 * 005 T039. 화면 테스트 픽스처가 계약 예시 파일과 같은 모양인지 대조한다.
 *
 * <p>프론트엔드가 `docs/specs`의 계약 예시를 테스트에서 직접 읽는 첫 사례다. 지금까지는 주석으로만
 * 가리켰다. 계약 예시가 바뀌었는데 픽스처가 그대로면 화면 테스트는 옛 모양으로 통과한다.
 *
 * <p>상세는 값이 아니라 <b>키 집합</b>과 분기에 쓰는 값(`status`·`validationTier`)만 본다. 전략 이름·종목
 * 같은 값은 픽스처가 일부러 지어낸 것이다(`resultFixtures.ts` 머리 주석). 시계열은 지어낼 이유가 없어
 * 값까지 같게 둔다 — 상세의 최대 하락 폭과 저점이 맞아야 그래프가 음영을 그린다.
 */

const examplesDir = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  '..',
  'docs',
  'specs',
  'result-api',
  'examples',
)

function readExample(name: string): Record<string, unknown> {
  return JSON.parse(readFileSync(resolve(examplesDir, name), 'utf8')) as Record<string, unknown>
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 중첩된 키를 점 경로로 모은다. 배열은 첫 원소의 모양만 본다. 자유 형식 사전(`parameters`)은 값이
 * 합성이라 키 이름을 대조하지 않는다. 비중 기록의 `target`·`actual`도 종목 식별자를 키로 쓰는 사전이라 같다.
 */
function keyPaths(value: unknown, prefix = ''): string[] {
  if (Array.isArray(value)) return value.length > 0 ? keyPaths(value[0], `${prefix}[]`) : []
  if (!isRecord(value)) return []
  if (/(parameters|\.target|\.actual)$/.test(prefix)) return []

  return Object.keys(value)
    .sort()
    .flatMap((key) => {
      const path = prefix ? `${prefix}.${key}` : key
      return [path, ...keyPaths(value[key], path)]
    })
}

function envelope(data: unknown) {
  return { success: true, data, error: null }
}

describe('결과 픽스처와 계약 예시', () => {
  it.each([
    ['result-detail-completed.json', resultDetailCompleted],
    ['result-detail-running.json', resultDetailRunning],
    ['result-detail-cancelled-no-summary.json', resultDetailCancelledNoSummary],
  ])('%s 와 키 집합·status·validationTier가 같다', (file, fixture) => {
    const example = readExample(file)
    const data = example.data as Record<string, unknown>

    expect(keyPaths(envelope(fixture))).toEqual(keyPaths(example))
    expect(fixture.status).toBe(data.status)
    expect(fixture.validationTier).toBe(data.validationTier)
  })

  it('만료 본문이 계약 예시와 같다', () => {
    expect(resultExpiredEnvelope).toEqual(readExample('result-expired.json'))
  })

  it('시계열이 계약 예시와 같은 키와 같은 점 수를 갖는다', () => {
    const example = readExample('result-timeseries.json')
    const points = (example.data as { points: unknown[] }).points

    expect(keyPaths(envelope(resultTimeseries))).toEqual(keyPaths(example))
    expect(resultTimeseries.points).toHaveLength(points.length)
  })

  it('시계열의 값이 계약 예시와 같다', () => {
    expect(resultTimeseries).toEqual(readExample('result-timeseries.json').data)
  })

  it('완료 상세의 최대 하락 폭이 시계열의 저점과 같아 음영 구간이 잡힌다', () => {
    const lowest = resultTimeseries.points.reduce((low, point) =>
      Number(point.drawdown) < Number(low.drawdown) ? point : low,
    )

    expect(lowest.drawdown).toBe(resultDetailCompleted.summary?.maxDrawdown)
    expect(
      findDrawdownWindow(resultTimeseries.points, resultDetailCompleted.summary?.maxDrawdown),
    ).not.toBeNull()
  })

  it('어느 예시에도 004의 내부 실행 식별자가 없다', () => {
    for (const file of [
      'result-detail-completed.json',
      'result-detail-running.json',
      'result-detail-cancelled-no-summary.json',
      'result-expired.json',
      'result-timeseries.json',
    ]) {
      expect(JSON.stringify(readExample(file)), file).not.toMatch(/BT-|backtestId/)
    }
  })
})

const backtestExamplesDir = resolve(examplesDir, '..', '..', 'backtest-api', 'examples')

describe('상세 패널 픽스처와 004 하위 자원 예시', () => {
  it.each([
    ['backtest-weights.json', resultWeights],
    ['backtest-exceptions.json', resultExceptions],
    ['backtest-trades.json', resultTradePage],
    ['backtest-positions.json', resultPositions],
  ])('%s 에서 backtestId만 뺀 키 집합과 같다', (file, fixture) => {
    const example = JSON.parse(
      readFileSync(resolve(backtestExamplesDir, file), 'utf8'),
    ) as { data: Record<string, unknown> }
    // 공개 경로는 004 응답에서 `backtestId`만 버린다(contracts "공개 응답에서 버리는 필드").
    const { backtestId: _dropped, ...publicData } = example.data

    // 선택 키(`plannedDate`·`riskScale` 등)는 예시에도 픽스처에도 없는 채로 대조한다.
    expect(keyPaths(envelope(fixture))).toEqual(keyPaths({ ...example, data: publicData }))
    expect(_dropped).toMatch(/^BT-/)
  })
})

describe('makeEquitySeries', () => {
  it('가장 낮은 하락 비율이 정한 저점에 오고 고점에서는 0이다', () => {
    const series = makeEquitySeries({ points: 129, peakIndex: 40, troughIndex: 55 })
    const drawdowns = series.map((point) => Number(point.drawdown))
    const minimum = Math.min(...drawdowns)

    expect(drawdowns.indexOf(minimum)).toBe(55)
    expect(drawdowns[40]).toBe(0)
    expect(minimum).toBeLessThan(0)
  })

  it('긴 계열(1,900점)도 거래일 순서대로 만든다', () => {
    const series = makeEquitySeries({ points: 1_900, peakIndex: 700, troughIndex: 900 })

    expect(series).toHaveLength(1_900)
    expect(series.every((point, index) => index === 0 || point.date > series[index - 1].date)).toBe(
      true,
    )
  })

  it('고점이 저점보다 뒤면 만들지 않는다', () => {
    expect(() => makeEquitySeries({ points: 10, peakIndex: 5, troughIndex: 3 })).toThrow()
  })
})

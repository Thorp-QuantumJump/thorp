import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Disclosure } from './Disclosure'

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

function channel(value: number): number {
  const normalized = value / 255
  return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
}

function luminance(hex: string): number {
  const [red, green, blue] = [1, 3, 5].map((offset) => channel(parseInt(hex.slice(offset, offset + 2), 16)))
  return red * 0.2126 + green * 0.7152 + blue * 0.0722
}

function token(name: string): string {
  const match = css.match(new RegExp(`--thorp-${name}: (#[0-9a-fA-F]{6});`))
  if (!match) throw new Error(`토큰 값 없음: ${name}`)
  return match[1]
}

function contrast(first: string, second: string): number {
  const values = [luminance(token(first)), luminance(token(second))].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

describe('신뢰 범위 공통 표시', () => {
  it('보이는 문구, 결과 맥락, 키보드 초점을 함께 제공한다', () => {
    render(<Disclosure label="역사 비교" context="시험 분야 2026-07 검색 결과"><p>서버 문구입니다.</p></Disclosure>)
    const notice = screen.getByRole('status', { name: '시험 분야 2026-07 검색 결과: 역사 비교' })
    expect(notice.textContent).toContain('서버 문구입니다.')
    expect(notice.getAttribute('tabindex')).toBe('0')
    expect(notice.getAttribute('aria-live')).toBe('polite')
  })

  it.each([
    ['foreground', 'surface'], ['copy', 'surface'], ['warning', 'surface'], ['negative', 'surface'],
  ])('%s 문구가 표시 표면에서 4.5:1 이상 대비를 갖는다', (foreground, background) => {
    expect(contrast(foreground, background)).toBeGreaterThanOrEqual(4.5)
  })
})

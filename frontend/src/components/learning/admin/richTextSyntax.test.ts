import { describe, expect, it } from 'vitest'
import type { DraftRun } from '@/types/learningAdmin'
import { formatRichText, parseRichText, richTextLength } from './richTextSyntax'

/**
 * 013 T174. 편집 문법(research R26) — 문법 문자열과 조각 배열이 왕복하고, 틀린 문법은 줄·열과 함께
 * 거절한다. 원고 가져오기와 저장이 같은 해석기를 쓰므로 여기서 어긋나면 두 곳이 함께 틀린다.
 */

describe('parseRichText', () => {
  it('용어·출처 표시를 조각으로 바꾸고 나머지는 글자 조각 하나로 잇는다', () => {
    expect(
      parseRichText(
        '앞 글자 {{용어:ebb-gap|썰물 간격}}을 본다{{출처:합성 출처|https://example.com/a}}',
      ),
    ).toEqual({
      ok: true,
      runs: [
        { text: '앞 글자 ' },
        { term: 'ebb-gap', text: '썰물 간격' },
        { text: '을 본다' },
        { source: { label: '합성 출처', url: 'https://example.com/a' } },
      ],
    })
  })

  it('빈 문자열은 빈 배열이다', () => {
    expect(parseRichText('')).toEqual({ ok: true, runs: [] })
  })

  it('\\{{는 글자 {{이고 표시로 읽지 않는다', () => {
    expect(parseRichText('a \\{{용어:x|y}} b')).toEqual({
      ok: true,
      runs: [{ text: 'a {{용어:x|y}} b' }],
    })
  })

  it('닫히지 않은 {{는 그 자리의 줄·열과 함께 거절한다', () => {
    const result = parseRichText('첫 줄\n둘째 {{용어:x|y')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues).toHaveLength(1)
    expect(result.issues[0]).toMatchObject({ line: 2, column: 4 })
  })

  it('모르는 표시 이름, |가 없는 표시, 빈 식별자를 모두 모아 거절한다', () => {
    const result = parseRichText('{{굵게:가}} {{용어:x}} {{용어:|글자}} {{출처:이름|}}')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues.map((issue) => issue.column)).toEqual([1, 10, 19, 30])
  })

  it('표시 안에 다시 {{가 있으면 앞 표시를 닫지 않은 것으로 본다', () => {
    const result = parseRichText('{{용어:a|b {{용어:c|d}}')
    expect(result.ok).toBe(false)
  })

  it('칸이 허용하지 않는 조각은 거절한다', () => {
    const noSource = parseRichText('목록 항목{{출처:이름|https://example.com}}', {
      term: false,
      source: true,
    })
    expect(noSource.ok).toBe(true)

    const listTerm = parseRichText('{{용어:x|글자}}', { term: false, source: true })
    expect(listTerm.ok).toBe(false)
    if (listTerm.ok) return
    expect(listTerm.issues[0].message).toBe('이 칸에는 용어 표시를 쓸 수 없습니다.')
  })
})

describe('formatRichText', () => {
  const runs: DraftRun[] = [
    { text: '괄호 {{ 하나와 ' },
    { term: 'keel-room', text: '용골 여유' },
    { text: '\n둘째 줄' },
    { source: { label: '합성 출처', url: 'https://example.com/b' } },
  ]

  it('조각 → 문법 → 조각이 같다', () => {
    const syntax = formatRichText(runs)
    expect(syntax).toBe(
      '괄호 \\{{ 하나와 {{용어:keel-room|용골 여유}}\n둘째 줄{{출처:합성 출처|https://example.com/b}}',
    )
    expect(parseRichText(syntax)).toEqual({ ok: true, runs })
  })

  it('문법 → 조각 → 문법이 같다', () => {
    const syntax = '역슬래시 \\ 와 \\\\{{ 둘 {{용어:a|b}}'
    const parsed = parseRichText(syntax)
    expect(parsed.ok).toBe(true)
    if (!parsed.ok) return
    expect(formatRichText(parsed.runs)).toBe(syntax)
  })
})

describe('richTextLength', () => {
  it('글자·용어 언급의 글자만 세고 출처 표시는 세지 않는다', () => {
    expect(
      richTextLength([
        { text: '가나' },
        { term: 'x', text: '다라마' },
        { source: { label: '긴 출처 이름', url: 'https://example.com' } },
      ]),
    ).toBe(5)
  })
})

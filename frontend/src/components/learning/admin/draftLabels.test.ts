import { describe, expect, it } from 'vitest'
import { describeField, describeFieldPath } from './draftLabels'

/**
 * 013 B14 converge. 위치 이름은 모두 1부터 센다. 서버의 0부터 센 경로를 그대로 보이면 같은 화면의 문법 오류
 * ('본문 3번째 블록')와 한 칸 어긋나 운영자가 다른 블록을 고친다.
 */

describe('describeField', () => {
  it.each([
    ['lines[1]', '2번째 줄'],
    ['items[0]', '1번째 항목'],
    ['rows[0][1]', '1행 2열'],
    ['columns[2]', '3번째 열 이름'],
    ['series[0].name', '1번째 계열 이름'],
    ['aliases[3]', '4번째 다른 이름'],
    ['wrong', '오해'],
    ['source.url', '출처 · 주소'],
    ['choices[1].text', '2번째 보기 · 내용'],
    ['새필드', '새필드'],
  ])('%s → %s', (field, expected) => {
    expect(describeField(field)).toBe(expected)
  })
})

describe('describeFieldPath', () => {
  it.each([
    ['blocks[2].content', '본문 3번째 블록 · 내용 (blocks[2].content)'],
    ['blocks[4].rows[0][1]', '본문 5번째 블록 · 1행 2열 (blocks[4].rows[0][1])'],
    ['terms[0].transferFrom', '1번째 용어 · 넘겨받기 (terms[0].transferFrom)'],
    ['quiz[1].questionId', '2번째 확인 문제 · 식별자 (quiz[1].questionId)'],
    ['example.sectors[0].weightTenths', '예시 1번째 섹터 · 비중 (example.sectors[0].weightTenths)'],
    ['quiz', '확인 문제 (quiz)'],
    ['blocks[0]', '본문 1번째 블록 (blocks[0])'],
    ['unknown.path', 'unknown.path'],
  ])('%s → %s', (path, expected) => {
    expect(describeFieldPath(path)).toBe(expected)
  })
})

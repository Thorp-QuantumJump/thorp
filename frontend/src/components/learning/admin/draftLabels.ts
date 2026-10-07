import type { AdminDraftBlockV1, AdminDraftBlockV2 } from '@/types/learningAdmin'

/**
 * 관리 화면이 블록·필드를 사람이 읽는 이름으로 부를 때 쓰는 표다. 편집기, 저장 전 문법 오류, 게시 전 검사
 * 위치(`PublishCheckResultList`)가 같은 이름을 써야 운영자가 오류가 가리키는 칸을 찾는다.
 *
 * 서버가 주지 않는 이름이라 화면이 짓는다. 검사 문장(`message`)은 여기서 짓지 않는다(research R7).
 */

export const BLOCK_LABELS: Record<AdminDraftBlockV1['type'] | AdminDraftBlockV2['type'], string> = {
  keySummary: '핵심 요약',
  heading: '소제목',
  paragraph: '문단',
  term: '용어',
  hypothetical: '가상의 계산 예',
  misconception: '자주 하는 오해',
  list: '목록',
  table: '비교 표',
  diagram: '가상 도식',
  institutionNote: '제도 안내',
}

const FIELD_NAMES: Record<string, string> = {
  content: '내용',
  text: '내용',
  wrong: '오해',
  correction: '바로잡기',
  caption: '표 제목',
  columns: '열 이름',
  title: '도식 제목',
  unit: '단위',
  categories: '가로축 칸',
  series: '계열',
  description: '설명',
  asOf: '기준 시점',
  source: '출처',
  prompt: '질문',
  explanation: '풀이',
  choices: '보기',
  label: '이름',
  aliases: '다른 이름',
  meaning: '뜻',
  reading: '읽는 법',
  caveat: '한계',
  sentence: '설명 문장',
}

/**
 * 필드 표기(`lines[1]`, `rows[0][1]`, `wrong` 등, data-model §8-4)를 사람이 읽는 이름으로 바꾼다. 표에 없는
 * 이름은 그대로 둔다. 서버가 필드를 늘려도 위치를 잃지 않게 하려는 것이다.
 */
export function describeField(field: string): string {
  const line = /^lines\[(\d+)\]$/.exec(field)
  if (line) return `${Number(line[1]) + 1}번째 줄`
  const item = /^items\[(\d+)\]$/.exec(field)
  if (item) return `${Number(item[1]) + 1}번째 항목`
  const cell = /^rows\[(\d+)\]\[(\d+)\]$/.exec(field)
  if (cell) return `${Number(cell[1]) + 1}행 ${Number(cell[2]) + 1}열`
  return FIELD_NAMES[field] ?? field
}

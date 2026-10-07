import type { AdminDraftBlockV1, AdminDraftBlockV2 } from '@/types/learningAdmin'

/**
 * 관리 화면이 블록·필드를 사람이 읽는 이름으로 부를 때 쓰는 표다. 편집기, 저장 전 문법 오류, 저장 400의 위치,
 * 게시 전 검사 위치(`PublishCheckResultList`)가 같은 이름을 써야 운영자가 오류가 가리키는 칸을 찾는다. 순번은
 * 모두 1부터 센다. 서버의 0부터 센 경로를 그대로 보이면 같은 화면의 다른 안내와 한 칸 어긋난다.
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
  kind: '도식 종류',
  ordered: '번호 목록',
  categories: '가로축 칸',
  series: '계열',
  description: '설명',
  asOf: '기준 시점',
  source: '출처',
  url: '주소',
  prompt: '질문',
  explanation: '풀이',
  choices: '보기',
  correct: '정답',
  // 식별자는 늘 앞 마디(n번째 확인 문제·보기·용어) 뒤에 붙으므로 '식별자'만 쓴다.
  questionId: '식별자',
  choiceId: '식별자',
  termId: '식별자',
  transferFrom: '넘겨받기',
  label: '이름',
  aliases: '다른 이름',
  meaning: '뜻',
  reading: '읽는 법',
  caveat: '한계',
  sentence: '설명 문장',
  code: '섹터 코드',
  weightTenths: '비중',
}

/** 필드 안의 순번 표기. `[n]`을 1부터 센 'n번째 …'로 바꾼다. */
const INDEXED_FIELDS: [RegExp, (match: RegExpExecArray) => string][] = [
  [/^lines\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 줄`],
  [/^items\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 항목`],
  [/^rows\[(\d+)\]\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}행 ${Number(m[2]) + 1}열`],
  [/^rows\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}행`],
  [/^columns\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 열 이름`],
  [/^categories\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 칸`],
  [/^series\[(\d+)\]\.name$/, (m) => `${Number(m[1]) + 1}번째 계열 이름`],
  [/^series\[(\d+)\]\.values$/, (m) => `${Number(m[1]) + 1}번째 계열 값`],
  [/^series\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 계열`],
  [/^aliases\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 다른 이름`],
  [/^choices\[(\d+)\]$/, (m) => `${Number(m[1]) + 1}번째 보기`],
]

/**
 * 필드 표기(`lines[1]`, `rows[0][1]`, `series[0].name`, `wrong` 등, data-model §8-4)를 사람이 읽는 이름으로
 * 바꾼다. 점으로 이어진 표기(`choices[1].text`, `source.url`)는 마디마다 바꿔 잇는다. 표에 없는 이름은 그대로
 * 둔다. 서버가 필드를 늘려도 위치를 잃지 않게 하려는 것이다.
 */
export function describeField(field: string): string {
  for (const [pattern, name] of INDEXED_FIELDS) {
    const match = pattern.exec(field)
    if (match) return name(match)
  }
  if (field in FIELD_NAMES) return FIELD_NAMES[field]
  const dot = field.indexOf('.')
  if (dot > 0)
    return `${describeField(field.slice(0, dot))} · ${describeField(field.slice(dot + 1))}`
  return field
}

/** 저장 400의 `details.fields` 경로 맨 앞 마디. 초안의 어느 부분인지 가리킨다. */
const ROOT_PATHS: [RegExp, (n: number) => string][] = [
  [/^blocks\[(\d+)\]/, (n) => `본문 ${n}번째 블록`],
  [/^terms\[(\d+)\]/, (n) => `${n}번째 용어`],
  [/^quiz\[(\d+)\]/, (n) => `${n}번째 확인 문제`],
  [/^sources\[(\d+)\]/, (n) => `${n}번째 출처`],
  [/^example\.sectors\[(\d+)\]/, (n) => `예시 ${n}번째 섹터`],
]

const ROOT_NAMES: Record<string, string> = {
  title: '제목',
  question: '질문',
  readingOrder: '읽는 순서',
  stageKey: '단계',
  stagePosition: '단계 안 순서',
  format: '형식',
  blocks: '본문',
  terms: '용어',
  quiz: '확인 문제',
  sources: '출처',
  example: '예시',
  expectedDraftVersion: '초안 번호',
}

/**
 * 저장 400의 필드 경로(`blocks[2].content`, `quiz[0].choices[1].choiceId` 등, Bean Validation 표기)를 사람이
 * 읽는 위치로 바꾸고, 원래 경로를 괄호 안에 함께 둔다. 원래 경로는 원고 JSON에서 그 자리를 찾을 때 쓴다.
 */
export function describeFieldPath(path: string): string {
  if (path in ROOT_NAMES) return `${ROOT_NAMES[path]} (${path})`
  for (const [pattern, name] of ROOT_PATHS) {
    const match = pattern.exec(path)
    if (!match) continue
    const head = name(Number(match[1]) + 1)
    const rest = path.slice(match[0].length).replace(/^\./, '')
    return rest === '' ? `${head} (${path})` : `${head} · ${describeField(rest)} (${path})`
  }
  return path
}

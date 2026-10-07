import type { ReleaseLogEntry, StageStatus, StageView } from '@/types/learning'

/**
 * 커리큘럼 화면의 문장 틀(013 design-notes §7, FR-031).
 *
 * <p>단계 이름·편수·제목 같은 값은 모두 커리큘럼 응답에서 오고, 여기서는 그 값을 정해진 문장에 끼운다. 단계를
 * 공개하지 않았거나 앞으로 공개한다는 뜻의 잠금·예고 어휘('잠김'·'해금'·'열립니다'·'공개 예정'·'곧')는 쓰지 않는다
 * (FR-031 표시 규칙).
 */

/** 공개되지 않은 단계의 사유. */
export const PREPARING_REASON = '이 단계의 글을 모두 준비하면 한 번에 공개합니다.'

/** 공개했지만 게시된 글이 0편인 단계의 사유. 위 사유를 붙이지 않는다(FR-031). */
export const REPREPARING_REASON = '이 단계의 글을 다시 준비하고 있습니다. 글이 게시되면 이 자리에 보입니다.'

/** 공개 안내에 로그인 사용자만 덧붙이는 문장. 변경 기록에는 붙이지 않는다. */
export const RECORDS_KEPT_SENTENCE = '지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.'

/** 읽는 순서는 제안일 뿐이라는 안내(design-notes §3.1). */
export const READING_ORDER_NOTE = '읽는 순서를 제안합니다. 공개된 글은 어느 것이든 바로 열 수 있습니다.'

/** 이전 판 글의 화면 낭독 설명. 번호와 상태가 없는 까닭을 함께 알린다. */
export const PREVIOUS_EDITION_NOTE = '다시 쓰기 전의 글입니다. 나의 학습에 기록하지 않습니다.'

export function preparingReason(status: StageStatus): string | null {
  if (status === 'PREPARING') return PREPARING_REASON
  if (status === 'REPREPARING') return REPREPARING_REASON
  return null
}

/** 공개 경계 한 줄과 공개 안내 '○단계 보기'가 가리키는 단계 자리의 주소 조각이다. */
export function stageHash(stageNumber: number): string {
  return `#stage-${stageNumber}`
}

/** `#stage-3`에서 단계 번호를 읽는다. 다른 모양이면 null이다. */
export function stageNumberFromHash(hash: string): number | null {
  const match = /^#stage-(\d{1,3})$/.exec(hash)
  return match ? Number(match[1]) : null
}

/**
 * 단계 번호 묶음을 읽는 꼴로 쓴다. 하나면 '1단계', 둘이면 '1·2단계', 셋 이상 이어지면 '3~6단계'다. 이어지지 않는
 * 번호는 가운뎃점으로 잇는다.
 */
export function stageRange(numbers: readonly number[]): string {
  const sorted = [...numbers].sort((a, b) => a - b)
  const consecutive = sorted.every((n, i) => i === 0 || n === sorted[i - 1] + 1)
  if (sorted.length >= 3 && consecutive) return `${sorted[0]}~${sorted[sorted.length - 1]}단계`
  return `${sorted.join('·')}단계`
}

/** 공개 경계로 세는 단계. `REPREPARING`도 공개된 단계다(계약 §1). */
export function isReleased(stage: Pick<StageView, 'status'>): boolean {
  return stage.status !== 'PREPARING'
}

/**
 * 공개 경계 한 줄(예: '지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.'). 공개하지 않은 단계가 없으면
 * 경계가 없으므로 null이다.
 */
export function boundarySentence(stages: readonly StageView[]): string | null {
  const released = stages.filter(isReleased).map((stage) => stage.number)
  const preparing = stages.filter((stage) => !isReleased(stage)).map((stage) => stage.number)
  if (released.length === 0 || preparing.length === 0) return null
  return `지금 공개된 단계는 ${stageRange(released)}입니다. ${stageRange(preparing)}는 준비 중입니다.`
}

/** 받침 있는 낱말 뒤의 목적격 조사. 단계 이름처럼 한글로 끝나는 낱말만 가린다. */
export function objectParticle(word: string): '을' | '를' {
  const last = word.trim().slice(-1)
  const code = last.charCodeAt(0)
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 === 0 ? '를' : '을'
  return '를'
}

function countSentence(entry: Pick<ReleaseLogEntry, 'newCount' | 'rewrittenCount'>): string {
  const parts: string[] = []
  if (entry.newCount > 0) parts.push(`새 글 ${entry.newCount}편`)
  if (entry.rewrittenCount > 0) parts.push(`다시 쓴 글 ${entry.rewrittenCount}편`)
  return parts.length === 0 ? '' : `${parts.join('과 ')}입니다.`
}

export type NoticeLine = {
  /** 합친 한 줄 */
  text: string
  /** '○단계 보기'가 가리킬 단계. 가장 최근 줄의 단계다 */
  stageNumber: number
  /** [닫기]가 기억할 줄들 */
  entryKeys: string[]
}

/**
 * 7일 안의 공개 안내 줄을 한 줄로 합친다(FR-031 '7일 안에 공개가 둘 이상 겹치면 한 줄로 합친다').
 *
 * <p>일어난 순서대로 잇는다. 단계 공개는 '2단계 '수익과 위험 읽기'를 공개했습니다. 새 글 3편과 다시 쓴 글
 * 1편입니다.', 공개된 단계에 더한 글은 단계마다 묶어 '2단계에 새 글 1편을 더했습니다.'다. 로그인 사용자에게는 1단계가
 * 아닌 단계 공개가 들어 있을 때만 기록이 그대로라는 문장을 덧붙인다. 첫 단계 공개 안내와 글 추가 안내에는 붙이지
 * 않는다(나의 학습은 첫 단계 공개 때 처음 켜진다).
 *
 * @param notices 서버가 준 최신 순서의 안내 줄. 닫은 줄은 미리 뺀다
 */
export function noticeLine(notices: readonly ReleaseLogEntry[], signedIn: boolean): NoticeLine | null {
  if (notices.length === 0) return null
  const oldestFirst = [...notices].reverse()

  const sentences: string[] = []
  const addedByStage = new Map<number, number>()
  const addedSentenceIndex = new Map<number, number>()
  for (const entry of oldestFirst) {
    if (entry.kind === 'STAGE_RELEASED') {
      const counts = countSentence(entry)
      sentences.push(
        `${entry.stageNumber}단계 '${entry.stageName}'${objectParticle(entry.stageName)} 공개했습니다.${counts ? ` ${counts}` : ''}`,
      )
      continue
    }
    const added = (addedByStage.get(entry.stageNumber) ?? 0) + entry.newCount
    addedByStage.set(entry.stageNumber, added)
    const sentence = `${entry.stageNumber}단계에 새 글 ${added}편을 더했습니다.`
    const index = addedSentenceIndex.get(entry.stageNumber)
    if (index === undefined) {
      addedSentenceIndex.set(entry.stageNumber, sentences.length)
      sentences.push(sentence)
    } else {
      sentences[index] = sentence
    }
  }

  const keepsRecords =
    signedIn && notices.some((entry) => entry.kind === 'STAGE_RELEASED' && entry.stageNumber !== 1)
  if (keepsRecords) sentences.push(RECORDS_KEPT_SENTENCE)

  return {
    text: sentences.join(' '),
    stageNumber: notices[0].stageNumber,
    entryKeys: notices.map((entry) => entry.entryKey),
  }
}

/**
 * 변경 기록 한 줄의 본문(날짜 뒤, 예: "2단계 공개 · 새 글 3편, 다시 쓴 글 1편('수익과 손실')"). 기록이 그대로라는
 * 문장은 넣지 않는다(FR-031).
 */
export function changeLogText(entry: ReleaseLogEntry): string {
  if (entry.kind === 'ARTICLE_ADDED') {
    const title = entry.articleTitle ? `('${entry.articleTitle}')` : ''
    return `${entry.stageNumber}단계에 글 추가 · 새 글 ${entry.newCount}편${title}`
  }
  const parts: string[] = []
  if (entry.newCount > 0) parts.push(`새 글 ${entry.newCount}편`)
  if (entry.rewrittenCount > 0) {
    const titles = entry.rewrittenTitles.length
      ? `(${entry.rewrittenTitles.map((title) => `'${title}'`).join(', ')})`
      : ''
    parts.push(`다시 쓴 글 ${entry.rewrittenCount}편${titles}`)
  }
  return parts.length === 0
    ? `${entry.stageNumber}단계 공개`
    : `${entry.stageNumber}단계 공개 · ${parts.join(', ')}`
}

const KST_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** ISO 시각을 한국 날짜 `YYYY-MM-DD`로 쓴다. 읽을 수 없으면 받은 값 그대로다. */
export function kstDate(isoInstant: string): string {
  const at = new Date(isoInstant)
  return Number.isNaN(at.getTime()) ? isoInstant : KST_DATE.format(at)
}

/** 단계의 공개된 글을 모두 읽는 데 드는 예상 시간(분). */
export function stageMinutes(stage: Pick<StageView, 'articles'>): number {
  return stage.articles.reduce((sum, article) => sum + article.readingMinutes, 0)
}

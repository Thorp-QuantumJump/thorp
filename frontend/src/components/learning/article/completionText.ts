import type { Gained, GainedExp, StageProgress } from '@/types/learningMe'

/**
 * 완료 카드의 글자(013 FR-021·FR-030, 목업 '글 끝'). 카드 부품과 따로 두어 화면 테스트 없이 문장을 확인한다.
 */

/**
 * 이 글 화면에서 받은 응답 하나의 `gained`. 완료 카드의 경험치 목록·새 배지·새 레벨과 조용한 알림이 이것으로
 * 짓는다.
 */
export type GainedEntry = {
  gained: Gained
  /** 받은 때(ISO 8601). 완료 카드의 '받음 날짜'에 쓴다 */
  receivedAt: string
  /** 완료 응답이면 그때의 단계 진행이다. 단계를 다 읽은 순간의 문장을 짓는다. 답·다시 풀기 응답이면 `null` */
  stageProgress: StageProgress | null
}

const dateFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** ISO 시각을 서울 날짜 `YYYY-MM-DD`로 보인다. 읽지 못하면 받은 그대로 둔다. */
export function formatCompletedDate(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : dateFormat.format(date)
}

/** 장부 줄 이름(data-model §12-3). 모르는 종류도 버리지 않고 '경험치'로 보인다. */
export function expLabel(exp: GainedExp): string {
  switch (exp.kind) {
    case 'ARTICLE_COMPLETED':
      return '글 완료'
    case 'ANSWER_CORRECT':
      return '첫 답 정답'
    case 'ALL_CORRECT':
      return '모두 맞힘'
    case 'STAGE_COMPLETED':
      return exp.stageNumber ? `${exp.stageNumber}단계 완주` : '단계 완주'
    default:
      return '경험치'
  }
}

/** 숫자 끝소리에 맞춘 '이'·'가'. 2·4·5·9로 끝나면 '가'다(이, 사, 오, 구). */
function subjectParticle(value: number): string {
  return [2, 4, 5, 9].includes(Math.abs(value) % 10) ? '가' : '이'
}

/**
 * 이 응답으로 단계를 다 읽었으면 그 문장(목업 '1단계 글 4편을 모두 읽었습니다.'). 단계 완주 경험치가 생긴
 * 완료 응답에서만 짓는다. 이미 다 읽었던 단계의 글을 다시 열었을 때는 새로 일어난 일이 아니라 짓지 않는다.
 */
export function stageCompletedSentence(
  entry: Pick<GainedEntry, 'gained' | 'stageProgress'>,
): string | null {
  const progress = entry.stageProgress
  if (!progress?.stageCompleted) return null
  const completedNow = entry.gained.exp.some(
    (exp) =>
      exp.kind === 'STAGE_COMPLETED' &&
      (exp.stageNumber === undefined || exp.stageNumber === progress.stageNumber),
  )
  return completedNow
    ? `${progress.stageNumber}단계 글 ${progress.publishedCount}편을 모두 읽었습니다.`
    : null
}

/** 새로 일어난 일(경험치·배지·레벨)이 있는 `gained`인가. */
export function hasGainedNews(gained: Gained): boolean {
  return gained.exp.length > 0 || gained.badges.length > 0 || gained.levelAfter > gained.levelBefore
}

/**
 * 응답 하나에서 '새 배지'로 말할 배지. 레벨이 오른 응답에는 그 레벨의 레벨 갈래 배지가 함께 온다(서버는 배지 목록을
 * 레벨 갈래부터 정산한다, data-model §12-4). 레벨은 '레벨 3이 되었습니다'로 따로 말하므로 그 응답의 레벨 갈래 배지는
 * 새 배지로 다시 말하지 않는다(design-notes §7 완료 카드 상태 문장, §3.1).
 */
export function newBadgesOf(entry: Pick<GainedEntry, 'gained'>): GainedEntry['gained']['badges'] {
  const { badges, levelBefore, levelAfter } = entry.gained
  return levelAfter > levelBefore ? badges.filter((badge) => badge.family !== 'LEVEL') : badges
}

/** 받은 것 가운데 레벨이 오른 마지막 응답. 오르지 않았으면 `null`이다. */
export function reachedLevel<T extends Pick<GainedEntry, 'gained'>>(
  entries: readonly T[],
): T | null {
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const { gained } = entries[index]
    if (gained.levelAfter > gained.levelBefore) return entries[index]
  }
  return null
}

/**
 * 새로 일어난 일의 문장(FR-030). 단계를 다 읽은 문장, 새 배지, 새 레벨 순서다. 무엇을 얻었는지만 알리고
 * 서두르게 하지 않는다. 새로 일어난 일이 없으면 빈 문자열이다.
 *
 * <p>완료 카드의 보이는 상태 문장은 이 화면에서 받은 것 전부로, 조용한 알림은 방금 받은 응답 하나로 짓는다.
 * 알림이 앞서 알린 것까지 다시 읽지 않게 하려는 것이다(quickstart §13 '한 번 읽힌다').
 */
export function gainedNotice(
  entries: readonly Pick<GainedEntry, 'gained' | 'stageProgress'>[],
): string {
  const parts: string[] = []
  for (const entry of entries) {
    const stage = stageCompletedSentence(entry)
    if (stage) parts.push(stage)
    for (const badge of newBadgesOf(entry)) parts.push(`새 배지: ${badge.name}.`)
  }
  const level = reachedLevel(entries)?.gained.levelAfter
  if (level !== undefined) parts.push(`레벨 ${level}${subjectParticle(level)} 되었습니다.`)
  return parts.join(' ')
}

/**
 * '나의 학습' 응답 하나의 `gained`를 알릴 문장(계약 §1, FR-030). 그 요청의 정산으로 새로 생긴 배지·레벨이다. 이
 * 응답에는 단계 진행이 없어 단계를 다 읽은 문장은 짓지 않는다. 새 배지·레벨이 없으면 빈 문자열이다.
 */
export function settledGainedNotice(gained: Gained): string {
  return gainedNotice([{ gained, stageProgress: null }])
}

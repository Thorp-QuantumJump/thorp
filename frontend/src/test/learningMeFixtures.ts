import type {
  AnswerResult,
  BadgeView,
  CompletionResult,
  ContinueReading,
  Gained,
  LearnerArticleRecord,
  LearnerOverview,
  QuizSummary,
  RetryResult,
} from '@/types/learningMe'

/**
 * 013 나의 학습 응답의 합성 픽스처. `learningFixtures.currentArticle`(`swell-reading`, 문제 q1·q3)과 짝을 이룬다.
 * 값은 모두 가상의 예다.
 */

export const emptyGained: Gained = {
  exp: [],
  expTotal: 0,
  badges: [],
  levelBefore: 2,
  levelAfter: 2,
}

export const openQuiz: QuizSummary = {
  total: 2,
  answered: 0,
  correct: 0,
  allAnswered: false,
  mastered: false,
  masteredVia: null,
  retryAvailable: false,
}

export const resumeNext: ContinueReading = {
  kind: 'RESUME',
  articleId: 'tide-reading',
  number: '2-4',
  title: '물때 읽기',
  percent: 40,
  moreStagesPreparing: true,
}

export const allDone: ContinueReading = {
  kind: 'ALL_DONE',
  articleId: null,
  number: null,
  title: null,
  percent: null,
  moreStagesPreparing: true,
}

/** 연 적이 없는 글의 기록. */
export const unreadRecord: LearnerArticleRecord = {
  articleId: 'swell-reading',
  state: 'UNREAD',
  percent: 0,
  completedAt: null,
  answers: [],
  quiz: openQuiz,
  stageProgress: { stageNumber: 2, completedCount: 1, publishedCount: 4, stageCompleted: false },
  continueReading: resumeNext,
}

/** 62%까지 읽은 글의 기록. */
export const readingRecord: LearnerArticleRecord = { ...unreadRecord, state: 'READING', percent: 62 }

/** 완료했고 첫 문제만 맞힌 글의 기록. */
export const completedRecord: LearnerArticleRecord = {
  ...unreadRecord,
  state: 'COMPLETED',
  percent: 100,
  completedAt: '2026-11-20T03:10:00Z',
  answers: [{ questionId: 'q1', choiceId: 'c2', correct: true, answeredAt: '2026-11-20T03:11:00Z' }],
  quiz: {
    ...openQuiz,
    answered: 1,
    correct: 1,
    mastered: true,
    masteredVia: 'FIRST_ANSWER',
  },
  stageProgress: { stageNumber: 2, completedCount: 2, publishedCount: 4, stageCompleted: false },
}

/**
 * 이번 요청에서 완료한 응답. 단계 완주와 새 배지, 새 레벨이 함께 생겼다. 서버는 배지를 레벨 갈래부터 정산하므로
 * (data-model §12-4) 레벨이 오르면 그 레벨의 레벨 갈래 배지가 맨 앞에 온다.
 */
export const completionResult: CompletionResult = {
  completedAt: '2026-11-20T03:10:00Z',
  alreadyCompleted: false,
  stageProgress: { stageNumber: 2, completedCount: 4, publishedCount: 4, stageCompleted: true },
  continueReading: allDone,
  gained: {
    exp: [
      { kind: 'ARTICLE_COMPLETED', points: 20 },
      { kind: 'STAGE_COMPLETED', points: 50, stageNumber: 2 },
    ],
    expTotal: 70,
    badges: [
      { badgeKey: 'level-3', family: 'LEVEL', name: '레벨 3' },
      { badgeKey: 'stage-swell-and-risk', family: 'STAGE', name: '2단계 완주' },
    ],
    levelBefore: 2,
    levelAfter: 3,
  },
}

export function answerResult(overrides: Partial<AnswerResult> = {}): AnswerResult {
  return {
    questionId: 'q1',
    choiceId: 'c2',
    correct: true,
    correctChoiceId: 'c2',
    explanation: '합성 풀이 하나다.',
    alreadyAnswered: false,
    quiz: { ...openQuiz, answered: 1, correct: 1, mastered: true, masteredVia: 'FIRST_ANSWER' },
    gained: { ...emptyGained, exp: [{ kind: 'ANSWER_CORRECT', points: 30 }], expTotal: 30 },
    ...overrides,
  }
}

export function retryResult(overrides: Partial<RetryResult> = {}): RetryResult {
  return {
    questionId: 'q1',
    choiceId: 'c2',
    correct: true,
    correctChoiceId: 'c2',
    explanation: '합성 풀이 하나다.',
    quiz: {
      total: 2,
      answered: 2,
      correct: 0,
      allAnswered: true,
      mastered: true,
      masteredVia: 'RETRY',
      retryAvailable: false,
    },
    gained: {
      ...emptyGained,
      badges: [{ badgeKey: 'persistence', family: 'SPECIAL', name: '끈기' }],
    },
    ...overrides,
  }
}

function badge(overrides: Partial<BadgeView> & Pick<BadgeView, 'badgeKey' | 'family' | 'name'>): BadgeView {
  return {
    criterion: `${overrides.name} 기준 문장이다.`,
    status: 'NOT_YET',
    earnedAt: null,
    isNew: false,
    progress: null,
    preparingReason: null,
    stageNumber: null,
    ...overrides,
  }
}

/**
 * '나의 학습' 전체(계약 §1). `learningFixtures.releasedCurriculum`(1·2단계 공개, 글 4편)과 짝을 이룬다. 1단계 두 편을
 * 완료했고 `tide-reading`을 62%까지 읽었다. 레벨 2에 닿은 지 7일 안이고, 1단계 완주 배지를 새로 받았다.
 */
export const learnerOverview: LearnerOverview = {
  level: {
    current: 2,
    exp: 250,
    currentThreshold: 100,
    nextThreshold: 300,
    remainingToNext: 50,
    reachedAt: '2026-11-20T03:03:00Z',
    isNew: true,
    visibleMax: 3,
    unreachableFrom: 4,
    unreachableReason: 'MORE_STAGES',
    milestones: [
      { level: 1, threshold: 0 },
      { level: 2, threshold: 100 },
      { level: 3, threshold: 300 },
      { level: 4, threshold: 600 },
    ],
  },
  counts: { completedArticles: 2, masteredTerms: 3, badges: 5, publishedArticles: 4, publishedTerms: 5 },
  continueReading: { ...resumeNext, articleId: 'tide-reading', number: '2-1', percent: 62 },
  articles: [
    { articleId: 'harbor-notes', state: 'COMPLETED', percent: 100, completedAt: '2026-11-19T02:00:00Z', termCount: 2, masteredTermCount: 2 },
    { articleId: 'dock-fees', state: 'COMPLETED', percent: 100, completedAt: '2026-11-20T02:00:00Z', termCount: 0, masteredTermCount: 0 },
    { articleId: 'tide-reading', state: 'READING', percent: 62, completedAt: null, termCount: 1, masteredTermCount: 0 },
    { articleId: 'swell-reading', state: 'UNREAD', percent: 0, completedAt: null, termCount: 2, masteredTermCount: 0 },
  ],
  stages: [
    { stageKey: 'harbor-basics', number: 1, status: 'OPEN', completedCount: 2, publishedCount: 2, completed: true },
    { stageKey: 'swell-and-risk', number: 2, status: 'OPEN', completedCount: 0, publishedCount: 2, completed: false },
    { stageKey: 'tide-tables', number: 3, status: 'PREPARING', completedCount: null, publishedCount: null, completed: false },
    { stageKey: 'chart-and-cost', number: 4, status: 'PREPARING', completedCount: null, publishedCount: null, completed: false },
    { stageKey: 'reef-checks', number: 5, status: 'PREPARING', completedCount: null, publishedCount: null, completed: false },
    { stageKey: 'weather-and-seas', number: 6, status: 'PREPARING', completedCount: null, publishedCount: null, completed: false },
  ],
  masteredTerms: [
    { termId: 'harbor', label: '항구', articleId: 'harbor-notes', articleNumber: '1-1' },
    { termId: 'berth', label: '선석', articleId: 'harbor-notes', articleNumber: '1-1' },
    { termId: 'quay', label: '안벽', articleId: 'dock-fees', articleNumber: '1-2' },
  ],
  badges: [
    badge({ badgeKey: 'level-2', family: 'LEVEL', name: '레벨 2', status: 'EARNED', earnedAt: '2026-11-20T03:03:00Z', isNew: true }),
    badge({ badgeKey: 'level-3', family: 'LEVEL', name: '레벨 3', progress: { current: 250, target: 300 } }),
    badge({ badgeKey: 'level-4', family: 'LEVEL', name: '레벨 4', status: 'PREPARING', preparingReason: 'MORE_STAGES' }),
    badge({ badgeKey: 'read-1', family: 'READ', name: '읽은 글 1편', status: 'EARNED', earnedAt: '2026-11-01T02:00:00Z' }),
    badge({ badgeKey: 'read-5', family: 'READ', name: '읽은 글 5편', status: 'PREPARING', preparingReason: 'MORE_STAGES' }),
    badge({ badgeKey: 'read-10', family: 'READ', name: '읽은 글 10편', status: 'PREPARING', preparingReason: 'MORE_STAGES' }),
    badge({ badgeKey: 'read-27', family: 'READ', name: '읽은 글 27편', status: 'PREPARING', preparingReason: 'ALL_STAGES' }),
    badge({ badgeKey: 'correct-1', family: 'CORRECT', name: '첫 답 정답 1개', status: 'EARNED', earnedAt: '2026-11-01T02:10:00Z' }),
    badge({ badgeKey: 'correct-10', family: 'CORRECT', name: '첫 답 정답 10개', progress: { current: 3, target: 10 } }),
    badge({ badgeKey: 'perfect-1', family: 'PERFECT', name: '모두 맞힘 1편', progress: { current: 0, target: 1 } }),
    badge({ badgeKey: 'stage-harbor-basics', family: 'STAGE', name: '1단계 완주', status: 'EARNED', earnedAt: '2026-11-20T02:00:00Z', isNew: true, stageNumber: 1 }),
    badge({ badgeKey: 'stage-swell-and-risk', family: 'STAGE', name: '2단계 완주', progress: { current: 0, target: 2 }, stageNumber: 2 }),
    badge({ badgeKey: 'stage-tide-tables', family: 'STAGE', name: '3단계 완주', status: 'PREPARING', preparingReason: 'STAGE_NOT_RELEASED', stageNumber: 3 }),
    badge({ badgeKey: 'terms-1', family: 'TERMS', name: '익힌 용어 1개', status: 'EARNED', earnedAt: '2026-11-01T02:10:00Z' }),
    badge({ badgeKey: 'terms-10', family: 'TERMS', name: '익힌 용어 10개', status: 'PREPARING', preparingReason: 'MORE_STAGES' }),
    badge({ badgeKey: 'persistence', family: 'SPECIAL', name: '끈기' }),
    badge({ badgeKey: 'exploration', family: 'SPECIAL', name: '탐험', status: 'PREPARING', preparingReason: 'ALL_STAGES' }),
  ],
  gained: emptyGained,
}

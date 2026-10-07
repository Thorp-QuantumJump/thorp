import type {
  AnswerResult,
  CompletionResult,
  ContinueReading,
  Gained,
  LearnerArticleRecord,
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

/** 이번 요청에서 완료한 응답. 단계 완주와 새 배지, 새 레벨이 함께 생겼다. */
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
    badges: [{ badgeKey: 'stage-swell-and-risk', family: 'STAGE', name: '2단계 완주' }],
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

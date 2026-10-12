/**
 * spec 013 나의 학습 API 응답의 타입.
 *
 * 원본 계약은 `specs/013-learning-pages/contracts/learning-me-api.md`이며 필드는 그것과 1:1이다
 * (예시는 `docs/specs/learning-api/examples/me*.json`).
 *
 * **브라우저 저장소에 쓰지 않는다.** 이 응답은 화면 상태에만 두고 사용자가 바뀌면 버린다(013 FR-020, research R33).
 */

/** 글의 기록 상태(계약 §1·§2). `READING`의 `percent`는 1~99다. */
export type LearnerArticleState = 'UNREAD' | 'READING' | 'COMPLETED'

/**
 * '이어서 읽기'(계약 §1). `RESUME`은 읽는 중 글을 기록된 위치에서, `START`는 첫 안 읽은 글을 맨 위에서 연다.
 * `ALL_DONE`이면 글 필드가 모두 `null`이다.
 */
export type ContinueReading = {
  kind: 'RESUME' | 'START' | 'ALL_DONE'
  articleId: string | null
  number: string | null
  title: string | null
  percent: number | null
  /** 공개되지 않은 단계가 있어 '다음 단계는 준비 중입니다'를 덧붙일지 */
  moreStagesPreparing: boolean
}

/** 이 글이 놓인 단계의 진행. 그 단계를 다 읽었으면 `stageCompleted`다. */
export type StageProgress = {
  stageNumber: number
  completedCount: number
  publishedCount: number
  stageCompleted: boolean
}

/** 확인 문제 요약. `correct`는 첫 답 기준이라 다시 풀어도 바뀌지 않는다(계약 §6). */
export type QuizSummary = {
  total: number
  answered: number
  correct: number
  allAnswered: boolean
  mastered: boolean
  masteredVia: 'FIRST_ANSWER' | 'RETRY' | null
  retryAvailable: boolean
}

/** 저장된 첫 답 하나. 공개 판에 있는 문제의 것만 온다(계약 §2). */
export type SavedAnswer = {
  questionId: string
  choiceId: string
  correct: boolean
  answeredAt: string
}

/** 장부 줄의 종류(data-model §12-3). 화면은 모르는 이름도 버리지 않고 점수만 보인다. */
export type GainedExpKind =
  | 'ARTICLE_COMPLETED'
  | 'ANSWER_CORRECT'
  | 'ALL_CORRECT'
  | 'STAGE_COMPLETED'
  | (string & Record<never, never>)

export type GainedExp = {
  kind: GainedExpKind
  points: number
  /** 단계 완주 줄에만 있다 */
  stageNumber?: number
}

/**
 * 배지 갈래(계약 §1, data-model §12-4). 응답의 `badges`가 레벨·읽은 글·첫 답 정답·모두 맞힘·단계 완주·익힌 용어·특별
 * 순서로 온다. 서버가 갈래를 더해도 응답 전체를 버리지 않도록 열어 두고, 화면은 모르는 갈래를 특별 갈래 자리에 둔다.
 */
export type BadgeFamily =
  | 'LEVEL'
  | 'READ'
  | 'CORRECT'
  | 'PERFECT'
  | 'STAGE'
  | 'TERMS'
  | 'SPECIAL'
  | (string & Record<never, never>)

export type GainedBadge = { badgeKey: string; family: BadgeFamily; name: string }

/** 이번 요청에서 새로 생긴 것(계약 §8). 화면은 조용한 알림으로 보인다. */
export type Gained = {
  exp: GainedExp[]
  expTotal: number
  badges: GainedBadge[]
  levelBefore: number
  levelAfter: number
}

/** `GET /learning/me/articles/{articleId}`(계약 §2). 정산하지 않는다. */
export type LearnerArticleRecord = {
  articleId: string
  state: LearnerArticleState
  percent: number
  completedAt: string | null
  answers: SavedAnswer[]
  quiz: QuizSummary
  stageProgress: StageProgress
  continueReading: ContinueReading
}

/** `POST …/completion`(계약 §4). 이미 완료한 글이면 그때의 시각과 빈 `gained`다. */
export type CompletionResult = {
  completedAt: string
  alreadyCompleted: boolean
  stageProgress: StageProgress
  continueReading: ContinueReading
  gained: Gained
}

/** `POST …/answers`(계약 §5). 서버가 판정하고 저장한 뒤의 결과다. */
export type AnswerResult = {
  questionId: string
  choiceId: string
  correct: boolean
  correctChoiceId: string
  explanation: string
  /** 이미 답한 문제였다. `choiceId`는 저장된 첫 답이다(다른 탭에서 먼저 답했을 수 있다) */
  alreadyAnswered: boolean
  quiz: QuizSummary
  gained: Gained
}

/** `POST …/retry-answers`(계약 §6). 답을 저장하지 않는다. */
export type RetryResult = Omit<AnswerResult, 'alreadyAnswered'>

/** 레벨(계약 §1). */
export type LevelView = {
  current: number
  exp: number
  currentThreshold: number
  nextThreshold: number
  remainingToNext: number
  reachedAt: string | null
  isNew: boolean
  visibleMax: number
  unreachableFrom: number
  unreachableReason: 'MORE_STAGES' | 'MORE_ARTICLES' | (string & Record<never, never>)
  milestones: { level: number; threshold: number }[]
}

export type LearnerCounts = {
  completedArticles: number
  masteredTerms: number
  badges: number
  publishedArticles: number
  publishedTerms: number
}

export type LearnerArticleEntry = {
  articleId: string
  state: LearnerArticleState
  percent: number
  completedAt: string | null
  termCount: number
  masteredTermCount: number
}

/** 단계 진행. `OPEN`은 두 수가 있고 `PREPARING`은 둘 다 `null`이다(계약 §1). */
export type LearnerStageEntry = {
  stageKey: string
  number: number
  status: 'OPEN' | 'REPREPARING' | 'PREPARING' | (string & Record<never, never>)
  completedCount: number | null
  publishedCount: number | null
  completed: boolean
}

export type MasteredTerm = {
  termId: string
  label: string
  articleId: string
  articleNumber: string | null
}

/**
 * 배지가 '준비 중'인 사유(계약 §1, data-model §12-4). 화면은 design-notes §7 문구로 바꾼다. 서버가 사유를 더해도
 * 응답 전체를 버리지 않도록 열어 둔다.
 */
export type PreparingReason =
  | 'MORE_STAGES'
  | 'MORE_ARTICLES'
  | 'ALL_STAGES'
  | 'STAGE_NOT_RELEASED'
  | 'STAGE_EMPTY'
  | (string & Record<never, never>)

export type BadgeView = {
  badgeKey: string
  family: BadgeFamily
  name: string
  criterion: string
  status: 'EARNED' | 'NOT_YET' | 'PREPARING' | (string & Record<never, never>)
  earnedAt: string | null
  isNew: boolean
  progress: { current: number; target: number } | null
  preparingReason: PreparingReason | null
  stageNumber: number | null
}

/** `GET /learning/me`(계약 §1). 요청마다 정산한다. */
export type LearnerOverview = {
  level: LevelView
  counts: LearnerCounts
  continueReading: ContinueReading
  articles: LearnerArticleEntry[]
  stages: LearnerStageEntry[]
  masteredTerms: MasteredTerm[]
  badges: BadgeView[]
  gained: Gained
}

/**
 * '이어서 읽기'로 글을 열 때 싣는 navigation state(data-model §15, FR-020). 값은 늘 `true`다. 위치는 싣지 않고,
 * 글 화면이 받은 글 기록의 `percent`로 옮긴다. 완료 카드·요약 패널·떠 있는 단추·'나의 학습' 화면이 같은 모양을 쓴다.
 */
export type LearningResumeState = { learningResume: true }

/**
 * 나의 학습 API가 내보내는 서버 오류 신호 이름이다(계약 §0·§5·§6).
 *
 * 이 union을 공용 `ServiceApiErrorCode`에 잇는 쪽은 `./api`다. `ApiClientError.is`가 그 닫힌 합집합만 받는다.
 */
export type LearningMeApiErrorCode =
  | 'LEARNING_NOT_RECORDABLE'
  | 'LEARNING_COMPLETION_REQUIRED'
  | 'LEARNING_RETRY_NOT_AVAILABLE'
  | 'LEARNING_QUESTION_NOT_FOUND'

/**
 * spec 013 배우기 공개 조회 응답의 타입.
 *
 * 원본 계약은 `specs/013-learning-pages/contracts/learning-public-api.md`이며 필드는 그것과 1:1이다
 * (013 data-model §15).
 *
 * **글 본문·용어 문장을 이 코드에 상수로 두지 않는다(013 FR-015).** 화면은 서버가 보낸 판을 그대로
 * 그린다. 글의 제목·순서·용어 구성이 바뀌어도 프런트엔드 배포가 필요 없어야 한다.
 */

/**
 * 글 하나 응답의 머리 필드이자 커리큘럼 `preReleaseArticles`의 한 항목(계약 §1·§3). 두 자리가 같은 네
 * 필드를 싣는다.
 */
export type LearningArticleSummary = {
  articleId: string
  title: string
  question: string
  /** 읽는 순서 제안. 서버가 이 순서로 보내며 화면은 다시 정렬하지 않는다 */
  readingOrder: number
}

/**
 * 단계의 공개 상태(계약 §1).
 *
 * - `OPEN`: 공개됐고 공개된 글이 한 편 이상 있다
 * - `PREPARING`: 아직 공개하지 않았다
 * - `REPREPARING`: 공개했지만 공개된 글이 0편이다. 공개 경계에서는 공개된 단계로 센다(FR-031)
 */
export type StageStatus = 'OPEN' | 'PREPARING' | 'REPREPARING'

/** `OPEN` 단계의 공개된 글 하나. 서버가 읽는 순서로 보낸다. */
export type CurriculumArticle = {
  articleId: string
  /** 단계 안 번호(예: `2-3`) */
  number: string
  title: string
  question: string
  readingMinutes: number
  /** 그 글의 공개 용어 수. 0이면 화면이 용어 수를 보이지 않는다 */
  termCount: number
  hasExample: boolean
}

/** `PREPARING` 단계에 남은 이전 판 글. 번호·상태가 없다(FR-031). */
export type PreviousEditionArticle = {
  articleId: string
  title: string
  question: string
  readingMinutes: number
}

/** 커리큘럼의 단계 하나. 공개되지 않은 단계의 새 글 제목·편수는 어느 필드에도 없다(SC-017). */
export type StageView = {
  stageKey: string
  number: number
  name: string
  levelLabel: string
  summary: string
  status: StageStatus
  /** ISO 8601 시각. 공개하지 않은 단계는 null이다 */
  releasedAt: string | null
  /** `OPEN` 단계만 채운다 */
  articles: CurriculumArticle[]
  /** `PREPARING` 단계만 채운다. 첫 단계 공개 전에는 비어 있다 */
  previousEditionArticles: PreviousEditionArticle[]
}

/** 변경 기록 한 줄의 종류. 단계 공개와 공개된 단계에 처음 게시된 글이다(R27). */
export type ReleaseLogKind = 'STAGE_RELEASED' | 'ARTICLE_ADDED'

/** 공개 안내와 변경 기록의 한 줄(계약 §1). */
export type ReleaseLogEntry = {
  /** `stage:{stageKey}` 또는 `article:{articleId}`. 닫은 안내를 이 값으로 기억한다 */
  entryKey: string
  kind: ReleaseLogKind
  stageNumber: number
  stageName: string
  /** ISO 8601 시각 */
  occurredAt: string
  newCount: number
  rewrittenCount: number
  /** 다시 쓴 글의 그때 제목 */
  rewrittenTitles: string[]
  /** `ARTICLE_ADDED`의 그때 제목. `STAGE_RELEASED`는 null이다 */
  articleTitle: string | null
}

/** `GET /learning/curriculum` 응답 본문(계약 §1). */
export type Curriculum = {
  /** 공개된 단계 수. `REPREPARING`도 센다 */
  releasedStageCount: number
  allStagesReleased: boolean
  /** 여섯 단계 전부, 번호 순서 */
  stages: StageView[]
  /** 첫 단계를 공개하기 전에만 배열이다. 공개한 뒤에는 null이다 */
  preReleaseArticles: LearningArticleSummary[] | null
  /** `changeLog` 가운데 지금부터 7일 안의 줄, 최신 순서 */
  notices: ReleaseLogEntry[]
  /** 모든 변경 기록, 최신 순서 */
  changeLog: ReleaseLogEntry[]
}

/**
 * 글의 판(계약 §3). 블록 모양이 판마다 다르다.
 *
 * - `CURRENT`: 새 기준 글(V2). 블록은 §3-2 모양이고 나의 학습에 기록한다
 * - `PRE_RELEASE`: 첫 단계 공개 전의 기존 글. 9월 판 모양 그대로 보인다
 * - `PREVIOUS`: 첫 단계 공개 뒤 공개되지 않은 단계에 남은 기존 글(이전 판). 기록하지 않는다
 */
export type Edition = 'CURRENT' | 'PRE_RELEASE' | 'PREVIOUS'

/**
 * 9월 판 본문 블록(`PRE_RELEASE`·`PREVIOUS`). `type`으로 가른다.
 *
 * `term` 블록의 `label`·`sentence`는 서버가 같은 판의 용어 설명에서 채워 보낸다. 도움말 조회가
 * 돌려주는 문장과 같은 값이다(013 SC-003).
 */
export type LegacyArticleBlock =
  | { type: 'heading'; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'term'; termId: string; label: string; sentence: string }
  /** 화면이 '가상의 계산 예' 표시를 붙인다(013 FR-005) */
  | { type: 'hypothetical'; text: string }

/** 9월 판 블록의 옛 이름. 1판 코드가 이 이름으로 쓴다. */
export type ArticleBlock = LegacyArticleBlock

/** 글자 조각(data-model §5-2). 화면은 글자 그대로 그리고 마크업으로 해석하지 않는다(FR-026). */
export type TextRun = { text: string }

/**
 * 용어 언급 조각. `owned`·`aliases`는 서버가 채운다. 다른 글이 소유한 용어의 다른 이름은 게시 용어
 * 색인에서 오고, 그 용어가 게시되어 있지 않으면 빈 배열이다(data-model §5-4).
 */
export type TermMentionRun = {
  term: string
  text: string
  owned: boolean
  aliases: string[]
}

/** 출처 표시 조각. 바로 앞 글자 옆의 '(*출처)'다(FR-007). 배열의 첫 조각으로는 오지 않는다. */
export type SourceRun = { source: { label: string; url: string } }

export type Run = TextRun | TermMentionRun | SourceRun

/** 가상 도식의 종류(data-model §5-3). */
export type DiagramKind = 'bar' | 'line'

/** 가상 도식의 계열 하나. `values` 길이는 `categories` 길이와 같다(서버 저장 검사). */
export type DiagramSeries = { name: string; values: number[] }

/** 제도 안내의 출처. 글자 조각의 출처와 같은 모양이다. */
export type NoteSource = { label: string; url: string }

/**
 * 새 기준 본문 블록(`CURRENT`, data-model §5-3).
 *
 * 9월 판과 같은 `type` 이름(`paragraph`·`hypothetical`·`term`)이라도 필드가 다르다. 그래서 응답을 읽는
 * 쪽이 `edition`을 먼저 보고 어느 합집합으로 읽을지 가른다(`api/learning.ts`).
 */
export type CurrentArticleBlock =
  | { type: 'keySummary'; lines: Run[][] }
  | { type: 'heading'; text: string }
  | { type: 'paragraph'; content: Run[] }
  | {
      type: 'term'
      termId: string
      label: string
      aliases: string[]
      meaning: string
      reading: string
      caveat: string
    }
  | { type: 'hypothetical'; content: Run[] }
  | { type: 'misconception'; wrong: Run[]; correction: Run[] }
  | { type: 'list'; ordered: boolean; items: Run[][] }
  | { type: 'table'; caption: string; columns: string[]; rows: Run[][][] }
  | {
      type: 'diagram'
      /**
       * 이 화면이 모르는 종류면 null이다. 서버가 종류를 더해도 글 전체를 실패로 두지 않고, 화면은 그림 대신
       * 설명 문장만 보인다(FR-026)
       */
      kind: DiagramKind | null
      title: string
      unit: string
      categories: string[]
      series: DiagramSeries[]
      description: string
    }
  | {
      type: 'institutionNote'
      content: Run[]
      /** `YYYY-MM-DD` */
      asOf: string | null
      source: NoteSource | null
    }

export type QuizChoice = { choiceId: string; text: string }

/**
 * 확인 문제 하나. 정답과 풀이가 공개 응답에 실려 온다(research R22). 화면은 비로그인 사용자에게 답한
 * 뒤에만, 로그인 사용자에게는 기록이 저장된 뒤에만 보인다.
 */
export type QuizQuestion = {
  questionId: string
  prompt: string
  choices: QuizChoice[]
  correctChoiceId: string
  explanation: string
}

export type LearningExampleUnavailableReason = 'SECTOR_NOT_IN_MAP' | 'SECTOR_MAP_NOT_READY'

/** 예시의 섹터 한 줄. */
export type LearningExampleSector = {
  code: string
  /** 열린 대응표의 섹터 이름. 대응표에 없는 코드는 null이다 */
  name: string | null
  /** 0.1%p 정수, 1000이 100%다. 002 계약과 같은 단위라 변환하지 않고 넘긴다 */
  weightTenths: number
}

/** 글의 예시와 조회 시점의 대응표 판정. */
export type LearningExample = {
  /** 열린 대응표에 예시 섹터가 모두 있는가 */
  available: boolean
  sectors: LearningExampleSector[]
  /** 대응표에 없는 코드. `SECTOR_MAP_NOT_READY`는 판정하지 못했으므로 빈 배열이다 */
  missingSectorCodes: string[]
  /** `available=false`일 때만 값이 있다. 화면은 이 값으로 안내 문장을 가른다 */
  unavailableReason: LearningExampleUnavailableReason | null
}

/** 글의 출처(9월 판 글 끝 목록). `url`은 없을 수 있고, 있으면 `http`·`https`만 온다. */
export type LearningSource = {
  label: string
  url: string | null
}

/** 글이 놓인 공개 단계. */
export type ArticleStage = {
  stageKey: string
  number: number
  name: string
}

/** 읽는 순서상 다음 공개된 글. */
export type ArticleNext = {
  articleId: string
  number: string | null
  title: string
}

type LearningArticleCommon = LearningArticleSummary & {
  stage: ArticleStage
  /** 단계 안 번호(예: `2-4`). 공개된 단계의 글에만 있다 */
  number: string | null
  readingMinutes: number
  sources: LearningSource[]
  next: ArticleNext | null
  /** 그 단계의 공개된 글 수. 이전 판·첫 단계 공개 전 글은 null이다 */
  stageArticleCount: number | null
  /** '다음 단계는 준비 중입니다'를 덧붙일지 */
  hasUnreleasedStages: boolean
  /** ISO 8601 시각 */
  publishedAt: string
}

/** 9월 판 모양에 필드를 더한 글(`PRE_RELEASE`·`PREVIOUS`, 계약 §3-1). */
export type LegacyLearningArticle = LearningArticleCommon & {
  edition: 'PRE_RELEASE' | 'PREVIOUS'
  blocks: LegacyArticleBlock[]
  /** 기록하지 않는 글이라 늘 null이다 */
  minDwellSeconds: null
  /** 이전 판·첫 단계 공개 전 글에는 확인 문제가 없다 */
  quiz: QuizQuestion[]
  example: LearningExample
}

/** 새 기준 글(`CURRENT`, 계약 §3-2). */
export type CurrentLearningArticle = LearningArticleCommon & {
  edition: 'CURRENT'
  blocks: CurrentArticleBlock[]
  /** 로그인 사용자의 완료 판정에 쓰는 최소 머문 시간(R20) */
  minDwellSeconds: number
  quiz: QuizQuestion[]
  /** 예시가 없는 글은 null이고 '이 예시로 전략 진단하기'가 없다(FR-003) */
  example: LearningExample | null
}

/** `GET /learning/articles/{articleId}` 응답 본문. 공개 중인 판의 내용이다. `edition`으로 가른다. */
export type LearningArticle = LegacyLearningArticle | CurrentLearningArticle

/**
 * 게시 시각을 뺀 글이다. 관리 미리 보기(관리 계약 §8)가 같은 모양에 게시 전이라 `publishedAt`이 `null`인 응답을
 * 주므로, 해석은 공개 글과 같은 함수로 하고 게시 시각만 따로 읽는다(`api/learning.ts`의 `readArticleContent`).
 *
 * 미리 보기는 예시 없는 V1 초안을 9월 판 모양에 `example: null`로 보낸다. 그래서 이 타입의 9월 판 예시는 비어 있을
 * 수 있다. 공개 글(`LearningArticle`)의 9월 판에는 늘 예시가 있다.
 */
export type LearningArticleContent =
  | (Omit<LegacyLearningArticle, 'publishedAt' | 'example'> & { example: LearningExample | null })
  | Omit<CurrentLearningArticle, 'publishedAt'>

/** `GET /learning/terms/{termId}` 응답 본문. 설명 문장은 화면이 짓거나 줄이지 않고 그대로 보인다. */
export type LearningTermEntry = {
  termId: string
  label: string
  sentence: string
  /** 도움말의 '해당 주제 글로 이동' 대상 */
  articleId: string
  articleTitle: string
}

/**
 * 도움말에서 글로 갔다가 돌아올 자리. navigation state `learningReturn`에 싣는다
 * (013 term-help-ui §3, research R11).
 *
 * `label`은 호환을 위해 그대로 싣지만 받는 쪽은 쓰지 않는다. 돌아가기 문구는
 * `components/learning/returnTargets.ts`의 표가 정한다(research R35).
 */
export type LearningReturnState = {
  path: string
  label: string
}

/**
 * 배우기 공개 조회가 내보내는 서버 오류 신호 이름이다.
 *
 * 두 404를 한 이름으로 합치지 않는다. 글은 '지금은 볼 수 없는 글', 용어는 '설명 준비 중'으로
 * 화면 문장이 다르다(013 research R10). 이 union을 공용 `ServiceApiErrorCode`에 잇는 쪽은 `./api`다.
 */
export type LearningApiErrorCode = 'LEARNING_ARTICLE_UNAVAILABLE' | 'LEARNING_TERM_NOT_READY'

import type {
  CurrentLearningArticle,
  Curriculum,
  LearningArticleSummary,
  LegacyLearningArticle,
  ReleaseLogEntry,
  StageView,
} from '@/types/learning'
import type {
  AdminLearningArticle,
  AdminLearningArticleSummary,
  PublishCheckFailure,
  StageAdminView,
  StageReadiness,
} from '@/types/learningAdmin'

/**
 * 013 배우기 화면 테스트용 응답 픽스처.
 *
 * <p><b>data-model §8에 없는 `articleId`·`termId`를 일부러 쓴다.</b> 실제 글 식별자로 만들면 화면이 그
 * 글을 알고 있어도 테스트가 통과한다 — 이름을 지어내야 "응답만 보고 그린다"가 증명된다(013 FR-015).
 * `src/test/strategyRegistryFixtures.ts`가 003에서 같은 이유로 같은 방식을 쓴다.
 *
 * <p>아래 문장·숫자·섹터 코드는 계약 형식을 보이려는 합성 값이다. 원고가 아니다.
 */

export const tideSummary: LearningArticleSummary = {
  articleId: 'tide-reading',
  title: '물때 읽기',
  question: '물이 빠지는 속도는 왜 들어오는 속도와 다른가?',
  readingOrder: 2,
}

export const harborSummary: LearningArticleSummary = {
  articleId: 'harbor-notes',
  title: '항구 기록',
  question: '같은 배가 다른 항구에서 다르게 보이는 이유는?',
  readingOrder: 5,
}

/** 서버가 준 순서 그대로다. 화면이 다시 정렬하는지 보려고 `readingOrder`와 반대로 둔다. */
export const articleSummaries: LearningArticleSummary[] = [harborSummary, tideSummary]

/**
 * 9월 판 네 블록 종류를 모두 갖고 예시로 바로 진단을 시작할 수 있는 글.
 *
 * <p>첫 단계 공개 전 글(`PRE_RELEASE`)이다. 9월 판 블록이므로 2판 필드는 기록하지 않는 글의 값이다.
 */
export const tideArticle: LegacyLearningArticle = {
  articleId: 'tide-reading',
  edition: 'PRE_RELEASE',
  title: '물때 읽기',
  question: '물이 빠지는 속도는 왜 들어오는 속도와 다른가?',
  readingOrder: 2,
  stage: { stageKey: 'harbor-basics', number: 1, name: '항구 기초' },
  number: null,
  readingMinutes: 1,
  minDwellSeconds: null,
  quiz: [],
  next: null,
  stageArticleCount: null,
  hasUnreleasedStages: true,
  blocks: [
    { type: 'heading', text: '물이 가장 낮았던 때' },
    { type: 'paragraph', text: '합성 문단이다. 화면이 받은 문장을 그대로 그리는지 본다.' },
    {
      type: 'term',
      termId: 'ebb-gap',
      label: '썰물 간격',
      sentence: '합성 용어 문장이다. 줄이거나 바꾸지 않고 보여야 한다.',
    },
    { type: 'hypothetical', text: '가상의 항구에서 물이 3칸 들어왔다가 1칸 빠졌다고 해 보자.' },
  ],
  example: {
    available: true,
    sectors: [
      { code: 'ALPHA_DOCK', name: '알파 부두', weightTenths: 625 },
      { code: 'BETA_DOCK', name: '베타 부두', weightTenths: 375 },
    ],
    missingSectorCodes: [],
    unavailableReason: null,
  },
  sources: [
    { label: '합성 출처 하나', url: 'https://example.com/tide' },
    { label: '주소 없는 합성 출처', url: null },
  ],
  publishedAt: '2026-09-20T01:00:00Z',
}

/** 예시 섹터 하나가 지금 대응표에 없어 진단으로 넘길 수 없는 글. */
export const harborArticle: LegacyLearningArticle = {
  articleId: 'harbor-notes',
  edition: 'PRE_RELEASE',
  title: '항구 기록',
  question: '같은 배가 다른 항구에서 다르게 보이는 이유는?',
  readingOrder: 5,
  stage: { stageKey: 'harbor-basics', number: 1, name: '항구 기초' },
  number: null,
  readingMinutes: 1,
  minDwellSeconds: null,
  quiz: [],
  next: null,
  stageArticleCount: null,
  hasUnreleasedStages: true,
  blocks: [{ type: 'paragraph', text: '합성 문단이다.' }],
  example: {
    available: false,
    sectors: [
      { code: 'ALPHA_DOCK', name: '알파 부두', weightTenths: 500 },
      { code: 'GAMMA_DOCK', name: null, weightTenths: 500 },
    ],
    missingSectorCodes: ['GAMMA_DOCK'],
    unavailableReason: 'SECTOR_NOT_IN_MAP',
  },
  sources: [],
  publishedAt: '2026-09-21T01:00:00Z',
}

/** 첫 단계 공개 뒤 공개되지 않은 단계에 남은 같은 글(이전 판). 블록 모양은 9월 판 그대로다. */
export const previousTideArticle: LegacyLearningArticle = {
  ...tideArticle,
  edition: 'PREVIOUS',
  stage: { stageKey: 'tide-tables', number: 3, name: '물때표 읽기' },
}

/**
 * 새 기준 블록 열 종류를 모두 갖고 예시가 없는 글(`CURRENT`).
 *
 * <p>처음 나오는 자리를 보려고 용어를 두 번씩 쓴다. 이 글이 소유한 `swell-height`(다른 이름 있음)는
 * 용어 블록보다 핵심 요약에 먼저 나오고, 다른 글이 소유한 `ebb-gap`(다른 이름 있음)은 문단과 가상의 계산
 * 예에, 게시되지 않은 다른 글의 `moor-knot`(다른 이름 없음)는 문단과 오해 상자에 나온다.
 */
export const currentArticle: CurrentLearningArticle = {
  articleId: 'swell-reading',
  edition: 'CURRENT',
  title: '너울 읽기',
  question: '높은 너울은 배가 가라앉는다는 뜻일까?',
  readingOrder: 203,
  stage: { stageKey: 'swell-and-risk', number: 2, name: '너울과 위험' },
  number: '2-3',
  readingMinutes: 4,
  minDwellSeconds: 80,
  blocks: [
    {
      type: 'keySummary',
      lines: [
        [
          { term: 'swell-height', text: '너울 높이', owned: true, aliases: ['파고', 'swell'] },
          { text: '는 합성 요약 첫 줄이다.' },
        ],
        [{ text: '합성 요약 둘째 줄이다.' }],
        [{ text: '합성 요약 셋째 줄이다.' }],
      ],
    },
    { type: 'heading', text: '너울은 무엇을 재나' },
    {
      type: 'paragraph',
      content: [
        { text: '합성 문단이다. ' },
        { term: 'ebb-gap', text: '썰물 간격', owned: false, aliases: ['물 빠짐 폭', 'ebb'] },
        { text: '과 ' },
        { term: 'moor-knot', text: '계류 매듭', owned: false, aliases: [] },
        { text: '은 다르다.' },
        { source: { label: '합성 항만 자료', url: 'https://example.com/harbor' } },
      ],
    },
    {
      type: 'term',
      termId: 'swell-height',
      label: '너울 높이',
      aliases: ['파고', 'swell'],
      meaning: '합성 뜻 문장이다.',
      reading: '합성 읽는 법 문장이다.',
      caveat: '합성 한계 문장이다.',
    },
    {
      type: 'hypothetical',
      content: [
        { text: '가상의 항구에서 ' },
        { term: 'ebb-gap', text: '썰물 간격', owned: false, aliases: ['물 빠짐 폭', 'ebb'] },
        { text: '이 3칸이었다고 해 보자.' },
      ],
    },
    {
      type: 'misconception',
      wrong: [{ text: '너울이 높으면 배가 가라앉는다.' }],
      correction: [
        { term: 'moor-knot', text: '계류 매듭', owned: false, aliases: [] },
        { text: '과 함께 따로 본다.' },
      ],
    },
    {
      type: 'list',
      ordered: true,
      items: [
        [{ text: '합성 목록 첫 항목' }],
        [
          { text: '합성 목록 둘째 항목' },
          { source: { label: '합성 목록 자료', url: 'https://example.com/list' } },
        ],
      ],
    },
    {
      type: 'table',
      caption: '가상의 두 항구',
      columns: ['구분', '가 항구', '나 항구'],
      rows: [
        [[{ text: '너울' }], [{ text: '높다' }], [{ text: '낮다' }]],
        [[{ text: '물때' }], [{ text: '같다' }], [{ text: '같다' }]],
      ],
    },
    {
      type: 'diagram',
      kind: 'line',
      title: '가상의 너울 흐름',
      unit: 'm',
      categories: ['1일', '2일', '3일'],
      series: [
        { name: '가 항구', values: [1, 2.5, 1.5] },
        { name: '나 항구', values: [1, 1.2, 1.5] },
      ],
      description: '가 항구는 크게 오르내리고 나 항구는 거의 그대로지만 둘 다 1.5에서 끝난다.',
    },
    {
      type: 'institutionNote',
      content: [{ text: '합성 제도 안내 문장이다.' }],
      asOf: '2026-07-01',
      source: { label: '합성 제도 자료', url: 'https://example.com/rule' },
    },
  ],
  quiz: [
    {
      questionId: 'q1',
      prompt: '합성 질문 하나다.',
      choices: [
        { choiceId: 'c1', text: '합성 보기 가' },
        { choiceId: 'c2', text: '합성 보기 나' },
      ],
      correctChoiceId: 'c2',
      explanation: '합성 풀이 하나다.',
    },
    {
      questionId: 'q3',
      prompt: '합성 질문 둘이다.',
      choices: [
        { choiceId: 'c4', text: '합성 보기 다' },
        { choiceId: 'c5', text: '합성 보기 라' },
        { choiceId: 'c6', text: '합성 보기 마' },
      ],
      correctChoiceId: 'c4',
      explanation: '합성 풀이 둘이다.',
    },
  ],
  example: null,
  sources: [],
  next: { articleId: 'tide-reading', number: '2-4', title: '물때 읽기' },
  stageArticleCount: 4,
  hasUnreleasedStages: true,
  publishedAt: '2026-11-19T01:00:00Z',
}

/** 단계 여섯의 공통 필드. 이름·설명은 합성 값이고 data-model §13의 단계가 아니다. */
const stageBase = [
  { stageKey: 'harbor-basics', number: 1, name: '항구 기초', levelLabel: '입문', summary: '합성 단계 설명 하나다.' },
  { stageKey: 'swell-and-risk', number: 2, name: '너울과 위험', levelLabel: '입문', summary: '합성 단계 설명 둘이다.' },
  { stageKey: 'tide-tables', number: 3, name: '물때표 읽기', levelLabel: '입문~초급', summary: '합성 단계 설명 셋이다.' },
  { stageKey: 'chart-and-cost', number: 4, name: '해도와 비용', levelLabel: '초급', summary: '합성 단계 설명 넷이다.' },
  { stageKey: 'reef-checks', number: 5, name: '암초 점검', levelLabel: '초급', summary: '합성 단계 설명 다섯이다.' },
  { stageKey: 'weather-and-seas', number: 6, name: '날씨와 바다', levelLabel: '초급', summary: '합성 단계 설명 여섯이다.' },
] as const

function preparingStage(index: number, previous: StageView['previousEditionArticles'] = []): StageView {
  return { ...stageBase[index], status: 'PREPARING', releasedAt: null, articles: [], previousEditionArticles: previous }
}

/** 2단계 공개 줄. 새 글 3편과 다시 쓴 글 1편이다. */
export const stageTwoReleased: ReleaseLogEntry = {
  entryKey: 'stage:swell-and-risk',
  kind: 'STAGE_RELEASED',
  stageNumber: 2,
  stageName: '너울과 위험',
  occurredAt: '2026-11-19T01:00:00Z',
  newCount: 3,
  rewrittenCount: 1,
  rewrittenTitles: ['물때 읽기'],
  articleTitle: null,
}

/** 1단계에 글 하나를 더한 줄. */
export const stageOneArticleAdded: ReleaseLogEntry = {
  entryKey: 'article:dock-fees',
  kind: 'ARTICLE_ADDED',
  stageNumber: 1,
  stageName: '항구 기초',
  occurredAt: '2026-11-15T01:00:00Z',
  newCount: 1,
  rewrittenCount: 0,
  rewrittenTitles: [],
  articleTitle: '부두 이용료는 어디로 갈까?',
}

/** 1단계 공개 줄. 7일이 지나 변경 기록에만 있다. */
export const stageOneReleased: ReleaseLogEntry = {
  entryKey: 'stage:harbor-basics',
  kind: 'STAGE_RELEASED',
  stageNumber: 1,
  stageName: '항구 기초',
  occurredAt: '2026-11-05T01:00:00Z',
  newCount: 1,
  rewrittenCount: 1,
  rewrittenTitles: ['항구 기록'],
  articleTitle: null,
}

/**
 * 1·2단계를 공개하고 3~6단계가 준비 중인 커리큘럼(계약 §1). 3·4단계에는 이전 판 글이 남아 있고, 1단계 둘째 글은
 * 용어가 없다. 공개 안내는 7일 안의 두 줄이다.
 */
export const releasedCurriculum: Curriculum = {
  releasedStageCount: 2,
  allStagesReleased: false,
  stages: [
    {
      ...stageBase[0],
      status: 'OPEN',
      releasedAt: '2026-11-05T01:00:00Z',
      articles: [
        { articleId: 'harbor-notes', number: '1-1', title: '항구 기록', question: '같은 배가 다른 항구에서 다르게 보이는 이유는?', readingMinutes: 5, termCount: 2, hasExample: true },
        { articleId: 'dock-fees', number: '1-2', title: '부두 이용료는 어디로 갈까?', question: '이용료는 언제 빠져나갈까?', readingMinutes: 4, termCount: 0, hasExample: false },
      ],
      previousEditionArticles: [],
    },
    {
      ...stageBase[1],
      status: 'OPEN',
      releasedAt: '2026-11-19T01:00:00Z',
      articles: [
        { articleId: 'tide-reading', number: '2-1', title: '물때 읽기', question: '물이 빠지는 속도는 왜 들어오는 속도와 다른가?', readingMinutes: 6, termCount: 1, hasExample: false },
        { articleId: 'swell-reading', number: '2-2', title: '너울 읽기', question: '너울은 왜 바람이 멎은 뒤에도 남을까?', readingMinutes: 4, termCount: 2, hasExample: true },
      ],
      previousEditionArticles: [],
    },
    preparingStage(2, [
      { articleId: 'anchor-lines', title: '닻줄 길이', question: '닻줄은 왜 물 깊이보다 길게 내릴까?', readingMinutes: 2 },
      { articleId: 'buoy-colors', title: '부표 색', question: '부표의 색은 무엇을 알려 줄까?', readingMinutes: 1 },
    ]),
    preparingStage(3, [{ articleId: 'chart-symbols', title: '해도 기호', question: '해도의 작은 기호는 어떻게 읽을까?', readingMinutes: 2 }]),
    preparingStage(4),
    preparingStage(5),
  ],
  preReleaseArticles: null,
  notices: [stageTwoReleased, stageOneArticleAdded],
  changeLog: [stageTwoReleased, stageOneArticleAdded, stageOneReleased],
}

/** 첫 단계를 공개하기 전 커리큘럼. 9월 판 목록과 같은 글·순서다(계약 §1). */
export const preReleaseCurriculum: Curriculum = {
  releasedStageCount: 0,
  allStagesReleased: false,
  stages: stageBase.map((_, index) => preparingStage(index)),
  preReleaseArticles: articleSummaries,
  notices: [],
  changeLog: [],
}

/**
 * 관리 화면(B05) 픽스처. 공개 조회와 같은 합성 식별자를 쓴다.
 *
 * <p>목록은 세 가지 표시를 한 벌에 담는다. 게시하지 않은 변경이 있는 글, 대응표를 확인할 수 없어
 * 판정하지 못한 글(`needsUpdate: null`), 아직 아무 기록이 없는 초안이다. 셋이 다른 사실이라
 * 화면이 하나로 접으면 이 픽스처에서 드러난다.
 */
export const adminArticleSummaries: AdminLearningArticleSummary[] = [
  {
    articleId: 'tide-reading',
    title: '물때 읽기',
    readingOrder: 2,
    status: 'PUBLISHED',
    draftVersion: 5,
    hasUnpublishedChanges: true,
    needsUpdate: false,
    lastPublication: {
      action: 'PUBLISH',
      revisionNo: 3,
      actorUserId: 7,
      occurredAt: '2026-09-20T01:00:00Z',
    },
    format: 'V1',
    stage: { stageKey: 'shore-basics', number: 1, released: false },
    stagePosition: 2,
    publishedEdition: 'LEGACY',
    needsReview: null,
    pendingTransfers: 0,
  },
  {
    articleId: 'harbor-notes',
    title: '항구 기록',
    readingOrder: 5,
    status: 'PUBLISHED',
    draftVersion: 2,
    hasUnpublishedChanges: false,
    needsUpdate: null,
    lastPublication: {
      action: 'UNPUBLISH',
      revisionNo: 1,
      actorUserId: 7,
      occurredAt: '2026-09-21T01:00:00Z',
    },
    format: 'V1',
    stage: { stageKey: 'shore-basics', number: 1, released: false },
    stagePosition: 5,
    publishedEdition: 'LEGACY',
    needsReview: true,
    pendingTransfers: 0,
  },
  {
    articleId: 'moor-lines',
    title: '계류줄 묶기',
    readingOrder: 9,
    status: 'DRAFT',
    draftVersion: 0,
    hasUnpublishedChanges: false,
    needsUpdate: true,
    lastPublication: null,
    format: 'V2',
    stage: { stageKey: 'open-water', number: 2, released: false },
    stagePosition: 1,
    publishedEdition: null,
    needsReview: false,
    pendingTransfers: 1,
  },
]

/** 네 블록 종류를 모두 가진 초안. `term` 블록은 `termId`만 담는다(계약 §4). */
export const adminTideArticle: AdminLearningArticle = {
  articleId: 'tide-reading',
  status: 'PUBLISHED',
  draftVersion: 5,
  draft: {
    title: '물때 읽기',
    question: '물이 빠지는 속도는 왜 들어오는 속도와 다른가?',
    readingOrder: 2,
    blocks: [
      { type: 'heading', text: '물이 가장 낮았던 때' },
      { type: 'paragraph', text: '합성 문단이다.' },
      { type: 'term', termId: 'ebb-gap' },
      { type: 'hypothetical', text: '가상의 항구에서 물이 3칸 들어왔다가 1칸 빠졌다고 해 보자.' },
    ],
    example: {
      sectors: [
        { code: 'ALPHA_DOCK', weightTenths: 625 },
        { code: 'BETA_DOCK', weightTenths: 375 },
      ],
    },
    sources: [{ label: '합성 출처 하나', url: 'https://example.com/tide' }],
    terms: [{ termId: 'ebb-gap', label: '썰물 간격', sentence: '합성 용어 문장이다.' }],
    format: 'V1',
    stageKey: 'shore-basics',
    stagePosition: 2,
    quiz: [],
  },
  publishedRevisionNo: 3,
  publishedEdition: 'LEGACY',
  hasLegacyRevision: true,
}

/** 아직 한 번도 게시하지 않은 V2 초안. 게시 취소 버튼이 없어야 하는 쪽이다. */
export const adminDraftArticle: AdminLearningArticle = {
  articleId: 'moor-lines',
  status: 'DRAFT',
  draftVersion: 0,
  draft: {
    title: '계류줄 묶기',
    question: '같은 매듭이 왜 배마다 다르게 풀리는가?',
    readingOrder: 201,
    blocks: [],
    example: null,
    sources: [],
    terms: [],
    format: 'V2',
    stageKey: 'open-water',
    stagePosition: 1,
    quiz: [],
  },
  publishedRevisionNo: null,
  publishedEdition: null,
  hasLegacyRevision: false,
}

/**
 * 공개된 단계의 V2 초안(2판). 글자 조각 세 종류, 넘겨받는 중인 용어, 식별자를 받은 확인 문제 둘을 담는다.
 * 편집기가 조각을 편집 문법 문자열로 바꾸고 저장할 때 되돌리는지, 게시가 바꾼 식별자를 받는지 이 한 벌로 본다.
 */
export const adminV2Article: AdminLearningArticle = {
  articleId: 'channel-depth',
  status: 'PUBLISHED',
  draftVersion: 4,
  draft: {
    title: '물길 깊이 재기',
    question: '같은 물길인데 배마다 지나갈 수 있는 때가 다른 까닭은?',
    readingOrder: 102,
    format: 'V2',
    stageKey: 'shore-basics',
    stagePosition: 3,
    blocks: [
      {
        type: 'keySummary',
        lines: [[{ text: '첫 줄' }], [{ text: '둘째 줄' }], [{ text: '셋째 줄' }]],
      },
      {
        type: 'paragraph',
        content: [
          { text: '합성 문단의 ' },
          { term: 'ebb-gap', text: '썰물 간격' },
          { text: '과 {{괄호' },
          { source: { label: '합성 출처', url: 'https://example.com/depth' } },
        ],
      },
      { type: 'term', termId: 'keel-room' },
    ],
    example: null,
    sources: [],
    terms: [
      {
        termId: 'keel-room',
        label: '용골 여유',
        aliases: ['keel clearance'],
        meaning: '합성 뜻 문장이다.',
        reading: '합성 읽는 법 문장이다.',
        caveat: '합성 한계 문장이다.',
        transferFrom: 'harbor-notes',
      },
    ],
    quiz: [
      {
        questionId: 'q1',
        prompt: '합성 질문 하나',
        choices: [
          { choiceId: 'c2', text: '틀린 보기', correct: false },
          { choiceId: 'c3', text: '정답 보기', correct: true },
        ],
        explanation: '합성 풀이 하나',
      },
      {
        questionId: 'q4',
        prompt: '합성 질문 둘',
        choices: [
          { choiceId: 'c5', text: '정답 보기', correct: true },
          { choiceId: 'c6', text: '틀린 보기', correct: false },
        ],
        explanation: '합성 풀이 둘',
      },
    ],
  },
  publishedRevisionNo: 2,
  publishedEdition: 'CURRENT',
  hasLegacyRevision: false,
}

/** 단계 둘(2판). 1단계는 공개할 수 있고 2단계는 앞 단계를 기다린다. 2단계에는 공개 배치만 남은 글이 있다. */
export const adminStages: StageAdminView[] = [
  {
    stageKey: 'shore-basics',
    number: 1,
    name: '물가 기초',
    levelLabel: '입문',
    summary: '합성 단계 요약이다.',
    releasedAt: null,
    releasedByUserId: null,
    releasable: true,
    articles: [
      {
        articleId: 'tide-reading',
        title: '물때 읽기',
        format: 'V2',
        status: 'PUBLISHED',
        publishedEdition: 'LEGACY',
        draftVersion: 5,
        stagePosition: 2,
      },
      {
        articleId: 'channel-depth',
        title: '물길 깊이 재기',
        format: 'V2',
        status: 'DRAFT',
        publishedEdition: null,
        draftVersion: 4,
        stagePosition: 3,
      },
    ],
    placementOnlyArticles: [],
  },
  {
    stageKey: 'open-water',
    number: 2,
    name: '먼바다',
    levelLabel: '기초',
    summary: '합성 단계 요약이다.',
    releasedAt: null,
    releasedByUserId: null,
    releasable: false,
    articles: [],
    placementOnlyArticles: [
      {
        articleId: 'harbor-notes',
        title: '항구 기록',
        format: 'V1',
        status: 'PUBLISHED',
        publishedEdition: 'LEGACY',
        draftVersion: 2,
        stagePosition: 5,
      },
    ],
  },
]

/** 1단계 미리 검사가 통과한 결과. 단계 공개 요청은 이 목록과 초안 번호를 그대로 싣는다. */
export const adminReadyReadiness: StageReadiness = {
  stageKey: 'shore-basics',
  ready: true,
  blockers: [],
  articles: [
    { articleId: 'tide-reading', draftVersion: 5, failures: [] },
    { articleId: 'channel-depth', draftVersion: 4, failures: [] },
  ],
  checkVersion: 'learning-publish-checks-v2',
}

/**
 * 게시 전 검사에 걸린 항목 셋.
 *
 * <p>위치 세 종류(`example`·`block`·`term`)를 한 벌에 담는다. 화면이 위치 문장을 짓는 자리가 셋이라
 * 하나만 담으면 나머지 둘이 검사되지 않는다. `matched`는 오는 항목과 오지 않는 항목이 섞여 있다.
 */
export const publishCheckFailures: PublishCheckFailure[] = [
  {
    check: 'EXAMPLE_WEIGHT_SUM',
    location: { part: 'example' },
    message: '예시 비중의 합계가 100%가 아닙니다. 지금 합계는 95.0%입니다.',
  },
  {
    check: 'FORBIDDEN_EXPRESSION',
    location: { part: 'block', index: 2 },
    matched: '추천 비중',
    message: '추천으로 읽히는 표현이 있습니다.',
  },
  {
    check: 'RESULT_NUMBER_OUTSIDE_HYPOTHETICAL',
    location: { part: 'term', termId: 'max-drawdown' },
    matched: '-30%',
    message: '실제 결과로 읽히는 숫자는 가상의 계산 예 블록에만 쓸 수 있습니다.',
  },
]

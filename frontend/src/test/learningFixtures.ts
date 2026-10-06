import type {
  CurrentLearningArticle,
  LearningArticleSummary,
  LegacyLearningArticle,
} from '@/types/learning'
import type {
  AdminLearningArticle,
  AdminLearningArticleSummary,
  PublishCheckFailure,
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
    terms: [
      { termId: 'ebb-gap', label: '썰물 간격', sentence: '합성 용어 문장이다.' },
    ],
  },
  publishedRevisionNo: 3,
}

/** 아직 한 번도 게시하지 않은 초안. 게시 취소 버튼이 없어야 하는 쪽이다. */
export const adminDraftArticle: AdminLearningArticle = {
  articleId: 'moor-lines',
  status: 'DRAFT',
  draftVersion: 0,
  draft: {
    title: '계류줄 묶기',
    question: '같은 매듭이 왜 배마다 다르게 풀리는가?',
    readingOrder: 9,
    blocks: [],
    example: null,
    sources: [],
    terms: [],
  },
  publishedRevisionNo: null,
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

import type {
  LearningArticle,
  LearningArticleSummary,
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

/** 네 블록 종류를 모두 갖고 예시로 바로 진단을 시작할 수 있는 글. */
export const tideArticle: LearningArticle = {
  articleId: 'tide-reading',
  title: '물때 읽기',
  question: '물이 빠지는 속도는 왜 들어오는 속도와 다른가?',
  readingOrder: 2,
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
export const harborArticle: LearningArticle = {
  articleId: 'harbor-notes',
  title: '항구 기록',
  question: '같은 배가 다른 항구에서 다르게 보이는 이유는?',
  readingOrder: 5,
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

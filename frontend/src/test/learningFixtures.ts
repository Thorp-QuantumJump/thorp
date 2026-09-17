import type {
  LearningArticle,
  LearningArticleSummary,
  LearningTermEntry,
} from '@/types/learning'

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

export const ebbGapTerm: LearningTermEntry = {
  termId: 'ebb-gap',
  label: '썰물 간격',
  sentence: '합성 용어 문장이다. 줄이거나 바꾸지 않고 보여야 한다.',
  articleId: 'tide-reading',
  articleTitle: '물때 읽기',
}

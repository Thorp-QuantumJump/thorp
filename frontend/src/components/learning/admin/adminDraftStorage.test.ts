import { beforeEach, describe, expect, it } from 'vitest'
import { adminV2Article } from '@/test/learningFixtures'
import {
  adminDraftKey,
  applyPublishToUnsentDraft,
  readUnsentDraft,
  saveUnsentDraft,
} from './adminDraftStorage'
import { toEditableDraft } from './editableDraft'

/**
 * 탭 보관본(013 research R12, data-model §15).
 *
 * - 읽기는 지우지 않는다. 이전 내용 버전 보관본도 그 사실만 돌려준다. 지우는 것은 편집 화면이 알린 뒤다
 * - 모양이 다른 보관본은 고쳐 쓰지 않고 없는 것으로 다룬다
 * - 게시 결과 반영은 바뀐 것이 없으면 보관본을 건드리지 않는다
 */

const KEY = adminDraftKey('channel-depth')
const OUTDATED = JSON.stringify({
  version: 1,
  savedAt: '2026-09-30T01:00:00Z',
  draft: { title: '옛 보관본' },
  draftVersion: 3,
})

beforeEach(() => {
  window.sessionStorage.clear()
})

describe('readUnsentDraft', () => {
  it('보관한 편집 내용과 그 기반 번호를 돌려준다', () => {
    const draft = { ...toEditableDraft(adminV2Article.draft), title: '보관한 제목' }
    saveUnsentDraft('channel-depth', draft, 4)

    const lookup = readUnsentDraft('channel-depth')
    expect(lookup.status).toBe('found')
    if (lookup.status !== 'found') return
    expect(lookup.unsent.draft).toEqual(draft)
    expect(lookup.unsent.draftVersion).toBe(4)
  })

  it('이전 내용 버전 보관본은 outdated로 알리고 지우지 않는다', () => {
    window.sessionStorage.setItem(KEY, OUTDATED)

    expect(readUnsentDraft('channel-depth')).toEqual({ status: 'outdated' })
    expect(window.sessionStorage.getItem(KEY)).toBe(OUTDATED)
  })

  it('키가 빠진 보관본은 빈 값으로 채우지 않고 없는 것으로 다룬다', () => {
    const withoutQuiz: Record<string, unknown> = { ...toEditableDraft(adminV2Article.draft) }
    delete withoutQuiz.quiz
    window.sessionStorage.setItem(
      KEY,
      JSON.stringify({
        version: 2,
        savedAt: '2026-10-07T01:00:00Z',
        draft: withoutQuiz,
        draftVersion: 4,
      }),
    )

    expect(readUnsentDraft('channel-depth')).toEqual({ status: 'none' })
  })
})

describe('applyPublishToUnsentDraft', () => {
  it('이전 내용 버전 보관본은 게시 결과를 반영하면서 지우지 않는다', () => {
    window.sessionStorage.setItem(KEY, OUTDATED)

    applyPublishToUnsentDraft('channel-depth', [{ from: 'q4', to: 'q7' }], [])

    expect(window.sessionStorage.getItem(KEY)).toBe(OUTDATED)
  })

  it('바뀐 것이 없으면 보관본을 다시 쓰지 않는다', () => {
    window.sessionStorage.setItem(KEY, OUTDATED)

    applyPublishToUnsentDraft('channel-depth', [], [])

    expect(window.sessionStorage.getItem(KEY)).toBe(OUTDATED)
  })
})

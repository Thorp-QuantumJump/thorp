import { describe, expect, it } from 'vitest'
import {
  releasedCurriculum,
  stageOneArticleAdded,
  stageOneReleased,
  stageTwoReleased,
} from '@/test/learningFixtures'
import type { ReleaseLogEntry, StageView } from '@/types/learning'
import {
  RECORDS_KEPT_SENTENCE,
  boundarySentence,
  changeLogText,
  kstDate,
  noticeLine,
  objectParticle,
  stageNumberFromHash,
  stageRange,
} from './curriculumCopy'

/** 013 T139·T141. 공개 경계·공개 안내·변경 기록 문장(design-notes §7, FR-031, quickstart 8-4·11-2). */

function stages(statuses: StageView['status'][]): StageView[] {
  return statuses.map((status, index) => ({ ...releasedCurriculum.stages[index], status }))
}

describe('stageRange', () => {
  it('하나는 n단계, 둘은 가운뎃점, 셋 이상 이어지면 물결표다', () => {
    expect(stageRange([1])).toBe('1단계')
    expect(stageRange([2, 1])).toBe('1·2단계')
    expect(stageRange([3, 4, 5, 6])).toBe('3~6단계')
  })
})

describe('boundarySentence', () => {
  it('공개된 단계와 준비 중인 단계를 한 줄로 쓴다', () => {
    expect(boundarySentence(releasedCurriculum.stages)).toBe(
      '지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.',
    )
  })

  it('글이 0편인 공개 단계(REPREPARING)도 공개된 단계로 센다', () => {
    expect(boundarySentence(stages(['OPEN', 'REPREPARING', 'OPEN', 'PREPARING', 'PREPARING', 'PREPARING']))).toBe(
      '지금 공개된 단계는 1~3단계입니다. 4~6단계는 준비 중입니다.',
    )
  })

  it('여섯 단계를 모두 공개했으면 경계가 없다', () => {
    expect(boundarySentence(stages(['OPEN', 'OPEN', 'OPEN', 'OPEN', 'OPEN', 'REPREPARING']))).toBeNull()
  })
})

describe('objectParticle', () => {
  it('받침이 있으면 을, 없으면 를이다', () => {
    expect(objectParticle('수익과 위험 읽기')).toBe('를')
    expect(objectParticle('너울과 위험')).toBe('을')
    expect(objectParticle('함정과 검증')).toBe('을')
  })
})

describe('noticeLine', () => {
  it('일어난 순서대로 한 줄로 합치고 가장 최근 줄의 단계를 가리킨다', () => {
    const line = noticeLine([stageTwoReleased, stageOneArticleAdded], false)!

    expect(line.text).toBe(
      "1단계에 새 글 1편을 더했습니다. 2단계 '너울과 위험'을 공개했습니다. 새 글 3편과 다시 쓴 글 1편입니다.",
    )
    expect(line.stageNumber).toBe(2)
    expect(line.entryKeys).toEqual(['stage:swell-and-risk', 'article:dock-fees'])
  })

  it('같은 단계에 더한 글은 편수를 합쳐 한 문장이다', () => {
    const second: ReleaseLogEntry = { ...stageOneArticleAdded, entryKey: 'article:pier-rules', occurredAt: '2026-11-16T01:00:00Z' }

    expect(noticeLine([second, stageOneArticleAdded], false)!.text).toBe('1단계에 새 글 2편을 더했습니다.')
  })

  it('로그인 사용자에게는 1단계가 아닌 단계 공개가 있을 때만 기록 문장을 덧붙인다', () => {
    expect(noticeLine([stageTwoReleased], true)!.text).toContain(RECORDS_KEPT_SENTENCE)
    expect(noticeLine([stageTwoReleased], false)!.text).not.toContain(RECORDS_KEPT_SENTENCE)
    expect(noticeLine([stageOneReleased], true)!.text).not.toContain(RECORDS_KEPT_SENTENCE)
    expect(noticeLine([{ ...stageOneArticleAdded, stageNumber: 2 }], true)!.text).not.toContain(
      RECORDS_KEPT_SENTENCE,
    )
  })

  it('새 글이나 다시 쓴 글이 0편이면 그 부분을 빼고, 안내 줄이 없으면 null이다', () => {
    expect(noticeLine([{ ...stageTwoReleased, rewrittenCount: 0, rewrittenTitles: [] }], false)!.text).toBe(
      "2단계 '너울과 위험'을 공개했습니다. 새 글 3편입니다.",
    )
    expect(noticeLine([{ ...stageTwoReleased, newCount: 0 }], false)!.text).toBe(
      "2단계 '너울과 위험'을 공개했습니다. 다시 쓴 글 1편입니다.",
    )
    expect(noticeLine([], true)).toBeNull()
  })
})

describe('changeLogText', () => {
  it('단계 공개는 편수와 다시 쓴 글의 그때 제목, 글 추가는 그 글의 제목이다', () => {
    expect(changeLogText(stageTwoReleased)).toBe("2단계 공개 · 새 글 3편, 다시 쓴 글 1편('물때 읽기')")
    expect(changeLogText(stageOneArticleAdded)).toBe("1단계에 글 추가 · 새 글 1편('부두 이용료는 어디로 갈까?')")
    expect(changeLogText({ ...stageTwoReleased, rewrittenCount: 2, rewrittenTitles: ['가', '나'] })).toBe(
      "2단계 공개 · 새 글 3편, 다시 쓴 글 2편('가', '나')",
    )
  })
})

describe('kstDate', () => {
  it('한국 날짜로 쓴다', () => {
    expect(kstDate('2026-11-18T16:00:00Z')).toBe('2026-11-19')
    expect(kstDate('2026-11-18T14:59:59Z')).toBe('2026-11-18')
  })
})

describe('stageNumberFromHash', () => {
  it('#stage-n 모양만 읽는다', () => {
    expect(stageNumberFromHash('#stage-3')).toBe(3)
    expect(stageNumberFromHash('#stage-')).toBeNull()
    expect(stageNumberFromHash('#learning-change-log')).toBeNull()
    expect(stageNumberFromHash('')).toBeNull()
  })
})

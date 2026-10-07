import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { adminV2Article } from '@/test/learningFixtures'
import type { EditableDraft, PublishedEdition } from '@/types/learningAdmin'
import { toEditableDraft, toServerDraft } from './editableDraft'
import { knownQuizIds, readManuscript, writeManuscript } from './manuscript'
import { ManuscriptTransfer } from './ManuscriptTransfer'

/**
 * 013 T174. 원고 가져오기·내보내기(research R26, quickstart 7-1).
 *
 * - 내보낸 원고를 다시 가져오면 같다. 글자 조각이 편집 문법으로 갔다가 같은 조각으로 돌아온다
 * - 지금 초안에 없는 문제·보기 식별자는 `null`이 된다. 저장 API가 지어 낸 식별자를 거절하기 때문이다
 * - 틀린 원고는 위치와 함께 거절하고 편집 상태를 바꾸지 않는다
 */

const ARTICLE_ID = adminV2Article.articleId
const editable = toEditableDraft(adminV2Article.draft)
const known = knownQuizIds(adminV2Article.draft.quiz)

function manuscriptFile(content: string, name = `${ARTICLE_ID}.json`): File {
  return new File([content], name, { type: 'application/json' })
}

function renderTransfer(dirty = false, publishedEdition: PublishedEdition | null = null) {
  const onImport = vi.fn<(draft: EditableDraft) => void>()
  render(
    <ManuscriptTransfer
      articleId={ARTICLE_ID}
      draft={editable}
      known={known}
      publishedEdition={publishedEdition}
      dirty={dirty}
      onImport={onImport}
    />,
  )
  return onImport
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('원고 왕복', () => {
  it('내보낸 원고를 가져오면 편집 상태와 같고, 저장하면 서버 초안과 같은 조각이 된다', () => {
    const text = writeManuscript(ARTICLE_ID, editable)
    expect(text).toContain('{{용어:ebb-gap|썰물 간격}}')
    expect(text).toContain('{{출처:합성 출처|https://example.com/depth}}')
    // 글자 `{{`는 원고 JSON 안에서 `\\{{`다.
    expect(text).toContain('과 \\\\{{괄호')

    const result = readManuscript(text, ARTICLE_ID, known)
    expect(result).toEqual({
      ok: true,
      draft: editable,
      ids: { newQuestions: 0, newChoices: 0, replaced: 0 },
    })
    if (!result.ok) return
    expect(toServerDraft(result.draft)).toEqual({ ok: true, draft: adminV2Article.draft })
  })

  it('지금 초안에 없는 문제·보기 식별자는 null로 바꾸고 있는 식별자는 둔다', () => {
    const file = JSON.parse(writeManuscript(ARTICLE_ID, editable))
    file.quiz[0].questionId = 'q99'
    file.quiz[0].choices[0].choiceId = 'c98'

    const result = readManuscript(JSON.stringify(file), ARTICLE_ID, known)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.draft.quiz[0].questionId).toBeNull()
    expect(result.draft.quiz[0].choices.map((choice) => choice.choiceId)).toEqual([null, 'c3'])
    expect(result.draft.quiz[1].questionId).toBe('q4')
  })

  it('모르는 키는 위치와 함께 거절한다. 오타 난 키의 내용을 버리지 않는다', () => {
    const file = JSON.parse(writeManuscript(ARTICLE_ID, editable))
    file.qiuz = file.quiz

    expect(readManuscript(JSON.stringify(file), ARTICLE_ID, known)).toEqual({
      ok: false,
      issues: [{ where: '원고', message: '모르는 키가 있습니다: qiuz' }],
    })
  })

  it('블록 모양이 틀리면 그 블록의 위치를 알린다', () => {
    const file = JSON.parse(writeManuscript(ARTICLE_ID, editable))
    file.blocks[1].content = [{ text: '조각 배열은 원고 모양이 아니다' }]

    expect(readManuscript(JSON.stringify(file), ARTICLE_ID, known)).toEqual({
      ok: false,
      issues: [{ where: 'blocks[1].content', message: '문자열이어야 합니다.' }],
    })
  })
})

describe('ManuscriptTransfer', () => {
  it('문법이 틀린 원고는 줄·열과 함께 거절하고 편집 상태를 바꾸지 않는다', async () => {
    const file = JSON.parse(writeManuscript(ARTICLE_ID, editable))
    file.blocks[1].content = '첫 줄\n둘째 {{용어:x|닫지 않음'
    const onImport = renderTransfer()
    const user = userEvent.setup()

    await user.upload(screen.getByLabelText('원고 가져오기'), manuscriptFile(JSON.stringify(file)))

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(/가져오지 않았습니다\. 편집 내용은 그대로입니다/)).not.toBeNull()
    expect(within(alert).getByText(/본문 2번째 블록\(문단\) · 내용 2줄 4칸/)).not.toBeNull()
    expect(onImport).not.toHaveBeenCalled()
  })

  it('다른 글의 원고는 거절한다', async () => {
    const onImport = renderTransfer()
    const user = userEvent.setup()

    await user.upload(
      screen.getByLabelText('원고 가져오기'),
      manuscriptFile(writeManuscript('harbor-notes', editable)),
    )

    expect(await screen.findByText(/다른 글\(harbor-notes\)의 원고입니다/)).not.toBeNull()
    expect(onImport).not.toHaveBeenCalled()
  })

  it('JSON이 아니면 거절한다', async () => {
    const onImport = renderTransfer()
    const user = userEvent.setup()

    await user.upload(screen.getByLabelText('원고 가져오기'), manuscriptFile('{ 깨진 원고'))

    expect(await screen.findByText(/JSON으로 읽지 못했습니다/)).not.toBeNull()
    expect(onImport).not.toHaveBeenCalled()
  })

  it('맞는 원고를 가져오면 편집 상태를 넘기고 저장해야 반영된다고 알린다', async () => {
    const onImport = renderTransfer()
    const user = userEvent.setup()

    await user.upload(
      screen.getByLabelText('원고 가져오기'),
      manuscriptFile(writeManuscript(ARTICLE_ID, editable)),
    )

    expect(await screen.findByText(/저장해야 초안에 반영됩니다/)).not.toBeNull()
    expect(onImport).toHaveBeenCalledWith(editable)
  })

  it('저장하지 않은 편집이 있으면 묻고, 거절하면 가져오지 않는다', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const onImport = renderTransfer(true)
    const user = userEvent.setup()

    await user.upload(
      screen.getByLabelText('원고 가져오기'),
      manuscriptFile(writeManuscript(ARTICLE_ID, editable)),
    )

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(onImport).not.toHaveBeenCalled()
  })

  it('(converge) 새 식별자를 받을 문제·보기 수를 알리고, 새 기준 판으로 공개 중이면 답한 사용자에게 새 문제로 보인다고 경고한다', async () => {
    const file = JSON.parse(writeManuscript(ARTICLE_ID, editable))
    // 식별자를 비운 원고(저장소 원고를 내보내기로 되받지 않은 경우)를 공개된 글에 다시 넣는다.
    file.quiz[1].questionId = null
    file.quiz[1].choices[0].choiceId = 'c98'
    renderTransfer(false, 'CURRENT')
    const user = userEvent.setup()

    await user.upload(screen.getByLabelText('원고 가져오기'), manuscriptFile(JSON.stringify(file)))

    const status = await screen.findByRole('status')
    expect(status.textContent).toContain(
      '새 문제 1개·새 보기 1개는 저장할 때 새 식별자를 받습니다.',
    )
    expect(status.textContent).toContain(
      '원고에 적힌 식별자 1개는 지금 초안에 없어 새 식별자로 바뀝니다.',
    )
    expect(status.textContent).toContain('이미 답한 사용자에게도 답하지 않은 새')
  })

  it('(converge) 식별자가 모두 이어지면 새 식별자 안내를 하지 않는다', async () => {
    renderTransfer(false, 'CURRENT')
    const user = userEvent.setup()

    await user.upload(
      screen.getByLabelText('원고 가져오기'),
      manuscriptFile(writeManuscript(ARTICLE_ID, editable)),
    )

    const status = await screen.findByRole('status')
    expect(status.textContent).not.toContain('새 식별자')
  })
})

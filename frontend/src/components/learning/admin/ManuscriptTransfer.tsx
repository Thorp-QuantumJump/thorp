import { useId, useState, type ChangeEvent } from 'react'
import type { PublishedEdition } from '@/types/learningAdmin'
import type { EditableDraft } from './editableDraft'
import {
  readManuscript,
  writeManuscript,
  type KnownQuizIds,
  type ManuscriptIdSummary,
  type ManuscriptIssue,
} from './manuscript'
import { describePosition } from './richTextSyntax'
import styles from './AdminEditor.module.css'

/**
 * 원고 가져오기·내보내기(013 FR-016, research R25·R26, T171).
 *
 * <p><b>원고는 저장소 MR로 확인받은 뒤 가져온다(FR-016).</b> 가져오기는 편집 상태만 바꾸고, 서버 초안은 운영자가
 * 저장을 눌러야 바뀐다. 원고가 틀리면 위치와 함께 거절하고 편집 상태를 그대로 둔다. 저장하지 않은 편집이 있으면
 * 원고로 덮기 전에 묻는다.
 *
 * <p><b>새 식별자를 받을 문제·보기를 알린다.</b> 지금 초안에 없는 문제·보기 식별자는 `null`이 되어 저장할 때 새
 * 식별자를 받는다. 새 기준 판으로 공개 중인 글이면 그 문제는 이미 답한 사용자에게도 답하지 않은 새 문제로 보이므로
 * (data-model §6-3) 그 사실을 함께 알린다. 말없이 새 문제가 되면 사용자의 첫 답 기록이 화면에서 사라진다.
 *
 * <p><b>내보내기는 지금 편집 상태를 그대로 쓴다.</b> 저장한 뒤 내보내면 서버가 준 식별자가 채워진 원고가 되고,
 * 그것을 저장소 원고로 둔다(`docs/learning-content/README.md`). 저장 전 편집을 내보내면 서버와 다른 원고가 된다.
 */

interface ManuscriptTransferProps {
  articleId: string
  draft: EditableDraft
  /** 지금 서버 초안의 문제·보기 식별자. 원고의 다른 식별자는 새 문제·보기가 된다 */
  known: KnownQuizIds
  /** 공개 중인 판의 종류. 새 기준 판(`CURRENT`)이면 새 문제가 답한 사용자에게 어떻게 보이는지 알린다 */
  publishedEdition: PublishedEdition | null
  /** 저장하지 않은 편집이 있는가. 있으면 가져오기 전에 묻는다 */
  dirty: boolean
  onImport: (draft: EditableDraft) => void
}

type ImportState =
  | { kind: 'idle' }
  | { kind: 'imported'; fileName: string; ids: ManuscriptIdSummary }
  | { kind: 'rejected'; fileName: string; issues: ManuscriptIssue[] }
  | { kind: 'exportFailed' }

function describeIssue(issue: ManuscriptIssue): string {
  const position =
    issue.line === undefined || issue.column === undefined
      ? ''
      : ` ${describePosition({ line: issue.line, column: issue.column })}`
  return `${issue.where}${position}: ${issue.message}`
}

/** 브라우저가 파일로 받게 한다. 내려받기를 지원하지 않는 환경이면 `false`를 돌려주고, 화면이 그 사실을 알린다. */
function download(fileName: string, content: string): boolean {
  if (typeof URL.createObjectURL !== 'function') return false
  const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.click()
  URL.revokeObjectURL(url)
  return true
}

export function ManuscriptTransfer({
  articleId,
  draft,
  known,
  publishedEdition,
  dirty,
  onImport,
}: ManuscriptTransferProps) {
  const inputId = useId()
  const [state, setState] = useState<ImportState>({ kind: 'idle' })

  const importFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.currentTarget
    const file = input.files?.[0]
    if (!file) return
    const source = await file.text()
    // 같은 파일을 고쳐 다시 고를 수 있게 비운다.
    input.value = ''

    const result = readManuscript(source, articleId, known)
    if (!result.ok) {
      setState({ kind: 'rejected', fileName: file.name, issues: result.issues })
      return
    }
    if (dirty && !window.confirm('저장하지 않은 편집 내용이 원고로 바뀝니다. 원고를 가져올까요?')) {
      setState({ kind: 'idle' })
      return
    }
    onImport(result.draft)
    setState({ kind: 'imported', fileName: file.name, ids: result.ids })
  }

  return (
    <section className={styles.editor} aria-labelledby="manuscript-title">
      <h3 id="manuscript-title">원고</h3>
      <p className={styles.guide}>
        원고는 이 편집기의 내용을 그대로 쓴 JSON입니다. 저장소 MR로 확인받은 원고를 가져와
        저장합니다. 저장한 뒤에는 서버가 준 문제·보기 식별자가 채워진 원고를 내보내 저장소 원고를
        바꿉니다. 형식과 순서는 docs/learning-content/README.md에 있습니다.
      </p>
      <div className={styles.row}>
        <label className={styles.field} htmlFor={inputId}>
          <span>원고 가져오기</span>
          <input
            id={inputId}
            type="file"
            accept="application/json,.json"
            onChange={(event) => void importFile(event)}
          />
        </label>
        <button
          type="button"
          onClick={() =>
            setState(
              download(`${articleId}.json`, writeManuscript(articleId, draft))
                ? { kind: 'idle' }
                : { kind: 'exportFailed' },
            )
          }
        >
          원고 내보내기
        </button>
      </div>

      {state.kind === 'imported' ? (
        <div role="status">
          <p className={styles.notice}>
            {state.fileName}을 가져왔습니다. 저장해야 초안에 반영됩니다.
          </p>
          <ImportedIds ids={state.ids} publishedEdition={publishedEdition} />
        </div>
      ) : null}
      {state.kind === 'exportFailed' ? (
        <p role="alert" className={styles.notice}>
          이 브라우저에서는 원고를 파일로 내려받을 수 없습니다. 다른 브라우저에서 내보내 주세요.
        </p>
      ) : null}
      {state.kind === 'rejected' ? (
        <div role="alert">
          <p className={styles.notice}>
            {state.fileName}을 가져오지 않았습니다. 편집 내용은 그대로입니다.
          </p>
          <ul className={styles.issues}>
            {state.issues.map((issue, index) => (
              <li key={index}>{describeIssue(issue)}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  )
}

function ImportedIds({
  ids,
  publishedEdition,
}: {
  ids: ManuscriptIdSummary
  publishedEdition: PublishedEdition | null
}) {
  if (ids.newQuestions === 0 && ids.newChoices === 0) return null
  return (
    <>
      <p className={styles.hint}>
        새 문제 {ids.newQuestions}개·새 보기 {ids.newChoices}개는 저장할 때 새 식별자를 받습니다.
        {ids.replaced > 0
          ? ` 원고에 적힌 식별자 ${ids.replaced}개는 지금 초안에 없어 새 식별자로 바뀝니다.`
          : ''}
      </p>
      {publishedEdition === 'CURRENT' && ids.newQuestions > 0 ? (
        <p className={styles.notice}>
          이 글은 새 기준 판으로 공개 중이라, 새 식별자를 받는 문제는 이미 답한 사용자에게도 답하지
          않은 새 문제로 보입니다. 고치려던 문제라면 저장하지 말고, 이 글에서 내보낸 원고(식별자가
          채워진 것)를 고쳐 가져오세요.
        </p>
      ) : null}
    </>
  )
}

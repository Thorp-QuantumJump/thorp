import { useId, useState, type ChangeEvent } from 'react'
import type { EditableDraft } from '@/types/learningAdmin'
import {
  readManuscript,
  writeManuscript,
  type KnownQuizIds,
  type ManuscriptIssue,
} from './manuscript'
import styles from './AdminEditor.module.css'

/**
 * 원고 가져오기·내보내기(013 research R25·R26, T171).
 *
 * <p><b>가져오기는 편집 상태만 바꾼다.</b> 서버 초안은 운영자가 저장을 눌러야 바뀐다. 원고가 틀리면 위치와 함께
 * 거절하고 편집 상태를 그대로 둔다. 저장하지 않은 편집이 있으면 원고로 덮기 전에 묻는다.
 *
 * <p><b>내보내기는 지금 편집 상태를 그대로 쓴다.</b> 저장소의 `docs/learning-content/{current|next}/{articleId}.json`
 * 자리에 두고 MR로 검토한다(R25). 저장 전 편집을 내보내면 서버와 다른 원고가 되므로, 저장한 뒤 내보내라고
 * 안내한다.
 */

interface ManuscriptTransferProps {
  articleId: string
  draft: EditableDraft
  /** 지금 서버 초안의 문제·보기 식별자. 원고의 다른 식별자는 새 문제·보기가 된다 */
  known: KnownQuizIds
  /** 저장하지 않은 편집이 있는가. 있으면 가져오기 전에 묻는다 */
  dirty: boolean
  onImport: (draft: EditableDraft) => void
}

type ImportState =
  | { kind: 'idle' }
  | { kind: 'imported'; fileName: string }
  | { kind: 'rejected'; fileName: string; issues: ManuscriptIssue[] }

function describeIssue(issue: ManuscriptIssue): string {
  const position = issue.line === undefined ? '' : ` ${issue.line}줄 ${issue.column}칸`
  return `${issue.where}${position}: ${issue.message}`
}

/** 브라우저가 파일로 받게 한다. 내려받기를 지원하지 않는 환경이면 아무것도 하지 않는다. */
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
      setState({
        kind: 'rejected',
        fileName: file.name,
        issues: result.issues,
      })
      return
    }
    if (dirty && !window.confirm('저장하지 않은 편집 내용이 원고로 바뀝니다. 원고를 가져올까요?')) {
      setState({ kind: 'idle' })
      return
    }
    onImport(result.draft)
    setState({ kind: 'imported', fileName: file.name })
  }

  return (
    <section className={styles.editor} aria-labelledby="manuscript-title">
      <h3 id="manuscript-title">원고</h3>
      <p className={styles.guide}>
        원고는 이 편집기의 내용을 그대로 쓴 JSON입니다. 가져온 원고는 저장해야 초안에 들어갑니다.
        저장소에 둘 원고는 저장한 뒤 내보내세요. 형식은 docs/learning-content/README.md에 있습니다.
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
          onClick={() => download(`${articleId}.json`, writeManuscript(articleId, draft))}
        >
          원고 내보내기
        </button>
      </div>

      {state.kind === 'imported' ? (
        <p role="status" className={styles.notice}>
          {state.fileName}을 가져왔습니다. 저장해야 초안에 반영됩니다.
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

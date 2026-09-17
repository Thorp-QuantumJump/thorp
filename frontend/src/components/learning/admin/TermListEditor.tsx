import { useState } from 'react'
import type { AdminDraftTerm } from '@/types/learningAdmin'
import styles from './TermListEditor.module.css'

/**
 * 이 글이 소유한 용어 설명을 편집한다(013 FR-008, T064).
 *
 * <p><b>저장된 행의 `termId`는 읽기 전용이다.</b> 식별자는 만들 때 정하고 이후 바꾸지 않는다
 * (research R4). 002·003·005 화면 코드가 이 값을 적어 두므로, 바꾸면 그쪽 도움말이 조용히 끊긴다.
 *
 * <p><b>저장 요청은 목록을 통째로 보낸다.</b> 여기서 지운 행은 초안에서 빠지고, 다음 게시 때
 * 공개에서도 빠진다(계약 §5). 그 사실을 한 줄로 알린다.
 */

interface TermListEditorProps {
  terms: AdminDraftTerm[]
  /** 서버에 이미 저장된 식별자. 이 목록에 있는 행은 `termId`를 바꿀 수 없다 */
  savedTermIds: readonly string[]
  onChange: (terms: AdminDraftTerm[]) => void
}

const LABEL_MAX_LENGTH = 50
const SENTENCE_MAX_LENGTH = 300

export function TermListEditor({ terms, savedTermIds, onChange }: TermListEditorProps) {
  const [newTermId, setNewTermId] = useState('')

  const updateAt = (index: number, patch: Partial<AdminDraftTerm>) => {
    onChange(terms.map((term, position) => (position === index ? { ...term, ...patch } : term)))
  }

  const addTerm = () => {
    const termId = newTermId.trim()
    if (termId === '') return
    onChange([...terms, { termId, label: '', sentence: '' }])
    setNewTermId('')
  }

  return (
    <section className={styles.editor} aria-labelledby="term-editor-title">
      <h3 id="term-editor-title">용어 설명</h3>
      <p className={styles.guide}>
        여기서 지운 용어는 다음 게시 때 공개에서도 빠집니다. 식별자는 만든 뒤 바꿀 수 없습니다.
      </p>

      <ul className={styles.list}>
        {terms.map((term, index) => {
          const isSaved = savedTermIds.includes(term.termId)
          return (
            <li key={term.termId || index} className={styles.row}>
              <label className={styles.field}>
                <span>식별자</span>
                <input
                  type="text"
                  value={term.termId}
                  readOnly={isSaved}
                  aria-readonly={isSaved}
                  onChange={(event) => updateAt(index, { termId: event.target.value })}
                />
              </label>

              <label className={styles.field}>
                <span>
                  이름 (최대 {LABEL_MAX_LENGTH}자, 지금 {term.label.length}자)
                </span>
                <input
                  type="text"
                  value={term.label}
                  onChange={(event) => updateAt(index, { label: event.target.value })}
                />
              </label>

              <label className={styles.field}>
                <span>
                  설명 문장 (최대 {SENTENCE_MAX_LENGTH}자, 지금 {term.sentence.length}자)
                </span>
                <textarea
                  rows={3}
                  value={term.sentence}
                  onChange={(event) => updateAt(index, { sentence: event.target.value })}
                />
              </label>

              <button
                type="button"
                onClick={() => onChange(terms.filter((_, position) => position !== index))}
                aria-label={`용어 ${term.termId} 삭제`}
              >
                삭제
              </button>
            </li>
          )
        })}
      </ul>

      {terms.length === 0 ? <p className={styles.empty}>용어가 없습니다.</p> : null}

      <div className={styles.add}>
        <label className={styles.field}>
          <span>새 용어 식별자</span>
          <input
            type="text"
            value={newTermId}
            onChange={(event) => setNewTermId(event.target.value)}
          />
        </label>
        <button type="button" onClick={addTerm} disabled={newTermId.trim() === ''}>
          용어 추가
        </button>
      </div>
    </section>
  )
}

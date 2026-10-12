import type { AdminDraftSource } from '@/types/learningAdmin'
import type { EditableDraft } from './editableDraft'
import styles from './AdminEditPanels.module.css'

/**
 * 글 끝 출처 목록을 편집한다(013 data-model §4).
 *
 * <p>V1 초안만 출처 목록을 쓴다. V2 초안은 출처를 글자 옆 출처 표시로 두며(계약 §5), 목록이 남아 있으면 지울
 * 수만 있게 보인다. 남은 목록을 숨기면 저장이 왜 400인지 운영자가 찾지 못한다. V2 초안에 목록이 없으면 그리지
 * 않는다.
 */

interface SourceListEditorProps {
  draft: EditableDraft
  onChange: (draft: EditableDraft) => void
}

export function SourceListEditor({ draft, onChange }: SourceListEditorProps) {
  if (draft.format === 'V2' && draft.sources.length === 0) return null

  const setSources = (sources: AdminDraftSource[]) => onChange({ ...draft, sources })
  const patchAt = (index: number, patch: Partial<AdminDraftSource>) =>
    setSources(
      draft.sources.map((item, position) => (position === index ? { ...item, ...patch } : item)),
    )

  return (
    <section className={styles.sources} aria-labelledby="admin-edit-sources-title">
      <h3 id="admin-edit-sources-title">출처</h3>
      {draft.format === 'V2' ? (
        <p className={styles.detail}>
          V2 글에는 글 끝 출처 목록을 둘 수 없습니다. 글자 옆 출처 표시로 옮긴 뒤 지워 주세요.
        </p>
      ) : null}
      <ul className={styles.sourceList}>
        {draft.sources.map((source, index) => (
          // 출처는 라벨이 겹칠 수 있어 순번으로 자리를 잡는다.
          <li key={index} className={styles.sourceRow}>
            <label className={styles.field}>
              <span>이름</span>
              <input
                type="text"
                value={source.label}
                onChange={(event) => patchAt(index, { label: event.target.value })}
              />
            </label>
            <label className={styles.field}>
              <span>주소</span>
              <input
                type="url"
                value={source.url ?? ''}
                onChange={(event) =>
                  patchAt(index, { url: event.target.value === '' ? null : event.target.value })
                }
              />
            </label>
            <button
              type="button"
              onClick={() => setSources(draft.sources.filter((_, position) => position !== index))}
              aria-label={`${index + 1}번째 출처 삭제`}
            >
              삭제
            </button>
          </li>
        ))}
      </ul>
      {draft.format === 'V1' ? (
        <button
          type="button"
          onClick={() => setSources([...draft.sources, { label: '', url: null }])}
        >
          출처 추가
        </button>
      ) : null}
    </section>
  )
}

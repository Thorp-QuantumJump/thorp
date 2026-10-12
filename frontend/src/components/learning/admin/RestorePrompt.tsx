import type { UnsentDraft } from './adminDraftStorage'
import styles from './AdminEditPanels.module.css'

/**
 * 이 탭에 보관한 편집 내용을 복원할지 묻는다(013 research R12).
 *
 * <p>묻지 않고 덮으면 서버 값이나 보관본 가운데 하나가 소리 없이 사라진다. 보관한 뒤 서버 초안이 바뀌었으면 그
 * 사실을 먼저 알린다. 복원해 저장하면 409가 오므로, 그때 보관본은 새 초안에 옮길 내용을 확인하는 데 쓴다.
 */

interface RestorePromptProps {
  unsent: UnsentDraft
  /** 서버가 지금 알려 준 초안 번호 */
  draftVersion: number
  onRestore: (unsent: UnsentDraft) => void
  onDiscard: () => void
}

export function RestorePrompt({ unsent, draftVersion, onRestore, onDiscard }: RestorePromptProps) {
  return (
    <section className={styles.restore} aria-labelledby="admin-edit-restore-title">
      <h2 id="admin-edit-restore-title">저장하지 못한 편집 내용이 있습니다</h2>
      <p>{new Date(unsent.savedAt).toLocaleString('ko-KR')}에 보관한 내용입니다.</p>
      {unsent.draftVersion === draftVersion ? null : (
        <p role="alert">
          보관한 뒤 다른 곳에서 초안이 바뀌었습니다(보관본 {unsent.draftVersion}번, 지금{' '}
          {draftVersion}번). 복원해 저장하면 초안이 바뀌었다는 안내를 받습니다. 새 초안에 옮길
          내용을 확인하는 데 쓰세요.
        </p>
      )}
      <p>복원할지 정하기 전에는 편집할 수 없습니다.</p>
      <div className={styles.actions}>
        <button type="button" onClick={() => onRestore(unsent)}>
          복원하기
        </button>
        <button type="button" onClick={onDiscard}>
          버리기
        </button>
      </div>
    </section>
  )
}

import { useId, useState } from 'react'
import { normalizeApiError } from '@/api/client'
import { deleteMyRecord } from '@/api/myRecords'
import { Button } from '@/components/ui/button'
import { RecordDialog } from './RecordDialog'
import styles from './records.module.css'

type Props = {
  open: boolean
  recordId: string
  recordName: string
  onClose: () => void
  /** 지운 뒤. 목록이 그 줄을 없앤다 */
  onDeleted: () => void
}

/**
 * 삭제 확인(spec 006 FR-017, research R6, T048).
 *
 * <p><b>무엇이 남고 무엇이 사라지는지 말한다.</b> "내 목록에서만 사라진다"를 적지 않으면 사용자는 계산 결과가 지워진다고
 * 읽고, 그 오해는 되돌릴 수 없는 조작 앞에서 가장 나쁘다.
 *
 * <p><b>되돌리기를 제안하지 않는다.</b> 복구 경로를 만들지 않기로 했으므로(data-model §5) "취소할 수 있습니다" 같은 문구도
 * 두지 않는다 — 없는 길을 암시하는 문장이 곧 거짓이다.
 */
export function DeleteConfirmDialog({
  open,
  recordId,
  recordName,
  onClose,
  onDeleted,
}: Props) {
  const titleId = useId()
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  async function confirm() {
    setDeleting(true)
    setError(null)
    try {
      await deleteMyRecord(recordId)
      onDeleted()
      onClose()
    } catch (cause) {
      setError(normalizeApiError(cause).message)
    } finally {
      setDeleting(false)
    }
  }

  return (
    <RecordDialog open={open} onClose={onClose} labelledBy={titleId}>
      <div className={styles.dialogBody}>
        <h2 id={titleId}>기록을 지울까요?</h2>
        <p>
          <strong>{recordName}</strong> 을(를) 내 목록에서 지웁니다.
        </p>
        <p className={styles.dialogNote}>저장된 계산 결과는 남고 내 목록에서만 사라집니다.</p>
        {error ? (
          <p role="alert" className={styles.dialogError}>
            {error}
          </p>
        ) : null}
        <div className={styles.dialogActions}>
          <Button type="button" variant="outline" onClick={onClose}>
            취소
          </Button>
          <Button type="button" onClick={confirm} disabled={deleting}>
            {deleting ? '지우는 중' : '지우기'}
          </Button>
        </div>
      </div>
    </RecordDialog>
  )
}

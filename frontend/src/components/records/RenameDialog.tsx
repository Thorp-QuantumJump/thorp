import { type FormEvent, useEffect, useId, useState } from 'react'
import { renameMyRecord } from '@/api/myRecords'
import { Button } from '@/components/ui/button'
import { normalizeApiError } from '@/api/client'
import type { MyRecordDetail } from '@/types/myRecords'
import { RecordDialog } from './RecordDialog'
import styles from './records.module.css'

type Props = {
  open: boolean
  recordId: string
  currentName: string
  onClose: () => void
  /** 성공했을 때 갱신된 상세. 목록이 그 줄만 제자리에서 고친다 */
  onRenamed: (detail: MyRecordDetail) => void
}

/**
 * 이름 변경(spec 006 FR-017, T048).
 *
 * <p><b>이름만 바뀐다</b>는 사실을 문구로 말한다 — 저장된 계산 결과·참조 버전은 그대로다. 사용자가 "다시 계산되나"를 걱정하지
 * 않게 하는 한 문장이다.
 *
 * <p>빈 이름과 길이 초과는 서버가 400으로 거절한다. 그 문장을 그대로 필드 오류로 보인다 — 화면이 같은 규칙을 한 벌 더 적으면
 * 서버가 규칙을 바꿀 때 둘이 갈린다.
 */
export function RenameDialog({ open, recordId, currentName, onClose, onRenamed }: Props) {
  const titleId = useId()
  const inputId = useId()
  const errorId = useId()
  const [name, setName] = useState(currentName)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  // 다시 열 때마다 지금 이름에서 시작한다. 앞서 고치다 만 값이 남아 있으면 무엇을 저장하는지 흐려진다.
  useEffect(() => {
    if (open) {
      setName(currentName)
      setError(null)
    }
  }, [open, currentName])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      onRenamed(await renameMyRecord(recordId, name))
      onClose()
    } catch (cause) {
      setError(normalizeApiError(cause).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <RecordDialog open={open} onClose={onClose} labelledBy={titleId}>
      <form className={styles.dialogBody} onSubmit={submit}>
        <h2 id={titleId}>이름 바꾸기</h2>
        <p className={styles.dialogNote}>
          이름만 바뀝니다. 저장된 계산 결과와 참조 버전은 그대로입니다.
        </p>
        <label htmlFor={inputId}>새 이름</label>
        <input
          id={inputId}
          value={name}
          onChange={(event) => setName(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          autoFocus
        />
        {error ? (
          <p id={errorId} role="alert" className={styles.dialogError}>
            {error}
          </p>
        ) : null}
        <div className={styles.dialogActions}>
          <Button type="button" variant="outline" className={styles.action} onClick={onClose}>
            취소
          </Button>
          <Button type="submit" className={styles.action} disabled={saving}>
            {saving ? '저장 중' : '저장'}
          </Button>
        </div>
      </form>
    </RecordDialog>
  )
}

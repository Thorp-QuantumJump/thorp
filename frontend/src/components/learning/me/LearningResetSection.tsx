import { CircleCheck } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { resetLearning } from '@/api/learningMe'
import { Button } from '@/components/ui/button'
import styles from './LearningResetSection.module.css'

/**
 * 회원정보 화면의 '학습 기록' 구역(013 FR-025, research R36). '나의 학습' 화면에는 두지 않는다.
 *
 * <p><b>확인 창에서 확인할 때만 지운다.</b> '학습 초기화'를 누르면 지워지는 것(완료·읽은 위치·첫 답·익힘·경험치·배지)과
 * 되살릴 수 없다는 안내를 보이고, 확인하면 `DELETE /learning/me` 하나를 보낸다(CSRF 토큰을 싣는다). 되돌리기를 제안하지
 * 않는다. 없는 길을 암시하는 문장이 곧 거짓이기 때문이다. 글마다 지우는 수단은 없다.
 *
 * <p><b>개발용 미리보기(`preview`)는 서버를 부르지 않는다.</b> 닉네임 저장과 같이 확인해도 지우지 않았다는 안내만 보인다.
 */
export function LearningResetSection({ preview = false }: { preview?: boolean }) {
  const titleId = useId()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const openerRef = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (open && !dialog.open) {
      // showModal이라야 초점 가두기·Esc 닫기·배경 비활성을 브라우저가 해 준다.
      if (typeof dialog.showModal === 'function') dialog.showModal()
      else dialog.setAttribute('open', '')
    }
    if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close()
      else dialog.removeAttribute('open')
    }
  }, [open])

  const close = () => {
    if (pending) return
    setOpen(false)
    setError('')
    openerRef.current?.focus()
  }

  const confirm = async () => {
    if (pending) return
    if (preview) {
      setOpen(false)
      setNotice('미리보기 화면이라 학습 기록을 지우지 않았습니다.')
      openerRef.current?.focus()
      return
    }
    setPending(true)
    setError('')
    const result = await resetLearning()
    setPending(false)
    if (result.status === 'ok') {
      setOpen(false)
      setNotice('학습 기록을 모두 지웠습니다.')
      openerRef.current?.focus()
      return
    }
    setError(
      result.status === 'unauthenticated'
        ? '로그인이 만료되어 지우지 못했습니다. 다시 로그인한 뒤 시도해 주세요.'
        : '학습 기록을 지우지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }

  return (
    <div className={styles.reset}>
      <p className={styles.lead}>
        배우기의 나의 학습 기록을 모두 지웁니다. 글마다 따로 지울 수는 없습니다.
      </p>
      <div className={styles.actions}>
        <Button
          ref={openerRef}
          type="button"
          variant="outline"
          onClick={() => {
            setNotice('')
            setOpen(true)
          }}
        >
          학습 초기화
        </Button>
      </div>
      {notice ? (
        <p className={styles.notice} role="status">
          <CircleCheck size={18} aria-hidden="true" />
          {notice}
        </p>
      ) : null}

      <dialog
        ref={dialogRef}
        className={styles.dialog}
        aria-labelledby={titleId}
        onCancel={(event) => {
          event.preventDefault()
          close()
        }}
      >
        <div className={styles.dialogBody}>
          <h2 id={titleId}>학습 기록을 모두 지울까요?</h2>
          <p>완료한 글, 읽은 위치, 확인 문제의 첫 답, 익힘, 경험치, 배지를 모두 지웁니다.</p>
          <p className={styles.warning}>
            지운 기록은 되살릴 수 없습니다. 모든 글이 '안 읽음'이 되고, 같은 글과 문제로 경험치와
            배지를 처음부터 다시 받습니다.
          </p>
          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
          <div className={styles.dialogActions}>
            <Button type="button" variant="outline" onClick={close} disabled={pending}>
              취소
            </Button>
            <Button type="button" onClick={() => void confirm()} disabled={pending}>
              {pending ? '지우는 중…' : '모두 지우기'}
            </Button>
          </div>
        </div>
      </dialog>
    </div>
  )
}

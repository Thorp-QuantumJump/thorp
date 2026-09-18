import { Dialog } from 'radix-ui'
import { useRef } from 'react'
import { Button } from '@/components/ui/button'
import styles from './comparison.module.css'

export type PeriodConfirmation = {
  start: string | null
  end: string | null
  warmupTradingDays: number | null
  message?: string
  requiredStart?: string | null
}

export function PeriodMismatchDialog({
  period,
  busy,
  error,
  returnFocusId,
  onConfirm,
  onCancel,
}: {
  period: PeriodConfirmation
  busy: boolean
  error?: string | null
  returnFocusId?: string
  onConfirm: () => void
  onCancel: () => void
}) {
  const previousFocus = useRef(
    document.activeElement instanceof HTMLElement ? document.activeElement : null,
  )
  const valid =
    period.start !== null &&
    period.end !== null &&
    period.start <= period.end &&
    period.warmupTradingDays !== null
  const cancel = () => {
    onCancel()
    queueMicrotask(() => {
      const target =
        (returnFocusId ? document.getElementById(returnFocusId) : null) ?? previousFocus.current
      if (target?.isConnected) target.focus()
    })
  }
  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open && !busy) cancel()
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content
          className={styles.dialog}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            const target =
              (returnFocusId ? document.getElementById(returnFocusId) : null) ?? previousFocus.current
            if (target?.isConnected) target.focus()
          }}
          onEscapeKeyDown={(event) => {
            if (busy) event.preventDefault()
          }}
          onInteractOutside={(event) => event.preventDefault()}
        >
          <Dialog.Title>같은 조건으로 다시 비교</Dialog.Title>
          <Dialog.Description>
            두 결과를 모두 다시 계산해야 합니다. 원래 결과와 주소는 그대로 남습니다.
          </Dialog.Description>
          {period.message ? <p>{period.message}</p> : null}
          <p>
            {valid
              ? '공통 구간: ' + period.start + ' ~ ' + period.end
              : '공통으로 평가할 수 있는 구간을 확인할 수 없습니다.'}
          </p>
          {period.warmupTradingDays !== null ? (
            <p>공통 준비 기간: {period.warmupTradingDays}거래일</p>
          ) : null}
          {period.requiredStart ? <p>필요 시작일: {period.requiredStart}</p> : null}
          {error ? <p role="alert">{error}</p> : null}
          <div className={styles.actions}>
            <Button variant="outline" disabled={!valid || busy} onClick={onConfirm}>
              {busy ? '요청 중' : '두 결과 다시 계산'}
            </Button>
            <Button variant="outline" disabled={busy} onClick={cancel}>
              취소
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

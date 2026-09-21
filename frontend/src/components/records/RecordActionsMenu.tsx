import { MoreHorizontal } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { exportMyRecordUrl } from '@/api/myRecords'
import type { MyRecordDetail, MyRecordListItem } from '@/types/myRecords'
import { DeleteConfirmDialog } from './DeleteConfirmDialog'
import { RenameDialog } from './RenameDialog'
import styles from './records.module.css'

type Props = {
  item: MyRecordListItem
  onRenamed: (detail: MyRecordDetail) => void
  onDeleted: () => void
  /** 조작의 시작·실패를 알리는 자리. 화면 하나에 하나만 둔다(FR-021) */
  announce: (message: string) => void
  /** `?resultToken=`으로 들어온 줄은 메뉴를 열어 둔다(T021) */
  initiallyOpen?: boolean
}

/**
 * 줄 하나의 '더 보기'(spec 006 FR-021, T045·T048).
 *
 * <p><b>내보내기는 주소만 연다.</b> 서버가 `Content-Disposition: attachment`로 내려보내므로 앵커의 `download`가 그대로
 * 받는다 — 여기서 받아 Blob 으로 다시 만들면 파일 이름을 화면이 짓게 되고 서버가 정한 이름과 갈린다.
 *
 * <p><b>진행 중인 항목은 내보내지 않는다.</b> 결과가 아직 없어 서버가 409로 거절하므로, 눌러 본 뒤에 실패하는 대신 사유와
 * 함께 비활성으로 둔다. `disabled` 대신 `aria-disabled`인 이유는 초점을 받아야 사유가 읽히기 때문이다.
 */
export function RecordActionsMenu({
  item,
  onRenamed,
  onDeleted,
  announce,
  initiallyOpen = false,
}: Props) {
  const menuId = useId()
  const reasonId = useId()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(initiallyOpen)
  const [dialog, setDialog] = useState<'rename' | 'delete' | null>(null)
  const running = item.status === 'RUNNING'

  // 메뉴를 닫으면 초점이 연 버튼으로 돌아온다(FR-021). 대화상자가 열려 있는 동안에는 가져오지
  // 않는다 — 그러면 대화상자 안에서 입력하던 초점을 뺏는다.
  useEffect(() => {
    if (!open && dialog === null) triggerRef.current?.focus()
  }, [open, dialog])

  function startExport(format: 'csv' | 'json') {
    announce(`${item.name} 을(를) ${format.toUpperCase()} 로 내려받습니다.`)
    setOpen(false)
  }

  return (
    <div className={styles.actions}>
      <button
        ref={triggerRef}
        type="button"
        aria-label={`${item.name} 더 보기`}
        aria-expanded={open}
        aria-controls={menuId}
        aria-haspopup="true"
        className={styles.menuButton}
        onClick={() => setOpen((current) => !current)}
      >
        <MoreHorizontal aria-hidden="true" size={18} />
      </button>

      {open ? (
        <div id={menuId} className={styles.menu} role="menu">
          <button type="button" role="menuitem" onClick={() => setDialog('rename')}>
            이름 바꾸기
          </button>
          {running ? (
            <>
              <span role="menuitem" aria-disabled="true" aria-describedby={reasonId} tabIndex={0}>
                CSV로 내보내기
              </span>
              <span role="menuitem" aria-disabled="true" aria-describedby={reasonId} tabIndex={0}>
                JSON으로 내보내기
              </span>
              <p id={reasonId} className={styles.menuReason}>
                아직 계산이 끝나지 않아 내보낼 결과가 없습니다.
              </p>
            </>
          ) : (
            <>
              <a
                role="menuitem"
                href={exportMyRecordUrl(item.recordId, 'csv')}
                download
                onClick={() => startExport('csv')}
              >
                CSV로 내보내기
              </a>
              <a
                role="menuitem"
                href={exportMyRecordUrl(item.recordId, 'json')}
                download
                onClick={() => startExport('json')}
              >
                JSON으로 내보내기
              </a>
            </>
          )}
          <button type="button" role="menuitem" onClick={() => setDialog('delete')}>
            지우기
          </button>
        </div>
      ) : null}

      {/* 닫힌 대화상자를 DOM 에 두지 않는다 — 그 안의 저장·지우기 버튼이 줄의 버튼으로 세어져
          "행마다 주 버튼 하나"(원칙 II)가 깨진다. */}
      {dialog === 'rename' ? (
        <RenameDialog
        open
        recordId={item.recordId}
        currentName={item.name}
        onClose={() => {
          setDialog(null)
          setOpen(false)
        }}
        onRenamed={(detail) => {
          onRenamed(detail)
          announce(`이름을 ${detail.name}(으)로 바꿨습니다.`)
        }}
        />
      ) : null}
      {dialog === 'delete' ? (
        <DeleteConfirmDialog
        open
        recordId={item.recordId}
        recordName={item.name}
        onClose={() => {
          setDialog(null)
          setOpen(false)
        }}
        onDeleted={() => {
          onDeleted()
          announce(`${item.name} 을(를) 목록에서 지웠습니다.`)
        }}
        />
      ) : null}
    </div>
  )
}

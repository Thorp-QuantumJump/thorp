import { Bot } from 'lucide-react'
import { Popover } from 'radix-ui'
import {
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react'
import { AssistantBubble } from './AssistantBubble'
import type { AssistantPage } from './assistantPageContext'
import styles from './assistant.module.css'
import {
  DRAG_THRESHOLD_PX,
  type FabPosition,
  clampFabY,
  loadFabPosition,
  saveFabPosition,
  snapSide,
} from './fabPosition'

const NARROW_QUERY = '(max-width: 600px)'

/** 좁은 화면(≤600px)이면 끌기 없이 우측 하단 고정 + 바닥 시트다(FR-002·FR-003). jsdom 처럼 `matchMedia`가 없으면 넓은 화면이다. */
function useNarrowScreen(): boolean {
  const [narrow, setNarrow] = useState(() => window.matchMedia?.(NARROW_QUERY).matches ?? false)
  useEffect(() => {
    const query = window.matchMedia?.(NARROW_QUERY)
    if (!query) return undefined
    const update = () => setNarrow(query.matches)
    update()
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return narrow
}

type DragState = { pointerId: number; startX: number; startY: number; dx: number; dy: number }

/**
 * 질문하기 플로팅 버튼(spec 015 US3, FR-001~FR-003).
 *
 * <p>넓은 화면: 포인터로 끌어 옮기고 놓으면 가까운 쪽 여백에 붙는다. 자리는 브라우저에 기억한다. 누르면 옆에
 * 말풍선(Radix Popover, 비모달)이 열리고 Escape·바깥 누르기·다시 누르기로 닫히며 초점은 버튼으로 돌아온다.
 * 좁은 화면: 우측 하단 고정, 말풍선은 바닥 시트다. 화면(맥락)이 바뀌면 닫는다(FR-015).
 *
 * <p>`ui/Button`을 쓰지 않는다 — 결과 화면의 주 모양 버튼은 '비교 대상 고르기' 하나여야 한다(원칙 II).
 */
export function AssistantFab({
  page,
  openTab,
}: {
  page: AssistantPage
  openTab: (tab: string) => void
}) {
  const narrow = useNarrowScreen()
  const panelId = useId()
  const buttonRef = useRef<HTMLButtonElement>(null)
  const sheetRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<FabPosition>(() => loadFabPosition(window.innerHeight))
  const [drag, setDrag] = useState<DragState | null>(null)
  const suppressClickRef = useRef(false)
  const [open, setOpen] = useState(false)

  const close = useCallback(() => {
    setOpen(false)
    buttonRef.current?.focus()
  }, [])

  // 화면이 바뀌면 이전 화면의 말풍선을 닫는다. 답은 말풍선이 자기 맥락 변화를 보고 버린다.
  useEffect(() => {
    setOpen(false)
  }, [page.kind, page.publicToken])

  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (narrow || event.button !== 0) return
    event.currentTarget.setPointerCapture?.(event.pointerId)
    setDrag({ pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, dx: 0, dy: 0 })
  }
  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!drag || event.pointerId !== drag.pointerId) return
    setDrag({ ...drag, dx: event.clientX - drag.startX, dy: event.clientY - drag.startY })
  }
  const onPointerUp = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!drag || event.pointerId !== drag.pointerId) return
    event.currentTarget.releasePointerCapture?.(event.pointerId)
    const moved = Math.hypot(drag.dx, drag.dy) >= DRAG_THRESHOLD_PX
    if (moved) {
      const next: FabPosition = {
        side: snapSide(event.clientX, window.innerWidth),
        y: clampFabY(position.y + drag.dy, window.innerHeight),
      }
      setPosition(next)
      saveFabPosition(next)
      suppressClickRef.current = true
    }
    setDrag(null)
  }
  const onClick = () => {
    if (suppressClickRef.current) {
      suppressClickRef.current = false
      return
    }
    setOpen((current) => !current)
  }

  const style: CSSProperties | undefined = narrow
    ? undefined
    : {
        top: position.y,
        [position.side]: 'var(--thorp-page-gutter, 16px)',
        transform: drag ? `translate(${drag.dx}px, ${drag.dy}px)` : undefined,
      }

  const button = (
    <button
      ref={buttonRef}
      type="button"
      className={styles.fab}
      style={style}
      data-side={position.side}
      data-narrow={narrow || undefined}
      data-dragging={drag ? 'true' : undefined}
      aria-label="질문하기"
      aria-haspopup="dialog"
      aria-expanded={open}
      aria-controls={open ? panelId : undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={() => setDrag(null)}
      onClick={onClick}
    >
      <Bot size={24} aria-hidden="true" />
    </button>
  )

  const bubble = (
    <AssistantBubble page={page} openTab={openTab} onClose={close} autoFocus={narrow} />
  )

  if (narrow) {
    return (
      <>
        {button}
        {open ? (
          <BottomSheet id={panelId} sheetRef={sheetRef} buttonRef={buttonRef} onClose={close}>
            {bubble}
          </BottomSheet>
        ) : null}
      </>
    )
  }

  return (
    <Popover.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) close()
      }}
    >
      <Popover.Anchor asChild>{button}</Popover.Anchor>
      <Popover.Portal>
        <Popover.Content
          id={panelId}
          className={styles.popover}
          side={position.side === 'left' ? 'right' : 'left'}
          align="end"
          sideOffset={8}
          collisionPadding={16}
          aria-label="질문하기"
          data-shell="popover"
          onOpenAutoFocus={(event) => {
            event.preventDefault()
            document.getElementById(panelId)?.querySelector('textarea')?.focus()
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            buttonRef.current?.focus()
          }}
        >
          {bubble}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

/** 좁은 화면의 바닥 시트. 비모달이라 뒤 화면을 막지 않고, Escape·바깥 누르기로 닫힌다. */
function BottomSheet({
  id,
  sheetRef,
  buttonRef,
  onClose,
  children,
}: {
  id: string
  sheetRef: React.RefObject<HTMLDivElement | null>
  buttonRef: React.RefObject<HTMLButtonElement | null>
  onClose: () => void
  children: React.ReactNode
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (sheetRef.current?.contains(target) || buttonRef.current?.contains(target)) return
      onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [onClose, sheetRef, buttonRef])

  return (
    <div
      ref={sheetRef}
      id={id}
      role="dialog"
      aria-modal="false"
      aria-label="질문하기"
      className={styles.sheet}
      data-shell="sheet"
    >
      {children}
    </div>
  )
}

import { Popover } from 'radix-ui'
import type { ReactNode } from 'react'
import styles from './InlineHelp.module.css'

interface InlineHelpProps {
  /** 무엇에 대한 도움말인지. 트리거의 접근 가능한 이름과 내용 제목에 함께 쓴다 */
  label: string
  /** 펼쳤을 때 보일 설명. 화면이 직접 가진 문장이다 */
  children: ReactNode
}

/**
 * 화면이 가진 설명을 물음표 뒤로 내리는 도움말.
 *
 * <p><b>[TermHelp](../learning/TermHelp.tsx)와 역할이 다르다.</b> TermHelp는 `termId`로 013 용어집을
 * 조회해 등록된 문장만 보이므로 화면이 문장을 넘길 수 없다. 이 컴포넌트는 반대로 화면이 가진 문장을
 * 그대로 받는다 — 지표 계산식이나 이 화면에서만 쓰는 구분처럼 013이 소유하지 않는 설명이 대상이다
 * (013 FR-008이 013의 범위를 개념 용어로 한정한다).
 *
 * <p><b>설명을 지우는 대신 층을 내린다.</b> 본문에 늘 펼쳐 두면 읽지 않고 지나치고, 아예 없애면
 * 알아야 할 사람이 알 수 없다. 처음에 보이는 것과 요청하면 펼치는 것을 나눈다(원칙 II).
 *
 * <p>Popover가 키보드 열기·Esc 닫기·닫은 뒤 트리거로 초점 되돌리기를 맡는다(005 FR-022).
 */
export function InlineHelp({ label, children }: InlineHelpProps) {
  return (
    <Popover.Root>
      <Popover.Trigger className={styles.trigger} aria-label={`${label} 도움말`}>
        ?
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.content} sideOffset={6} aria-label={`${label} 도움말`}>
          <p className={styles.term}>{label}</p>
          <div className={styles.body}>{children}</div>
          <Popover.Close className={styles.close} aria-label="도움말 닫기">
            닫기
          </Popover.Close>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

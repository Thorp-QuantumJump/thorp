import { HoverCard, Popover } from "radix-ui";
import type { ReactNode } from "react";
import styles from "./InlineHelp.module.css";

interface InlineHelpProps {
  /** 무엇에 대한 도움말인지. 트리거의 접근 가능한 이름과 내용 제목에 함께 쓴다 */
  label: string;
  /** 펼쳤을 때 보일 설명. 화면이 직접 가진 문장이다 */
  children: ReactNode;
  /**
   * 무엇으로 여는가. 기본은 누르기다.
   *
   * `'hover'`는 마우스를 얹기만 해도 열린다. 읽을거리가 짧고 화면이 이미 붐빌 때 쓴다 — 누르는 품을 없애는
   * 대신 닫기 버튼이 없다. 트리거는 이 모드에서도 버튼이라 초점을 받으면 열리고, 그래서 키보드와 터치에서도
   * 닿는다(누르면 초점이 간다).
   */
  on?: "click" | "hover";
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
export function InlineHelp({ label, children, on = "click" }: InlineHelpProps) {
  if (on === "hover") {
    return (
      <HoverCard.Root openDelay={80} closeDelay={80}>
        <HoverCard.Trigger asChild>
          <button
            type="button"
            className={styles.trigger}
            aria-label={`${label} 도움말`}
          >
            ?
          </button>
        </HoverCard.Trigger>
        <HoverCard.Portal>
          <HoverCard.Content
            className={styles.content}
            side="top"
            sideOffset={6}
            collisionPadding={12}
            aria-label={`${label} 도움말`}
          >
            <p className={styles.term}>{label}</p>
            <div className={styles.body}>{children}</div>
          </HoverCard.Content>
        </HoverCard.Portal>
      </HoverCard.Root>
    );
  }

  return (
    <Popover.Root>
      <Popover.Trigger
        className={styles.trigger}
        aria-label={`${label} 도움말`}
      >
        ?
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          className={styles.content}
          side="top"
          sideOffset={6}
          collisionPadding={12}
          aria-label={`${label} 도움말`}
        >
          <p className={styles.term}>{label}</p>
          <div className={styles.body}>{children}</div>
          <Popover.Close className={styles.close} aria-label="도움말 닫기">
            닫기
          </Popover.Close>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

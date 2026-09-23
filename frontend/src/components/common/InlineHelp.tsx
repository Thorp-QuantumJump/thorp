import { HoverCard, Popover } from "radix-ui";
import { useRef, useState, type ReactNode } from "react";
import styles from "./InlineHelp.module.css";

interface InlineHelpProps {
  /** 무엇에 대한 도움말인지. 트리거의 접근 가능한 이름과 내용 제목에 함께 쓴다 */
  label: string;
  /** 펼쳤을 때 보일 설명. 화면이 직접 가진 문장이다 */
  children: ReactNode;
  /**
   * 무엇으로 여는가. 기본은 얹기다.
   *
   * 물음표 뒤의 설명은 짧고 화면은 이미 붐비므로 누르는 품을 없애는 쪽을 기본으로 둔다. 대신 닫기 버튼이
   * 없다. 트리거는 이 모드에서도 버튼이라 초점을 받으면 열리고, 그래서 키보드와 터치에서도 닿는다
   * (누르면 초점이 간다).
   *
   * `'click'`은 눌러야 열리고 닫기 버튼이 붙는다. 설명이 길어 마우스를 얹은 채로 읽기 어려울 때만 쓴다.
   */
  on?: "click" | "hover";
  /** 도움말을 펼칠 방향. */
  side?: "top" | "bottom" | "right";
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
 * <p>기본은 HoverCard가 얹어서 열기를 맡고 초점·누르기는 트리거에서 직접 잇는다. `on="click"`에서는
 * Popover가 키보드 열기·Esc 닫기·닫은 뒤 트리거로 초점 되돌리기를 맡는다(005 FR-022).
 */
export function InlineHelp({ label, children, on = "hover", side = "top" }: InlineHelpProps) {
  const [open, setOpen] = useState(false);
  const closedByEscape = useRef(false);
  if (on === "hover") {
    return (
      <HoverCard.Root
        open={open}
        onOpenChange={(next) => setOpen(next && !closedByEscape.current)}
        openDelay={80}
        closeDelay={80}
      >
        <HoverCard.Trigger asChild>
          {/*
            여는 조건을 우리가 쥔다. HoverCard 는 마우스를 얹을 때만 열고 초점·누르기에는 반응하지
            않는다 — 그대로 두면 키보드와 터치에서 읽을 방법이 없다(확인: 2026-09-22).
          */}
          <button
            type="button"
            className={styles.trigger}
            aria-label={`${label} 도움말`}
            aria-expanded={open}
            onFocus={() => {
              if (!closedByEscape.current) setOpen(true);
            }}
            onBlur={() => {
              closedByEscape.current = false;
              setOpen(false);
            }}
            onPointerLeave={() => {
              closedByEscape.current = false;
            }}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                closedByEscape.current = true;
                setOpen(false);
              }
            }}
            onClick={() => setOpen((current) => !current)}
          >
            ?
          </button>
        </HoverCard.Trigger>
        <HoverCard.Portal>
          <HoverCard.Content
            className={styles.content}
            side={side}
            align={side === "top" ? "center" : "start"}
            alignOffset={side === "right" ? -8 : 0}
            sideOffset={6}
            collisionPadding={16}
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
          side={side}
          align={side === "top" ? "center" : "start"}
          alignOffset={side === "right" ? -8 : 0}
          sideOffset={6}
          collisionPadding={16}
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

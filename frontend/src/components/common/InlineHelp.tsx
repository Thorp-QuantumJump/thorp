import { Popover } from "radix-ui";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
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
  /** 도움말을 펼칠 방향. 오른쪽 끝 칸처럼 위로 펼치면 화면을 벗어나는 자리에서 바꾼다(427) */
  side?: "top" | "bottom" | "right";
}

/** 마우스가 지나가기만 할 때 열리지 않도록 두는 틈 */
const OPEN_DELAY_MS = 80;
/** 트리거에서 말풍선으로 마우스를 옮기는 동안 닫히지 않도록 두는 틈 */
const CLOSE_DELAY_MS = 80;

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
 * <p><b>여닫는 주체를 하나로 둔다.</b> 얹기 모드에서도 HoverCard 가 아니라 Popover 를 쓰고 트리거를
 * 앵커로만 붙인다. HoverCard 의 트리거는 초점을 받으면 <b>자기 지연 타이머</b>로 여는데, 그 타이머는
 * 닫을 때 지워지지 않는다. 그래서 초점 직후 Esc 를 누르면 닫힌 뒤 지연 시간만큼 지나 저절로 다시
 * 열렸다(S15P21E205-422). 여기서는 지연을 우리가 걸고 <b>상태가 바뀔 때마다 반드시 지운다</b> —
 * 닫으라는 신호가 오면 예약된 열기가 남아 있을 수 없다. Popover 에는 내부 타이머가 없다.
 *
 * <p>Esc 닫기와 바깥 누르기는 Popover 가 맡고, 결과는 {@code onOpenChange} 로 돌아와 같은 자리에서
 * 처리된다. 말풍선에는 초점을 옮기지 않는다 — 초점이 트리거에 남아야 Esc 를 누른 사람이 제자리에
 * 있고(005 FR-022), 트리거를 떠날 때 닫는 판단도 그대로 산다.
 */
export function InlineHelp({ label, children, on = "hover", side = "top" }: InlineHelpProps) {
  const [open, setOpen] = useState(false);
  const timerRef = useRef<number | undefined>(undefined);

  const clearPending = useCallback(() => {
    if (timerRef.current !== undefined) {
      window.clearTimeout(timerRef.current);
      timerRef.current = undefined;
    }
  }, []);

  /** 예약된 것을 지우고 곧바로 정한다. 닫기 신호가 예약된 열기를 반드시 이긴다. */
  const settle = useCallback(
    (next: boolean) => {
      clearPending();
      setOpen(next);
    },
    [clearPending],
  );

  const schedule = useCallback(
    (next: boolean, delay: number) => {
      clearPending();
      timerRef.current = window.setTimeout(() => {
        timerRef.current = undefined;
        setOpen(next);
      }, delay);
    },
    [clearPending],
  );

  // 화면을 떠난 뒤 타이머가 깨어나 없는 컴포넌트를 건드리지 않게 한다.
  useEffect(() => clearPending, [clearPending]);

  if (on === "hover") {
    return (
      <Popover.Root open={open} onOpenChange={settle}>
        <Popover.Anchor asChild>
          {/*
            여는 조건을 우리가 쥔다. 얹기는 틈을 두고, 초점과 누르기는 곧바로 반영한다 — 키보드와
            터치에서는 기다릴 이유가 없고, 기다리면 그 틈이 곧 위 주석의 사고로 돌아온다.
          */}
          <button
            type="button"
            className={styles.trigger}
            aria-label={`${label} 도움말`}
            aria-expanded={open}
            onPointerEnter={() => schedule(true, OPEN_DELAY_MS)}
            onPointerLeave={() => schedule(false, CLOSE_DELAY_MS)}
            onFocus={() => settle(true)}
            onBlur={() => settle(false)}
            onClick={() => settle(!open)}
          >
            ?
          </button>
        </Popover.Anchor>
        <Popover.Portal>
          <Popover.Content
            // 얹어서 읽는 설명이라 대화 상자가 아니다. 초점을 옮기지 않으므로 dialog 로 두면
            // 화면 낭독기에 "열렸는데 갈 수 없는 대화 상자"로 읽힌다.
            role="tooltip"
            className={styles.content}
            side={side}
            align={side === "bottom" ? "start" : "center"}
            sideOffset={6}
            collisionPadding={16}
            aria-label={`${label} 도움말`}
            // 초점은 트리거에 그대로 둔다. 옮기면 트리거가 흐려져 곧바로 닫힌다.
            onOpenAutoFocus={(event) => event.preventDefault()}
            onCloseAutoFocus={(event) => event.preventDefault()}
            // 트리거에서 말풍선으로 마우스를 옮기는 동안 닫히지 않게 한다.
            onPointerEnter={() => settle(true)}
            onPointerLeave={() => schedule(false, CLOSE_DELAY_MS)}
          >
            <p className={styles.term}>{label}</p>
            <div className={styles.body}>{children}</div>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
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
          align={side === "bottom" ? "start" : "center"}
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

import { Popover } from "radix-ui";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  fetchGlossaryEntry,
  type GlossaryLookup,
} from "@/api/learningGlossary";
import type { LearningReturnState } from "@/types/learning";
import styles from "./TermHelp.module.css";

interface TermHelpProps {
  /** 가리킬 용어의 고정 식별자(013 data-model §8) */
  termId: string;
  /** 트리거 버튼에 보일 이름. 설명을 불러오기 전에도 버튼이 이름을 가져야 한다 */
  label: string;
  /** 글로 이동했다가 돌아올 자리. 보통 지금 화면의 경로와 이름이다 */
  returnTo: LearningReturnState;
  /**
   * 013 설명 앞에 함께 보일, <b>화면이 가진</b> 한 문장. 없으면 그리지 않는다.
   *
   * 013 용어집은 개념의 뜻을 말하고, 이 자리는 그 값이 이 화면에서 무엇을 정하는지를 말한다(003 등록표의
   * 축 설명이 그것이다). 두 문장이 도움말 하나에 함께 있어야 한 항목에 물음표가 둘 생기지 않는다.
   * 이 컴포넌트는 여전히 문장을 짓지 않는다 — 받은 것을 그대로 보인다.
   */
  note?: ReactNode;
}

type HelpState = { status: "loading" } | GlossaryLookup;

/**
 * 진단 화면의 용어 도움말(013 term-help-ui §2, 003 FR-016).
 *
 * <p><b>002·003·005가 함께 쓴다.</b> 화면마다 따로 만들면 세 상태와 접근성 규칙이 갈라진다.
 *
 * <p><b>설명 문장을 짓지 않는다.</b> 받은 문장을 그대로 보이고, 설명이 없으면 '설명 준비 중'만 보인다
 * (013 FR-010). 불러오지 못한 것은 설명이 없는 것과 다른 사실이라 다른 문장과 다시 시도를 준다.
 *
 * <p><b>여는 행동이 요청을 시작한다.</b> 화면을 열 때 미리 부르지 않는다(013 research R10).
 *
 * <p><b>호출한 화면의 상태를 건드리지 않는다.</b> 열고 닫는 것은 이 컴포넌트 안의 상태뿐이고, 글로
 * 가는 연결은 같은 탭에서 이동하되 입력값을 싣지 않는다 — 002·003의 입력은 각자의 `sessionStorage`
 * 규칙이 지킨다(013 FR-009, 003 FR-017).
 *
 * <p>Popover가 키보드 열기·Esc 닫기·닫은 뒤 트리거로 초점 되돌리기를 맡는다(013 FR-019, 003 NFR-001).
 */
export function TermHelp({ termId, label, returnTo, note }: TermHelpProps) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<HelpState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    let active = true;
    setState({ status: "loading" });
    void fetchGlossaryEntry(termId, controller.signal).then((result) => {
      if (active) setState(result);
    });
    return () => {
      active = false;
      controller.abort();
    };
  }, [open, termId, attempt]);

  const retry = useCallback(() => setAttempt((count) => count + 1), []);

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        className={styles.trigger}
        aria-label={`${label} 도움말`}
      >
        ?
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          className={styles.content}
          sideOffset={6}
          aria-label={`${label} 도움말`}
        >
          {note ? <p className={styles.sentence}>{note}</p> : null}

          {state.status === "loading" ? (
            <p className={styles.muted}>설명을 불러오는 중입니다.</p>
          ) : null}

          {state.status === "ready" ? (
            <>
              <p className={styles.term}>{state.entry.label}</p>
              <p className={styles.sentence}>{state.entry.sentence}</p>
              <Link
                className={styles.link}
                to={`/learn/${encodeURIComponent(state.entry.articleId)}`}
                state={{ learningReturn: returnTo }}
              >
                ‘{state.entry.articleTitle}’ 글 읽기
              </Link>
            </>
          ) : null}

          {state.status === "notReady" ? (
            <p className={styles.muted}>설명 준비 중</p>
          ) : null}

          {state.status === "failed" ? (
            <div className={styles.failed}>
              <p role="alert">설명을 불러오지 못했습니다.</p>
              <button type="button" className={styles.retry} onClick={retry}>
                다시 시도
              </button>
            </div>
          ) : null}

          <Popover.Close className={styles.close} aria-label="도움말 닫기">
            닫기
          </Popover.Close>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

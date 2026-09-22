import { InlineHelp } from "@/components/common/InlineHelp";
import styles from "./ReducedPeriodNotice.module.css";
import {
  describeEvaluationWindow,
  describeReducedCause,
  type ReducedPeriodInput,
} from "./reducedPeriod";

interface ReducedPeriodNoticeProps {
  /** 평가 기간과 그 까닭. 까닭을 받지 못했으면 `null`이다 */
  input: ReducedPeriodInput | null;
  /**
   * 기간이 정해졌다는 판정은 있으나 까닭을 받지 못했을 때 보일 판정 문장. 줄지 않았으면 넘기지 않는다.
   *
   * 까닭을 모른다고 안내를 통째로 빼면 SC-004가 요구한 두 자리 가운데 한 곳이 빈다.
   */
  fallbackMessage?: string;
}

/**
 * 실행 전 안내(T066, FR-011·SC-004). '분석하기' 바로 위에 둔다.
 *
 * <p><b>사실은 늘 보이고 까닭은 펼쳐 본다.</b> 평가 기간 한 줄은 그대로 두고, 왜 그 기간인지는 옆의
 * 도움말을 열었을 때만 보인다. 예전에는 까닭까지 한 문장에 담아 매번 읽혔는데, 사용자가 고른 적 없는
 * 기간을 두고 "줄었다"고 말하는 문장이라 읽을 때마다 무엇을 잃은 것처럼 읽혔다(`S15P21E205-406`).
 * SC-004 도 그에 맞춰 두 곳에 기간을 보이고 까닭은 펼쳐 보는 것으로 고쳤다.
 *
 * <p>도움말은 화면이 가진 문장을 담는 {@link InlineHelp}다 — 013 이 소유하는 용어가 아니라 이 화면의
 * 판정 근거라서 `TermHelp`가 아니고, 저장소가 이미 쓰는 물음표 하나로 도움말 모양을 맞춘다.
 *
 * <p><b>줄지 않았으면 그리지 않는다.</b> "줄지 않았습니다" 같은 문장은 매번 읽히는 소음이 된다.
 * <b>막는 안내가 아니다</b> — 이 기간으로도 분석할 수 있고, 막을지는 최소 평가 기간 판정이 정한다.
 */
export function ReducedPeriodNotice({
  input,
  fallbackMessage,
}: ReducedPeriodNoticeProps) {
  const message =
    input !== null ? describeEvaluationWindow(input) : fallbackMessage;
  if (message === undefined) return null;

  return (
    <p className={styles.notice} role="status" aria-label="평가 기간">
      {message}
      {input !== null ? (
        <span className={styles.help}>
          <InlineHelp label="평가 기간">
            {describeReducedCause(input)}
          </InlineHelp>
        </span>
      ) : null}
    </p>
  );
}

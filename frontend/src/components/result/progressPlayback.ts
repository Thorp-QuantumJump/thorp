/**
 * 진행 곡선을 하루씩 그리는 재생 규칙.
 *
 * <p><b>왜 다시 나누는가.</b> 서버는 20거래일을 한 묶음으로 보낸다. 묶음 안에는 거래일마다 계산된 점이
 * 그대로 들어 있으므로, 화면이 하루씩 내보내도 없는 값을 지어내지 않는다. 보간이 아니라 이미 받은 값을
 * 늦게 공개하는 것이다.
 *
 * <p><b>왜 고정 속도가 아닌가.</b> 다음 묶음이 언제 올지 미리 알 수 없다. 고정 속도는 두 방향으로
 * 어긋난다 — 빠르면 받은 점을 다 쓰고 멈춰 "쭉 그리고 멈춤"을 반복하고, 느리면 계산이 끝난 뒤에도
 * 곡선만 한참 뒤를 따라간다. 그래서 대기열에 남은 양을 보고 속도를 정한다.
 */

/** 한 프레임에 적어도 이만큼은 내보낸다. 0이면 대기열이 줄지 않는다 */
const MIN_PER_FRAME = 1

/**
 * 대기열을 몇 프레임에 걸쳐 비울지. 크면 천천히 그려 다음 묶음이 올 시간을 벌고, 작으면 빨리 따라잡는다.
 * 60fps 기준으로 12프레임은 0.2초이므로, 20거래일 묶음 하나가 대략 그 시간에 그려진다.
 */
const FRAMES_TO_DRAIN = 12

/**
 * 대기열이 이보다 길면 밀린 것으로 보고 한 번에 비운다. 묶음 셋 이상이 쌓였다면 계산이 화면보다 훨씬
 * 앞서 있다는 뜻이고, 그때 천천히 그리면 끝난 결과를 기다리게 만든다.
 */
const CATCH_UP_THRESHOLD = 60

/**
 * 이번 프레임에 내보낼 점의 수.
 *
 * @param queued 대기열에 남은 점의 수
 * @param finished 실행이 끝났는가. 끝났으면 더 기다릴 묶음이 없으므로 남은 것을 모두 내보낸다
 */
export function drainCount(queued: number, finished: boolean): number {
  if (queued <= 0) return 0
  if (finished || queued >= CATCH_UP_THRESHOLD) return queued
  return Math.max(MIN_PER_FRAME, Math.ceil(queued / FRAMES_TO_DRAIN))
}

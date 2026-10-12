import { useCallback, useEffect, useState } from 'react'
import { fetchLearningMe } from '@/api/learningMe'
import type { LearnerOverview } from '@/types/learningMe'
import type { SettledAuth } from '../article/useSettledAuth'

/**
 * '나의 학습' 전체 조회의 상태.
 *
 * - `off`: 부르지 않는다. 비로그인·로그인 확인 전·첫 단계 공개 전, 또는 서버가 기록할 수 없다고 했다. 나의 학습
 *   요소를 그리지 않는다
 * - `loading`: 처음 받는 중이다. 그 자리를 비운다
 * - `loaded`: 받았다. 같은 사용자로 다시 받는 동안에도 받아 둔 값을 그대로 둔다
 * - `failed`: 받지 못했다. '학습 기록을 불러오지 못했습니다'와 '다시 시도'를 한 번 보인다. 어느 자리도 '안 읽음'·
 *   0편·레벨 1로 채우지 않는다(FR-020)
 */
export type OverviewState =
  | { status: 'off' }
  | { status: 'loading' }
  | { status: 'loaded'; overview: LearnerOverview }
  | { status: 'failed' }

type Snapshot = {
  /** 이 상태가 속한 사용자. 바뀌면 처음부터다 */
  owner: string | null
  state: OverviewState
  /** 마지막으로 끝난 요청이 어느 `refreshKey`의 것이었나 */
  finishedFor: string | null
  /** 마지막으로 성공한 요청이 어느 `refreshKey`의 것이었나. 다시 받다가 실패하면 앞 값이 남아 지금 키와 다르다 */
  okFor: string | null
}

interface OverviewOptions {
  /** 마지막으로 확인된 로그인 상태(`useSettledAuth`). 재확인(`checking`)·`error`는 바뀐 것으로 보지 않는다 */
  auth: SettledAuth
  /** 부를 수 있는 때인가. 첫 단계를 공개한 뒤(`isFirstStageReleased`)에만 참이다(research R33) */
  enabled: boolean
  /**
   * 같은 사용자로 다시 받을 때 바꾸는 값. 글 끝 완료 카드가 새 경험치를 받을 때마다 레벨과 다음 배지를 다시 받는다
   * (T204). 바뀌어도 받아 둔 값은 새 값이 올 때까지 그대로다
   */
  refreshKey?: string
}

/**
 * '나의 학습' 전체(`GET /learning/me`, 013 FR-020, 계약 §1).
 *
 * <p><b>로그인 상태이고 첫 단계를 공개한 뒤에만 부른다.</b> 첫 단계 공개 전에는 서버가 409를 주므로 요청하지 않고
 * 나의 학습 요소를 그리지 않는다(research R33). 서버가 그래도 409 `LEARNING_NOT_RECORDABLE`을 주면 같은 모습(`off`)이다.
 *
 * <p><b>사용자가 바뀌거나 로그아웃하면 버린다.</b> 받은 값은 화면 상태에만 두고 브라우저 저장소에 쓰지 않는다. 재확인
 * (`checking`)과 `error` 동안에는 `user`가 `null`이어도 마지막으로 확인된 사용자를 기준으로 하므로(`useSettledAuth`)
 * 받아 둔 값을 그대로 두고, 같은 `userId`로 확인되면 다시 요청하지 않는다. 그동안 보일지는 부르는 쪽이 `concealed`로 정한다.
 *
 * <p><b>조용히 재시도하지 않는다.</b> 실패는 실패로 두고, 사용자가 '다시 시도'를 누를 때 `retry`로 다시 부른다(계약 §0).
 */
export function useLearnerOverview({ auth, enabled, refreshKey = '' }: OverviewOptions) {
  const owner = enabled && auth.kind === 'authenticated' ? String(auth.userId) : null
  const [snapshot, setSnapshot] = useState<Snapshot>(() => initial(owner))
  const [attempt, setAttempt] = useState(0)

  // 사용자가 바뀌면 렌더 중에 비운다. 효과로 미루면 앞 사용자의 기록이 한 번 그려진다.
  const ownSnapshot = snapshot.owner === owner ? snapshot : initial(owner)
  if (snapshot.owner !== owner) setSnapshot(ownSnapshot)

  useEffect(() => {
    if (owner === null) return
    const forOwner = owner
    const forKey = `${refreshKey}#${attempt}`
    const controller = new AbortController()
    setSnapshot((previous) =>
      previous.owner !== forOwner || previous.state.status === 'loaded'
        ? previous
        : { ...previous, state: { status: 'loading' } },
    )
    void fetchLearningMe(controller.signal).then((result) => {
      if (controller.signal.aborted) return
      setSnapshot((previous) => {
        if (previous.owner !== forOwner) return previous
        if (result.status === 'ok') {
          return {
            owner: forOwner,
            state: { status: 'loaded', overview: result.data },
            finishedFor: forKey,
            okFor: forKey,
          }
        }
        if (result.status === 'notRecordable')
          return { ...previous, state: { status: 'off' }, finishedFor: forKey }
        // 다시 받다가 실패했으면 받아 둔 값을 지우지 않는다. 그 값은 여전히 이 사용자의 기록이다.
        if (previous.state.status === 'loaded') return { ...previous, finishedFor: forKey }
        return { ...previous, state: { status: 'failed' }, finishedFor: forKey }
      })
    })
    return () => controller.abort()
  }, [owner, refreshKey, attempt])

  /** 사용자가 '다시 시도'를 눌렀다. */
  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  const key = `${refreshKey}#${attempt}`
  return {
    state: ownSnapshot.state,
    /** 지금 `refreshKey`의 요청이 끝났는가(성공이든 실패든). 완료 순간의 알림이 다음 목표를 덧붙일지 기다리는 데 쓴다 */
    finished: owner === null || ownSnapshot.finishedFor === key,
    /**
     * 지금 `refreshKey`의 요청이 성공해 `loaded`가 그 응답인가. 다시 받다가 실패하면 앞 응답이 남아 있어도 거짓이다.
     * 새 경험치를 반영하지 않은 앞 응답으로 다음 목표를 그리지 않기 위해서다
     */
    upToDate: owner !== null && ownSnapshot.okFor === key,
    retry,
  }
}

function initial(owner: string | null): Snapshot {
  return {
    owner,
    state: owner === null ? { status: 'off' } : { status: 'loading' },
    finishedFor: null,
    okFor: null,
  }
}

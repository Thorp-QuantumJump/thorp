import { useEffect, useMemo, useRef, useState } from 'react'
import {
  hasGainedNews,
  settledGainedNotice,
  type GainedEntry,
} from '@/components/learning/article/completionText'
import type { SettledAuth } from '@/components/learning/article/useSettledAuth'
import { nextGoalSentence } from '@/components/learning/me/meCopy'
import type { LearnerOverview } from '@/types/learningMe'
import { useLearnerOverview } from './useLearnerOverview'

/**
 * 레벨 길의 점이 옮겨 가는 움직임을 주는 시간 창(ms). 받은 것이 생긴 뒤 이 안에 '나의 학습'이 와야 움직임을 준다. 늦게
 * 온 만큼 움직임의 시작을 당겨, 카드의 다른 움직임과 한 장면으로 950ms 안에 끝나게 한다(design-notes §5). 창을 넘기면 점을
 * 옮기지 않고 최종 모습만 보인다. 레벨 길 움직임의 가장 이른 시작(300ms 뒤)보다 짧아야 한다.
 */
export const GOAL_MOTION_WINDOW_MS = 250

/** 완료 뒤 다시 받은 '나의 학습' 응답 하나가 알릴 것. 같은 응답이면 같은 값이다. */
export type OverviewNotice = {
  /** 그 요청의 정산으로 새로 생긴 배지·레벨의 문장(계약 §4). 없으면 빈 문자열이다 */
  news: string
  /** 다음 목표 문장(T208). 없으면 빈 문자열이다 */
  goal: string
}

export type CompletionFollowUp = {
  /** 이 화면에서 받은 것까지 반영한 '나의 학습'. 아직 받지 못했거나 받지 못했으면 `null`이다 */
  overview: LearnerOverview | null
  /** 그 요청의 정산으로 이 화면에서 새로 생긴 것. 완료 카드의 받은 것에 잇는다 */
  extra: GainedEntry[]
  /**
   * 받은 것이 생긴 뒤 '나의 학습'이 오기까지 걸린 시간(ms). 레벨 길 움직임을 그만큼 당긴다. 움직임 창을 넘겼으면
   * `null`이고 움직이지 않는다
   */
  lateMs: number | null
  /** 알림 영역이 알릴 것. '나의 학습'을 받기 전이면 `null`이다 */
  notice: OverviewNotice | null
  /** 지금 요청이 끝났는가(성공이든 실패든). 알림이 다음 목표를 덧붙일지 기다리는 데 쓴다 */
  finished: boolean
}

type FollowUpState = {
  /** 이 상태가 속한 사용자와 글 판. 바뀌면 처음부터다 */
  owner: object
  extra: GainedEntry[]
  /** 마지막으로 반영한 응답 */
  seen: LearnerOverview | null
  lateMs: number | null
}

/**
 * 완료 카드가 다시 받는 '나의 학습'(013 T204, 계약 §4).
 *
 * <p>완료 카드가 놓이면 한 번, 이 화면에서 완료·첫 답·다시 풀기로 경험치·배지를 받을 때마다 다시 받는다. 다시 연 완료
 * 글도 받는다. 단계 완주 메달을 그 단계의 배지로 그리려면 배지 목록이 있어야 하기 때문이다(US6 시나리오 8). 그 요청도
 * 정산을 하므로(계약 §1) 운영자의 게시 취소나 단계 이동으로 채워진 배지·단계 완주가 그 응답의 `gained`로 올 수 있다.
 * 버리면 알림 없이 지나가므로(FR-030) 받은 것에 잇고 알린다. 같은 응답은 한 번만 잇는다.
 *
 * <p>받지 못해도 카드의 글자는 그대로이고, 레벨 길과 다음 목표만 빠진다. 다시 받다가 실패했으면 앞 응답으로 다음 목표를
 * 그리지 않는다.
 */
export function useCompletionFollowUp({
  auth,
  enabled,
  articleRevision,
  gainedCount,
}: {
  auth: SettledAuth
  /** 완료 카드가 놓였는가 */
  enabled: boolean
  /** 글을 다시 불러온 횟수 */
  articleRevision: number
  /** 이 화면에서 받은 응답(경험치·배지가 생긴 것)의 수 */
  gainedCount: number
}): CompletionFollowUp {
  const refreshKey = useMemo(
    () => ({ articleRevision, gainedCount }),
    [articleRevision, gainedCount],
  )
  const request = useLearnerOverview({ auth, enabled, refreshKey })
  const overview =
    request.upToDate && request.state.status === 'loaded' ? request.state.overview : null

  const ownerId = auth.kind === 'authenticated' ? String(auth.userId) : auth.kind
  const owner = useMemo(() => ({ ownerId, articleRevision }), [ownerId, articleRevision])
  const [state, setState] = useState<FollowUpState>(() => fresh(owner))
  const gainedAt = useRef(0)
  const current = state.owner === owner ? state : fresh(owner)
  if (state.owner !== owner) setState(current)

  // 받은 것이 생긴 때를 적어 둔다. 그 뒤 '나의 학습'이 오기까지 걸린 시간으로 움직임을 줄지 정한다.
  useEffect(() => {
    gainedAt.current = Date.now()
  }, [gainedCount])

  useEffect(() => {
    if (!overview) return
    const elapsed = Math.max(0, Date.now() - gainedAt.current)
    const lateMs = elapsed <= GOAL_MOTION_WINDOW_MS ? elapsed : null
    setState((previous) => {
      if (previous.owner !== owner || previous.seen === overview) return previous
      const extra = hasGainedNews(overview.gained)
        ? [
            ...previous.extra,
            { gained: overview.gained, receivedAt: new Date().toISOString(), stageProgress: null },
          ]
        : previous.extra
      return { ...previous, seen: overview, lateMs, extra }
    })
  }, [overview, owner])

  const notice = useMemo(
    () =>
      overview
        ? { news: settledGainedNotice(overview.gained), goal: nextGoalSentence(overview) }
        : null,
    [overview],
  )
  return {
    overview,
    extra: current.extra,
    lateMs: current.lateMs,
    notice,
    finished: request.finished,
  }
}

function fresh(owner: object): FollowUpState {
  return { owner, extra: [], seen: null, lateMs: 0 }
}

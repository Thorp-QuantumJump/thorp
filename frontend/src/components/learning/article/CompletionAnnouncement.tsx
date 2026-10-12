import { useEffect, useRef, useState } from 'react'
import type { OverviewNotice } from '@/hooks/useCompletionFollowUp'
import { QuietNotice } from '../me/QuietNotice'
import type { Announcement } from './useLearnerArticle'

/**
 * 다음 목표를 받기를 기다리는 가장 긴 시간(ms). 넘으면 다음 목표 없이 알린다(design-notes §3.4). 카드에 보이는 상태
 * 문장은 기다리지 않는다.
 */
export const SUFFIX_WAIT_MS = 4000

/**
 * 글 끝의 새 배지·레벨 조용한 알림(013 FR-030, design-notes §3.4, 목업의 `#live`). 처음부터 비어 있는 영역
 * (`QuietNotice`)에 방금 받은 것의 문장만 넣는다. 앞서 알린 것을 다시 읽지 않고, 초점을 옮기지 않는다.
 *
 * <p>글 기록 응답의 알림(`announcement`) 끝에는 완료 뒤 다시 받은 '나의 학습'의 정산으로 새로 생긴 것과 다음 목표('다음
 * 레벨까지 240, 다음 배지(읽은 글 5편)까지 1편입니다.', T208)를 덧붙인다. 그 응답을 받아야 알므로 요청이 끝날 때까지
 * (`ready`) 기다리고, 받지 못했거나 4초를 넘기면 덧붙이지 않는다.
 *
 * <p>'나의 학습'의 정산으로 새로 생긴 배지·레벨은 덧붙일 알림이 없어도 알린다(계약 §4). 경험치만 받은 완료라 글 기록
 * 응답의 알림이 없었거나, 응답이 기다림을 넘겨 왔을 때다. 그때는 그것과 다음 목표를 따로 한 번 알린다. 한 알림과 한
 * 응답의 새 배지는 한 번만 읽힌다.
 */
export function CompletionAnnouncement({
  announcement,
  notice,
  ready,
}: {
  announcement: Announcement | null
  notice: OverviewNotice | null
  ready: boolean
}) {
  const [message, setMessage] = useState('')
  const [waited, setWaited] = useState<number | null>(null)
  const latest = useRef({ notice, ready })
  latest.current = { notice, ready }
  // 덧붙일 것을 기다리는 알림과, 이미 읽힌 '나의 학습' 응답.
  const pending = useRef<number | null>(null)
  const told = useRef<OverviewNotice | null>(null)

  // 새 알림이 오면 비우고, 다음 목표를 기다리는 시간의 상한을 둔다.
  useEffect(() => {
    setMessage('')
    setWaited(null)
    pending.current = announcement?.id ?? null
    if (!announcement) return
    const timer = setTimeout(() => setWaited(announcement.id), SUFFIX_WAIT_MS)
    return () => clearTimeout(timer)
  }, [announcement])

  const settledNow = ready || waited === announcement?.id
  useEffect(() => {
    if (!announcement || !settledNow || pending.current !== announcement.id) return
    const { notice: arrived, ready: done } = latest.current
    pending.current = null
    const extra = done && arrived ? [arrived.news, arrived.goal] : []
    if (done && arrived) told.current = arrived
    setMessage([announcement.text, ...extra].filter(Boolean).join(' '))
  }, [announcement, settledNow])

  // 덧붙일 알림 없이 온 '나의 학습'의 새 배지·레벨은 따로 알린다.
  useEffect(() => {
    if (!notice?.news || told.current === notice || pending.current !== null) return
    told.current = notice
    setMessage([notice.news, notice.goal].filter(Boolean).join(' '))
  }, [notice])

  return <QuietNotice text={message} />
}

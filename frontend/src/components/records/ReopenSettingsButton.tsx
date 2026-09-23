import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { fetchMyRecord } from '@/api/myRecords'
import {
  type PrefillOutcome,
  SECTOR_SELECTION_PATH,
} from '@/components/result/prefillStrategySettings'
import { Button } from '@/components/ui/button'
import type { MyRecordDetail } from '@/types/myRecords'
import { reopenSettings } from './reopenSettings'
import styles from './records.module.css'

/** 이동하지 못해 그 자리에 남기는 안내 */
type Notice = Extract<PrefillOutcome, { status: 'failed' | 'reselectSectors' }>

const DETAIL_FAILED = '기록의 저장 조건을 불러오지 못해 설정 화면으로 넘길 수 없습니다. 잠시 후 다시 시도해 주세요.'

/**
 * '설정 바꿔 비교'(spec 006 FR-007, T030). 보조 모양이고,
 * 누르면 기록 상세를 받아(이미 있으면 그대로) `reopenSettings`로 003 설정 화면에 옮긴다.
 *
 * <p>결과 처리(contracts/record-handoff.md): `moved`는 할 일 없음, `reselectSectors`는 문장 + 섹터·비중 단계 연결,
 * `failed`는 문장, `canceled`(화면을 떠남)는 아무것도 없다. 화면을 떠나면 요청을 끊는다.
 *
 * <p>서버가 열 수 없다고 판정한 기록(`reopenSettings.available === false`)에는 이 버튼을 두지 않는다 — 그 판정은
 * 상세를 받아야 알 수 있어, 목록 행에서는 상세를 아직 모르면 버튼을 두고 눌렀을 때 판정 결과를 보인 뒤 버튼을 거둔다.
 */
export function ReopenSettingsButton({
  recordId,
  detail = null,
}: {
  recordId: string
  /** 펼쳐 보는 층이 이미 받아 둔 상세. 있으면 다시 받지 않는다 */
  detail?: MyRecordDetail | null
}) {
  const navigate = useNavigate()
  const [sending, setSending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  // 눌러서 직접 받아 온 상세. 열 수 없다는 판정을 기억해 버튼을 거두고, 다시 눌러도 같은 답을 다시 받지 않는다.
  const [learned, setLearned] = useState<MyRecordDetail | null>(null)
  const controllerRef = useRef<AbortController | null>(null)

  useEffect(() => () => controllerRef.current?.abort(), [])

  const known = detail ?? learned
  // 버튼만 거둔다. 눌러서 알게 된 경우에는 "열 수 없다" 문장은 남아야 왜 버튼이 사라졌는지 읽힌다.
  const unavailable = known?.reopenSettings?.available === false

  const move = async () => {
    if (sending) return
    const controller = new AbortController()
    controllerRef.current = controller
    setSending(true)
    setNotice(null)

    let loaded = known
    if (!loaded) {
      try {
        loaded = await fetchMyRecord(recordId, controller.signal)
        setLearned(loaded)
      } catch {
        if (controller.signal.aborted) return
        setSending(false)
        setNotice({ status: 'failed', message: DETAIL_FAILED })
        return
      }
    }
    const outcome = await reopenSettings(loaded, navigate, controller.signal)
    if (controller.signal.aborted || outcome.status === 'moved') return

    setSending(false)
    if (outcome.status !== 'canceled') setNotice(outcome)
  }

  return (
    <>
      {unavailable ? null : (
        <Button type="button" variant="outline" disabled={sending} onClick={() => void move()}>
          설정 바꿔 비교
        </Button>
      )}
      {notice ? (
        <div className={styles.alert} role={notice.status === 'failed' ? 'alert' : 'status'}>
          <p>{notice.message}</p>
          {notice.status === 'reselectSectors' ? (
            <Button asChild variant="link" size="sm" className="px-0">
              <Link to={SECTOR_SELECTION_PATH}>섹터·비중 단계로 가기</Link>
            </Button>
          ) : null}
        </div>
      ) : null}
    </>
  )
}

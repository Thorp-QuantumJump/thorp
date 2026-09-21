import { useEffect, useState } from 'react'
import { fetchMyRecord } from '@/api/myRecords'
import styles from './records.module.css'

export const SAME_VERSION_NOTICE = '동일 버전 조회 — 저장된 결과를 다시 계산하지 않고 그대로 보입니다.'

/**
 * 결과 화면이 내 기록에서 열렸을 때의 한 줄(FR-006·FR-008, quickstart S1-6·S5).
 *
 * <p>`recordId`가 있으면 상세를 받아 서버가 판정한 대응표 변경 문장만 보인다. 화면이 버전 문자열을 비교하지
 * 않고(원칙 II), 판정할 근거가 없어 문장이 없으면 아무것도 덧붙이지 않는다. 상세를 받지 못해도 결과 화면은 그대로다
 * — 이 줄은 결과의 조건이 아니라 덧붙이는 안내다.
 */
export function RecordOriginNotice({ recordId }: { recordId: string | null }) {
  const [drift, setDrift] = useState<string | null>(null)

  useEffect(() => {
    if (!recordId) return
    const controller = new AbortController()
    let active = true
    fetchMyRecord(recordId, controller.signal).then(
      (detail) => {
        if (active && detail.mappingDrift?.changed) setDrift(detail.mappingDrift.message)
      },
      () => undefined,
    )
    return () => {
      active = false
      controller.abort()
    }
  }, [recordId])

  return (
    <div className={styles.origin}>
      <p role="status">{SAME_VERSION_NOTICE}</p>
      {drift ? <p role="status">{drift}</p> : null}
    </div>
  )
}

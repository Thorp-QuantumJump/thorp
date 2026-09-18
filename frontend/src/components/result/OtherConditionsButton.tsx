import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import type { ResultConfig } from '@/types/result'
import { SECTOR_SELECTION_PATH, prefillStrategySettings } from './prefillStrategySettings'
import styles from './resultDetail.module.css'

/**
 * '다른 조건으로 비교'(FR-010). 이 결과의 조건을 채운 설정 화면으로 옮긴다. 거기서 분석하면 새 결과가 되고 이
 * 결과는 그대로 남는다.
 *
 * <p>보조 모양이다 — 결과 화면의 주 모양 버튼은 비교 대상 고르기 하나다(착수 전에 알아둘 것 ⑧).
 *
 * <p>설정 화면이 조건을 이어받을 수 없어 섹터를 다시 골라야 하는 경우(대응표가 바뀌었거나 섹터가 모자람)에는
 * 사유 옆에 섹터·비중 단계로 가는 연결을 둔다. 사유만 보이면 사용자가 그 단계를 스스로 찾아가야 한다.
 */
export function OtherConditionsButton({ config }: { config: ResultConfig }) {
  const navigate = useNavigate()
  const [sending, setSending] = useState(false)
  const [failure, setFailure] = useState<{ message: string; reselectSectors: boolean } | null>(
    null,
  )
  const controllerRef = useRef<AbortController | null>(null)

  useEffect(() => () => controllerRef.current?.abort(), [])

  const move = async () => {
    if (sending) return
    const controller = new AbortController()
    controllerRef.current = controller
    setSending(true)
    setFailure(null)

    const outcome = await prefillStrategySettings(config, navigate, controller.signal)
    if (controller.signal.aborted || outcome.status === 'moved') return

    setSending(false)
    if (outcome.status === 'failed') {
      setFailure({ message: outcome.message, reselectSectors: outcome.reselectSectors === true })
    }
  }

  return (
    <>
      <Button type="button" variant="outline" disabled={sending} onClick={() => void move()}>
        다른 조건으로 비교
      </Button>
      {failure ? (
        <div className={styles.row} role="alert">
          <p className={styles.alert}>{failure.message}</p>
          {failure.reselectSectors ? (
            <Button asChild variant="link" size="sm" className="px-0">
              <Link to={SECTOR_SELECTION_PATH}>섹터·비중 단계로 가기</Link>
            </Button>
          ) : null}
        </div>
      ) : null}
    </>
  )
}

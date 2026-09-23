import { TriangleAlert } from 'lucide-react'
import styles from './RejectionNotice.module.css'

interface RejectionNoticeProps {
  /** 서버가 준 사유. 오류 코드(`STRATEGY_NOT_REGISTERED`)나 멈춤 사유(`CANDIDATE_LIMIT`) 그대로. */
  code: string
  /** 서버가 함께 준 문장. 우리가 아는 사유가 아니면 이것이 유일한 설명이다. */
  message?: string | null
}

/** 한 문장 안내와, 사용자가 다음에 할 수 있는 일. */
type Guidance = { headline: string; next: string | null }

/**
 * 접수 거부 다섯 가지. **미등록과 실행 미지원을 다른 문장으로 적는다**(FR-026).
 *
 * 둘은 사용자가 할 일이 다르다 — 앞의 것은 전략이 등록되기를 기다리는 것이고, 뒤의 것은 등록은
 * 됐으니 실행 지원을 기다리는 것이다. "안 되는 전략입니다" 하나로 묶으면 그 차이가 사라진다.
 */
const guidanceByCode: Record<string, Guidance> = {
  CANDIDATE_LIMIT_EXCEEDED: {
    headline: '설정 조합이 한 번에 돌릴 수 있는 수를 넘어 실행하지 않았습니다.',
    next: '상세 변경에서 후보값을 줄이면 바로 실행할 수 있습니다.',
  },
  CANDIDATE_LIMIT: {
    headline: '설정 조합이 한 번에 돌릴 수 있는 수를 넘어 실행하지 않았습니다.',
    next: '상세 변경에서 후보값을 줄이면 바로 실행할 수 있습니다.',
  },
  NO_VALID_COMBINATION: {
    headline: '고른 범위에서 돌릴 수 있는 조합이 하나도 없어 실행하지 않았습니다.',
    next: '섹터를 늘리거나 후보값을 바꿔 주세요.',
  },
  NO_VALID_CANDIDATE: {
    headline: '고른 범위에서 돌릴 수 있는 조합이 하나도 없어 실행하지 않았습니다.',
    next: '섹터를 늘리거나 후보값을 바꿔 주세요.',
  },
  STRATEGY_NOT_REGISTERED: {
    headline: '등록표에 없는 전략이라 실행하지 않았습니다.',
    next: '전략이 등록되면 화면을 고치지 않아도 그대로 탐색할 수 있습니다.',
  },
  STRATEGY_NOT_EXECUTABLE: {
    headline: '등록은 되어 있지만 아직 실행을 지원하지 않는 전략이라 실행하지 않았습니다.',
    next: '실행 지원이 붙으면 그대로 탐색할 수 있습니다.',
  },
  REGISTRY_UNAVAILABLE: {
    headline: '전략·파라미터 등록 정보를 불러오지 못해 실행하지 않았습니다.',
    next: '잠시 후 다시 시도해 주세요. 지난 정보로 대신 실행하지는 않습니다.',
  },
  // 006 내 기록의 저장 상한(FR-018). 실행 자원 한도(429)와 다르다 — 기다려도 풀리지 않고 기존 기록을 지워야 풀린다.
  RECORD_LIMIT_REACHED: {
    headline: '저장할 수 있는 탐색 묶음 개수에 도달해 실행하지 않았습니다.',
    next: '내 기록에서 탐색 묶음을 지운 뒤 다시 시작할 수 있습니다.',
  },
}

/**
 * 접수 거부 안내(T104, FR-026).
 *
 * <p><b>오류 화면으로 만들지 않는다.</b> 거부는 고장이 아니라 "이 조건으로는 돌리지 않았다"는
 * 결과다. 빨간 전체 화면으로 덮으면 사용자는 자기가 무엇을 잘못했는지 모른 채 되돌아간다 —
 * 그래서 있던 화면을 그대로 두고 한 문장을 얹는다.
 *
 * <p>모르는 사유 코드는 서버가 준 문장을 그대로 보인다. 아는 코드로 접어서 잘못된 안내를 하느니
 * 덜 친절한 편이 낫다.
 */
export function RejectionNotice({ code, message }: RejectionNoticeProps) {
  const guidance = guidanceByCode[code] ?? null

  return (
    <section className={styles.notice} role="alert" aria-labelledby="rejection-title">
      <TriangleAlert size={17} aria-hidden="true" />
      <div>
        <p id="rejection-title" className={styles.headline}>
          {guidance?.headline ?? message ?? '이 조건으로는 실행하지 않았습니다.'}
        </p>
        {guidance && message ? <p className={styles.detail}>{message}</p> : null}
        {guidance?.next ? <p className={styles.next}>{guidance.next}</p> : null}
        <p className={styles.record}>
          {`사유 ${code} 로 기록해 두었습니다. 범위를 자동으로 줄여 대신 실행하지는 않습니다.`}
        </p>
      </div>
    </section>
  )
}

import { paint } from './materials'
import { roundCoord } from './svgCoord'
import styles from './medals.module.css'

/**
 * 레벨 표장·단계 완주 메달·갈래 메달이 함께 쓰는 조각(013 design-notes §3.3·§4·§5).
 */

/** 받음(금속과 에나멜), 아직 받지 않음(실선 회색과 진행 호), 준비 중(점선)이다. */
export type MedalState = 'earned' | 'notYet' | 'preparing'

/**
 * 아직 받지 않은 메달·표장 바깥의 초록 진행 호. 진행이 0 이하면 그리지 않는다. `d`는 실루엣을 바깥으로 넓힌 길이고, 호는
 * 그 길의 `progress`%(0~100)만큼 칠한다. `animate`면 차오르는 움직임을 준다(움직임 줄이기 설정이 아닐 때만).
 */
export function ProgressArc({
  d,
  unit,
  progress,
  animate,
}: {
  d: string
  /** 화면 1px이 그림판에서 몇 단위인가(`100 / size`) */
  unit: number
  progress: number
  animate: boolean
}) {
  if (progress <= 0) return null
  const filled = Math.max(0, Math.min(100, Math.round(progress)))
  return (
    <path
      className={animate ? `${styles.arc} ${styles.animateArc}` : styles.arc}
      d={d}
      strokeWidth={roundCoord(3 * unit)}
      strokeLinecap="round"
      pathLength={100}
      strokeDasharray={`${filled} 100`}
    />
  )
}

/** 받은 물건 위로 한 번 지나가는 흰 띠(목업 `sweep`). 움직임 줄이기 설정이면 윤곽 밖에 머물러 보이지 않는다. */
export function Sweep({ prefix, clipId }: { prefix: string; clipId: string }) {
  return (
    <g clipPath={`url(#${clipId})`}>
      <g transform="rotate(25 50 50)">
        <rect
          className={styles.sweep}
          x="-84"
          y="-40"
          width="46"
          height="180"
          fill={paint(prefix, 'sweep')}
        />
      </g>
    </g>
  )
}

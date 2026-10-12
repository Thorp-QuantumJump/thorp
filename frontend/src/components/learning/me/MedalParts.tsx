import type { ReactNode } from 'react'
import { paint } from './materials'
import { roundCoord } from './svgCoord'
import styles from './medals.module.css'

/**
 * 레벨 표장·단계 완주 메달·갈래 메달이 함께 쓰는 조각(013 design-notes §3.3·§4·§5). 세 그림은 같은 틀(100 단위
 * 그림판, `aria-hidden`), 같은 윤곽 문법(실선 회색과 점선), 같은 금속 마감(둘레의 짙은 선, 면의 빛과 그늘)을 쓴다.
 */

/** 받음(금속과 에나멜), 아직 받지 않음(실선 회색과 진행 호), 준비 중(점선)이다. */
export type MedalState = 'earned' | 'notYet' | 'preparing'

/**
 * 메달·표장 그림 한 장의 틀. 100 단위 그림판과 화면 크기, 보조 기술 숨김(`aria-hidden`), 상태 클래스(받은 물건의 그림자)를
 * 한곳에서 붙인다. `mark`는 화면과 테스트가 그림을 가리는 표지(`data-medal`·`data-emblem`)다. 진행 호와 그림자처럼
 * 실루엣 밖으로 나가는 것이 있으면 `overflow`를 준다.
 */
export function MedalSvg({
  size,
  state,
  className,
  mark,
  overflow = false,
  children,
}: {
  size: number
  state: MedalState
  className?: string
  mark: { 'data-medal': string } | { 'data-emblem': string }
  overflow?: boolean
  children: ReactNode
}) {
  const classes = [styles.medal, state === 'earned' ? styles.earned : null, className]
    .filter(Boolean)
    .join(' ')
  return (
    <svg
      className={classes}
      viewBox="0 0 100 100"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      overflow={overflow ? 'visible' : undefined}
      {...mark}
    >
      {children}
    </svg>
  )
}

/** 받은 메달 둘레의 짙은 선. 금속 테두리가 바탕에서 떨어져 보이게 한다. */
export function RimShade({
  d,
  unit,
  color = '#0b0d0c',
}: {
  d: string
  /** 화면 1px이 그림판에서 몇 단위인가(`100 / size`) */
  unit: number
  color?: string
}) {
  return (
    <path
      d={d}
      fill="none"
      stroke={color}
      strokeOpacity={0.55}
      strokeWidth={roundCoord(0.8 * unit)}
    />
  )
}

/** 받은 메달 밑의 그림자(재질 `shadow`). */
export function GroundShadow({ prefix }: { prefix: string }) {
  return <ellipse cx="50" cy="97" rx="31" ry="3.4" fill={paint(prefix, 'shadow')} />
}

/** 받은 메달 면 위쪽의 흰 빛(재질 `highlight`). `clipId`로 면 안쪽만 칠한다. */
export function FaceHighlight({ prefix, clipId }: { prefix: string; clipId: string }) {
  return (
    <g clipPath={`url(#${clipId})`}>
      <path d="M0 0H100V25L0 44Z" fill={paint(prefix, 'highlight')} />
    </g>
  )
}

/** 받은 메달 면 테두리의 빛과 그늘(재질 `bevel`). */
export function FaceBevel({
  prefix,
  d,
  unit,
  width = 1.25,
}: {
  prefix: string
  d: string
  unit: number
  /** 화면 px로 센 선 굵기 */
  width?: number
}) {
  return (
    <path
      d={d}
      fill="none"
      stroke={paint(prefix, 'bevel')}
      strokeWidth={roundCoord(width * unit)}
    />
  )
}

/**
 * 아직 받지 않은 메달의 실선 윤곽(`dashed` 없음)과 준비 중 메달의 점선 윤곽. 같은 색·같은 굵기이고 선 모양으로만
 * 다르다(design-notes §4). 강제 색 모드에서도 선 모양이 남는다.
 */
export function MedalOutline({
  d,
  unit,
  dashed = false,
}: {
  d: string
  unit: number
  dashed?: boolean
}) {
  return (
    <path
      className={styles.outline}
      d={d}
      strokeWidth={roundCoord(1.5 * unit)}
      strokeDasharray={dashed ? `${roundCoord(4 * unit)} ${roundCoord(3 * unit)}` : undefined}
    />
  )
}

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

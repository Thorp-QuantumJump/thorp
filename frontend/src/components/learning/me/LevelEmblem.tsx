import { useId } from 'react'
import { MaterialDefs } from './MaterialDefs'
import { paint, svgId } from './materials'
import { medalPath } from './medalShapes'
import styles from './medals.module.css'

export type MedalState = 'earned' | 'notYet' | 'preparing'

interface LevelEmblemProps {
  level: number
  /** 화면 크기(px) */
  size: number
  /** 받은 레벨(초록 금속), 아직 닿지 않은 레벨(실선 회색과 진행 호), 지금은 닿을 수 없는 레벨(점선) */
  state?: MedalState
  /** `notYet`의 진행(0~100) */
  progress?: number
  /** 숫자를 그리지 않는다. 완료 카드가 숫자가 넘어가는 움직임을 그 위에 따로 얹을 때다 */
  hideNumber?: boolean
  /** 방금 닿은 레벨이면 흰 띠가 한 번 지나간다(움직임 줄이기 설정이 아닐 때만) */
  shine?: boolean
  /** 진행 호가 차오르는 움직임을 준다 */
  animate?: boolean
  /** 눈금 묶음에 붙일 클래스. 완료 카드가 새로 생긴 눈금을 나타나게 할 때 쓴다 */
  ticksClassName?: string
  className?: string
}

const SMALL = 40

/**
 * 레벨 표장(013 design-notes §3.3, 목업 `emblemSVG`). 둥근 팔각형의 초록 금속이고, 레벨이 올라도 재질은 같고 안쪽
 * 고리와 눈금만 는다(레벨 2 = 고리 둘, 3 = 고리 둘과 눈금 12, 4 이상 = 고리 셋과 눈금 24). 동·은·금 같은 재질 서열과
 * 레벨 이름은 없다.
 *
 * <p>그림이라 보조 기술에 읽히지 않는다(`aria-hidden`). 레벨은 옆의 글자('레벨 3')가 원본이다. 그라디언트 식별자는
 * `useId`로 만들어 한 화면에 여러 표장이 있어도 겹치지 않는다. 40px 미만이면 고리·눈금을 빼고 숫자만 키운다.
 */
export function LevelEmblem({
  level,
  size,
  state = 'earned',
  progress = 0,
  hideNumber = false,
  shine = false,
  animate = false,
  ticksClassName,
  className,
}: LevelEmblemProps) {
  const prefix = svgId(useId())
  const unit = 100 / size
  const small = size < SMALL
  const rim = (size >= 96 ? 7 : size >= 72 ? 6 : size >= 48 ? 4.5 : 2.5) * unit
  const outer = medalPath('level')
  const face = medalPath('level', rim)
  const rings = level >= 4 ? 3 : 2
  const tickCount = level >= 4 ? 24 : level >= 3 ? 12 : 0
  const fontSize = small ? round(Math.min(52, 12 * unit)) : 50
  const classes = [styles.medal, state === 'earned' ? styles.earned : null, className]
    .filter(Boolean)
    .join(' ')

  const ringPaths = (strokeWidth: number, opacity: number, stroke?: string) =>
    small
      ? null
      : Array.from({ length: rings }, (_, i) => (
          <path
            key={i}
            d={medalPath('level', rim + 4.5 + i * 4.4)}
            fill="none"
            stroke={stroke ?? 'currentColor'}
            strokeWidth={round(strokeWidth)}
            opacity={opacity}
          />
        ))

  const ticks = (strokeWidth: number, opacity: number, color: string, extraClass?: string) => {
    if (small || tickCount === 0) return null
    const lines = []
    for (let i = 0; i < tickCount; i += 1) {
      const angle = (((i * 360) / tickCount - 90) * Math.PI) / 180
      const inner = tickCount === 24 && i % 2 === 1 ? 25.1 : 23.5
      lines.push(
        <line
          key={i}
          x1={round(50 + inner * Math.cos(angle))}
          y1={round(51 + inner * Math.sin(angle))}
          x2={round(50 + 27 * Math.cos(angle))}
          y2={round(51 + 27 * Math.sin(angle))}
        />,
      )
    }
    return (
      <g
        className={extraClass}
        stroke={color}
        strokeWidth={round(strokeWidth)}
        strokeLinecap="round"
        opacity={opacity}
      >
        {lines}
      </g>
    )
  }

  const number = (fill: string | undefined, numberClass?: string) =>
    hideNumber ? null : (
      <text
        className={numberClass}
        x="50"
        y={small ? 52 : 51.5}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={fontSize}
        fontWeight={800}
        fill={fill}
      >
        {level}
      </text>
    )

  if (state === 'earned') {
    return (
      <svg
        className={classes}
        viewBox="0 0 100 100"
        width={size}
        height={size}
        aria-hidden="true"
        focusable="false"
        data-emblem="earned"
      >
        <defs>
          <MaterialDefs prefix={prefix} materials={['gmetal', 'gtext', 'face', 'pin', 'sweep']} />
          <clipPath id={`${prefix}-faceclip`}>
            <path d={face} />
          </clipPath>
          <clipPath id={`${prefix}-outerclip`}>
            <path d={outer} />
          </clipPath>
        </defs>
        <path d={outer} fill={paint(prefix, 'gmetal')} />
        <path
          d={outer}
          fill="none"
          stroke="#04140b"
          strokeOpacity={0.55}
          strokeWidth={round(0.8 * unit)}
        />
        <path d={face} fill={paint(prefix, 'face')} />
        {ringPaths(1.1 * unit, 0.7, paint(prefix, 'gmetal'))}
        {ticks(1.3 * unit, 0.85, '#7EF0B0', ticksClassName)}
        {number(small ? '#C8FFE0' : paint(prefix, 'gtext'))}
        <g clipPath={`url(#${prefix}-faceclip)`}>
          <path d="M0 0H100V24L0 42Z" fill="#fff" opacity={0.12} />
        </g>
        <path d={face} fill="none" stroke={paint(prefix, 'pin')} strokeWidth={round(1.2 * unit)} />
        {shine ? <Sweep prefix={prefix} clipId={`${prefix}-outerclip`} /> : null}
      </svg>
    )
  }

  if (state === 'notYet') {
    return (
      <svg
        className={classes}
        viewBox="0 0 100 100"
        width={size}
        height={size}
        aria-hidden="true"
        focusable="false"
        data-emblem="notYet"
      >
        <g className={styles.muted}>
          <path className={styles.outline} d={outer} strokeWidth={round(1.5 * unit)} />
          {ringPaths(unit, 0.55)}
          {ticks(1.1 * unit, 0.6, 'currentColor')}
        </g>
        {number(undefined, styles.mutedText)}
        {progress > 0 ? (
          <path
            className={animate ? `${styles.arc} ${styles.animateArc}` : styles.arc}
            d={medalPath('level', -4.2 * unit)}
            strokeWidth={round(3 * unit)}
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={`${clampPercent(progress)} 100`}
          />
        ) : null}
      </svg>
    )
  }

  return (
    <svg
      className={classes}
      viewBox="0 0 100 100"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      data-emblem="preparing"
    >
      <path
        className={styles.outline}
        d={outer}
        strokeWidth={round(1.5 * unit)}
        strokeDasharray={`${round(4 * unit)} ${round(3 * unit)}`}
      />
      {number(undefined, styles.preparingText)}
    </svg>
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

function round(value: number): number {
  return Math.round(value * 100) / 100
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, Math.round(value)))
}

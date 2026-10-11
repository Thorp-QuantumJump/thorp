import { useId } from 'react'
import { stageHex } from '../stageColors'
import type { MedalState } from './LevelEmblem'
import { Sweep } from './LevelEmblem'
import { MaterialDefs } from './MaterialDefs'
import { paint, svgId } from './materials'
import { medalPath, stageArchProgressPath, stageLeadPath, stageNumberCircle } from './medalShapes'
import styles from './medals.module.css'

interface StageMedalProps {
  stageNumber: number
  /** 화면 크기(px) */
  size: number
  /** 받음(단계 색 스테인드글라스), 아직(실선 회색 아치와 진행 호), 준비 중(점선 '설계 도면') */
  state: MedalState
  /** `notYet`의 진행(0~100). 그 단계에서 읽은 글의 비율이다 */
  progress?: number
  shine?: boolean
  animate?: boolean
  className?: string
}

/** 색유리를 나누는 납선과 둘레의 색. 의미 토큰(바탕색)과 같은 값을 쓰지 않으려고 아주 조금 다르게 둔다. */
const LEAD = '#0C0E0D'

/**
 * 단계 완주 메달(013 design-notes §3.3·§4, 목업 `medalSVG('stage')`·`stained`). 고딕 아치 창 실루엣이다.
 *
 * <p>받은 메달은 은록 금속 테두리 안에 그 단계의 장식 색으로 밝기를 달리한 유리 조각 여섯 장과 납선, 가운데 번호 원을
 * 둔다. 아직 받지 않은 메달은 실선 회색 아치와 초록 진행 호(창턱을 빼고 옆면과 꼭대기만 따라간다), 준비 중이면 점선
 * 아치와 점선 납선이다. 단계 장식 색은 꾸밈이고 상태는 윤곽 모양이 나른다. 36px 미만이면 번호 원 없이 그린다.
 *
 * <p>그림이라 보조 기술에 읽히지 않는다(`aria-hidden`). 상태는 옆의 글자('완주 · 4편', '4편 중 3편 읽음')가 원본이다.
 */
export function StageMedal({
  stageNumber,
  size,
  state,
  progress = 0,
  shine = false,
  animate = false,
  className,
}: StageMedalProps) {
  const prefix = svgId(useId())
  const unit = 100 / size
  const outer = medalPath('stage')
  const big = size >= 36
  const { fontSize, radius } = big ? stageNumberCircle(size) : { fontSize: 0, radius: 7 }
  const classes = [styles.medal, state === 'earned' ? styles.earned : null, className]
    .filter(Boolean)
    .join(' ')

  if (state === 'earned') {
    const rim = (size >= 96 ? 5 : size >= 72 ? 4 : size >= 48 ? 3 : 2) * unit
    const inner = medalPath('stage', rim)
    const glass = glassTones(stageHex(stageNumber))
    const leadWidth = round(2.5 * unit)
    return (
      <svg
        className={classes}
        viewBox="0 0 100 100"
        width={size}
        height={size}
        aria-hidden="true"
        focusable="false"
        overflow="visible"
        data-medal="stage-earned"
      >
        <defs>
          <MaterialDefs
            prefix={prefix}
            materials={['metal', 'glass', 'hl', 'pin', 'shadow', 'sweep']}
          />
          <clipPath id={`${prefix}-inner`}>
            <path d={inner} />
          </clipPath>
          <clipPath id={`${prefix}-outer`}>
            <path d={outer} />
          </clipPath>
        </defs>
        <ellipse cx="50" cy="97" rx="31" ry="3.4" fill={paint(prefix, 'shadow')} />
        <path d={outer} fill={paint(prefix, 'metal')} />
        <path
          d={outer}
          fill="none"
          stroke="#0b0d0c"
          strokeOpacity={0.55}
          strokeWidth={round(0.8 * unit)}
        />
        <g clipPath={`url(#${prefix}-inner)`}>
          <path d="M0 0H50V44.5L0 50Z" fill={glass.a} />
          <path d="M50 0H100V50L50 44.5Z" fill={glass.d} />
          <path d="M0 50L50 44.5V74L0 66Z" fill={glass.c} />
          <path d="M50 44.5L100 50V66L50 74Z" fill={glass.b} />
          <path d="M0 66L50 74V100H0Z" fill={glass.e} />
          <path d="M50 74L100 66V100H50Z" fill={glass.a} />
          <rect width="100" height="100" fill={paint(prefix, 'glass')} />
          <path
            d="M27 18L36 30M66 56L76 61M24 80L32 88"
            stroke="#fff"
            strokeOpacity={0.35}
            strokeWidth={round(1.2 * unit)}
            strokeLinecap="round"
          />
          <path
            d={stageLeadPath(radius, 0, 100, 0, 100)}
            fill="none"
            stroke={LEAD}
            strokeWidth={leadWidth}
            strokeLinejoin="round"
          />
          <circle cx="50" cy="41" r={radius} fill={glass.f} stroke={LEAD} strokeWidth={leadWidth} />
          {big ? (
            <text
              x="50"
              y="41.5"
              textAnchor="middle"
              dominantBaseline="central"
              fontSize={fontSize}
              fontWeight={800}
              fill="#102016"
            >
              {stageNumber}
            </text>
          ) : null}
        </g>
        <g clipPath={`url(#${prefix}-inner)`}>
          <path d="M0 0H100V25L0 44Z" fill={paint(prefix, 'hl')} />
        </g>
        <path
          d={inner}
          fill="none"
          stroke={paint(prefix, 'pin')}
          strokeWidth={round(1.25 * unit)}
        />
        {shine ? <Sweep prefix={prefix} clipId={`${prefix}-outer`} /> : null}
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
        overflow="visible"
        data-medal="stage-notYet"
      >
        <g className={styles.muted}>
          <path className={styles.outline} d={outer} strokeWidth={round(1.5 * unit)} />
          <path
            d={stageLeadPath(big ? radius : 0)}
            fill="none"
            stroke="currentColor"
            strokeWidth={round(1.25 * unit)}
            strokeLinecap="round"
            strokeLinejoin="round"
            opacity={0.6}
          />
          {big ? (
            <circle
              cx="50"
              cy="41"
              r={radius}
              fill="none"
              stroke="currentColor"
              strokeWidth={round(1.25 * unit)}
            />
          ) : null}
        </g>
        {big ? (
          <text
            className={styles.mutedText}
            x="50"
            y="41.5"
            textAnchor="middle"
            dominantBaseline="central"
            fontSize={fontSize}
            fontWeight={800}
          >
            {stageNumber}
          </text>
        ) : null}
        {progress > 0 ? (
          <path
            className={animate ? `${styles.arc} ${styles.animateArc}` : styles.arc}
            d={stageArchProgressPath(-4.2 * unit)}
            strokeWidth={round(3 * unit)}
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={`${Math.max(0, Math.min(100, Math.round(progress)))} 100`}
          />
        ) : null}
      </svg>
    )
  }

  const dash = `${round(3 * unit)} ${round(3 * unit)}`
  return (
    <svg
      className={classes}
      viewBox="0 0 100 100"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      overflow="visible"
      data-medal="stage-preparing"
    >
      <g className={styles.muted}>
        <path
          className={styles.outline}
          d={outer}
          strokeWidth={round(1.5 * unit)}
          strokeDasharray={`${round(4 * unit)} ${round(3 * unit)}`}
        />
        {big ? (
          <>
            <path
              d={stageLeadPath(radius)}
              fill="none"
              stroke="currentColor"
              strokeWidth={round(unit)}
              strokeOpacity={0.55}
              strokeDasharray={dash}
            />
            <circle
              cx="50"
              cy="41"
              r={radius}
              fill="none"
              stroke="currentColor"
              strokeWidth={round(unit)}
              strokeOpacity={0.75}
              strokeDasharray={dash}
            />
          </>
        ) : null}
      </g>
      <text
        className={styles.preparingText}
        x="50"
        y={big ? 41.5 : 50}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={big ? fontSize : 34}
        fontWeight={800}
      >
        {stageNumber}
      </text>
    </svg>
  )
}

/** 단계 색을 흰색·짙은 색과 섞어 유리 조각 여섯 톤을 만든다(목업 `stained`). 단계 색이 없으면 옅은 회록이다. */
function glassTones(base: string | null) {
  const color = base ?? '#9AA6A0'
  const white = '#ffffff'
  const dark = '#0c0f0e'
  return {
    a: mix(color, white, 0.3),
    b: color,
    c: mix(color, dark, 0.38),
    d: mix(color, white, 0.1),
    e: mix(color, dark, 0.2),
    f: mix(color, white, 0.62),
  }
}

function channels(hex: string): [number, number, number] {
  const value = hex.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16)) as [number, number, number]
}

/** 두 hex 색을 `t`만큼 섞는다. */
function mix(a: string, b: string, t: number): string {
  const [ar, ag, ab] = channels(a)
  const [br, bg, bb] = channels(b)
  const channel = (x: number, y: number) =>
    Math.round(x + (y - x) * t)
      .toString(16)
      .padStart(2, '0')
  return `#${channel(ar, br)}${channel(ag, bg)}${channel(ab, bb)}`
}

function round(value: number): number {
  return Math.round(value * 100) / 100
}

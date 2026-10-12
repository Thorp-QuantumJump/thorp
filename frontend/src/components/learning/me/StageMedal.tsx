import { useId } from 'react'
import { stageHex } from '../stageColors'
import { MaterialDefs } from './MaterialDefs'
import {
  FaceBevel,
  FaceHighlight,
  GroundShadow,
  MedalOutline,
  MedalSvg,
  ProgressArc,
  RimShade,
  Sweep,
  type MedalState,
} from './MedalParts'
import { paint, svgId } from './materials'
import {
  medalPath,
  medalRim,
  stageArchProgressPath,
  stageLeadPath,
  stageNumberCircle,
} from './medalShapes'
import { roundCoord } from './svgCoord'
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

/**
 * 단계 완주 메달(013 design-notes §3.3·§4, 목업 `medalSVG('stage')`·`stained`). 고딕 아치 창 실루엣이다.
 *
 * <p>받은 메달은 은록 금속 테두리 안에 그 단계의 장식 색으로 밝기를 달리한 유리 조각 여섯 장과 납선, 가운데 번호 원을
 * 둔다. 납선은 바탕색 토큰이라 디자인 전환을 함께 따른다. 아직 받지 않은 메달은 실선 회색 아치와 초록 진행 호(창턱을
 * 빼고 옆면과 꼭대기만 따라간다), 준비 중이면 점선 아치와 점선 납선이다. 단계 장식 색은 꾸밈이고 상태는 윤곽 모양이
 * 나른다. 36px 미만이면 번호 원 없이 그린다.
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
  const frame = { size, state, className, mark: { 'data-medal': `stage-${state}` }, overflow: true }

  if (state === 'earned') {
    const inner = medalPath('stage', medalRim(size))
    const glass = glassTones(stageHex(stageNumber))
    const leadWidth = roundCoord(2.5 * unit)
    return (
      <MedalSvg {...frame}>
        <defs>
          <MaterialDefs
            prefix={prefix}
            materials={['metal', 'glass', 'highlight', 'bevel', 'shadow', 'sweep']}
          />
          <clipPath id={`${prefix}-inner`}>
            <path d={inner} />
          </clipPath>
          <clipPath id={`${prefix}-outer`}>
            <path d={outer} />
          </clipPath>
        </defs>
        <GroundShadow prefix={prefix} />
        <path d={outer} fill={paint(prefix, 'metal')} />
        <RimShade d={outer} unit={unit} />
        <g clipPath={`url(#${prefix}-inner)`}>
          <path d="M0 0H50V44.5L0 50Z" fill={glass.light} />
          <path d="M50 0H100V50L50 44.5Z" fill={glass.soft} />
          <path d="M0 50L50 44.5V74L0 66Z" fill={glass.deep} />
          <path d="M50 44.5L100 50V66L50 74Z" fill={glass.base} />
          <path d="M0 66L50 74V100H0Z" fill={glass.shade} />
          <path d="M50 74L100 66V100H50Z" fill={glass.light} />
          <rect width="100" height="100" fill={paint(prefix, 'glass')} />
          <path
            d="M27 18L36 30M66 56L76 61M24 80L32 88"
            stroke="#fff"
            strokeOpacity={0.35}
            strokeWidth={roundCoord(1.2 * unit)}
            strokeLinecap="round"
          />
          <path
            className={styles.lead}
            d={stageLeadPath(radius, 0, 100, 0, 100)}
            fill="none"
            strokeWidth={leadWidth}
            strokeLinejoin="round"
          />
          <circle
            className={styles.lead}
            cx="50"
            cy="41"
            r={radius}
            fill={glass.pale}
            strokeWidth={leadWidth}
          />
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
        <FaceHighlight prefix={prefix} clipId={`${prefix}-inner`} />
        <FaceBevel prefix={prefix} d={inner} unit={unit} />
        {shine ? <Sweep prefix={prefix} clipId={`${prefix}-outer`} /> : null}
      </MedalSvg>
    )
  }

  if (state === 'notYet') {
    return (
      <MedalSvg {...frame}>
        <g className={styles.muted}>
          <MedalOutline d={outer} unit={unit} />
          <path
            d={stageLeadPath(big ? radius : 0)}
            fill="none"
            stroke="currentColor"
            strokeWidth={roundCoord(1.25 * unit)}
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
              strokeWidth={roundCoord(1.25 * unit)}
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
        <ProgressArc
          d={stageArchProgressPath(-4.2 * unit)}
          unit={unit}
          progress={progress}
          animate={animate}
        />
      </MedalSvg>
    )
  }

  const dash = `${roundCoord(3 * unit)} ${roundCoord(3 * unit)}`
  return (
    <MedalSvg {...frame}>
      <g className={styles.muted}>
        <MedalOutline d={outer} unit={unit} dashed />
        {big ? (
          <>
            <path
              d={stageLeadPath(radius)}
              fill="none"
              stroke="currentColor"
              strokeWidth={roundCoord(unit)}
              strokeOpacity={0.55}
              strokeDasharray={dash}
            />
            <circle
              cx="50"
              cy="41"
              r={radius}
              fill="none"
              stroke="currentColor"
              strokeWidth={roundCoord(unit)}
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
    </MedalSvg>
  )
}

/**
 * 단계 색을 흰색·짙은 색과 섞어 유리 조각 여섯 톤을 만든다(목업 `stained`). 밝은 순서로 `pale`(번호 원) > `light` >
 * `soft` > `base`(단계 색 그대로) > `shade` > `deep`이다. 단계 색이 없으면 옅은 회록이다.
 */
function glassTones(base: string | null) {
  const color = base ?? '#9AA6A0'
  const white = '#ffffff'
  const dark = '#0c0f0e'
  return {
    pale: mix(color, white, 0.62),
    light: mix(color, white, 0.3),
    soft: mix(color, white, 0.1),
    base: color,
    shade: mix(color, dark, 0.2),
    deep: mix(color, dark, 0.38),
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

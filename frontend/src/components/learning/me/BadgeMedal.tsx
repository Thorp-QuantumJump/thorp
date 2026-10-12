import { useId } from 'react'
import { LevelEmblem } from './LevelEmblem'
import { MaterialDefs } from './MaterialDefs'
import { ProgressArc, Sweep, type MedalState } from './MedalParts'
import { paint, svgId, type Material } from './materials'
import type { MedalSpec, SpecialBadge } from './meCopy'
import { medalPath, medalRim, termCardGeometry, type MedalKind } from './medalShapes'
import { StageMedal } from './StageMedal'
import { roundCoord } from './svgCoord'
import styles from './medals.module.css'

/** 메달의 모습. 무엇을 그리는지(갈래·레벨·단계)는 `MedalSpec`이 정한다(`medalSpecOf`). */
interface MedalLook {
  /** 화면 크기(px) */
  size: number
  /** 받음(금속 테두리와 에나멜 면), 아직(실선 회색 실루엣과 초록 진행 호), 준비 중(점선 실루엣) */
  state: MedalState
  /** `notYet`의 진행(0~100) */
  progress?: number
  /** 같은 갈래 안의 사다리 자리(1부터). 글리프가 조금씩 겹을 더한다 */
  tier?: number
  shine?: boolean
  animate?: boolean
  className?: string
}

type BadgeMedalProps = MedalSpec & MedalLook

type FamilyKind = Exclude<MedalKind, 'level' | 'stage'>

/** 글리프 선 색. 받은 메달의 에나멜 위에서 짙은 초록이다. */
const INK = '#102016'

/**
 * 배지 메달 하나(013 design-notes §3.3, 목업 `medalSVG`). 일곱 갈래가 저마다 다른 실루엣(`medalShapes.ts`)이다.
 *
 * <p>받은 배지는 은록 금속 테두리와 갈래별 에나멜 면, 아직 받지 않은 배지는 실선 회색 실루엣과 초록 진행 호, 준비 중
 * 배지는 점선 실루엣이다. 레벨 갈래는 레벨 표장(`LevelEmblem`), 단계 완주 갈래는 단계 완주 메달(`StageMedal`)로
 * 그린다. 실선과 점선은 같은 색·같은 굵기이고 선 모양으로만 다르다.
 *
 * <p>그림이라 보조 기술에 읽히지 않는다(`aria-hidden`). 배지 이름·기준·상태는 옆의 글자가 원본이다.
 */
export function BadgeMedal(props: BadgeMedalProps) {
  const { size, state, progress = 0, shine = false, animate = false, className } = props
  if (props.kind === 'level') {
    return (
      <LevelEmblem
        level={props.level}
        size={size}
        state={state}
        progress={progress}
        shine={shine}
        animate={animate}
        className={className}
      />
    )
  }
  if (props.kind === 'stage') {
    return (
      <StageMedal
        stageNumber={props.stageNumber}
        size={size}
        state={state}
        progress={progress}
        shine={shine}
        animate={animate}
        className={className}
      />
    )
  }
  return <FamilyMedal {...props} kind={props.kind} />
}

function FamilyMedal({
  kind,
  size,
  state,
  progress = 0,
  tier = 1,
  special,
  shine = false,
  animate = false,
  className,
}: MedalLook & { kind: FamilyKind; special?: SpecialBadge }) {
  const prefix = svgId(useId())
  const unit = 100 / size
  const outer = medalPath(kind)
  const classes = [styles.medal, state === 'earned' ? styles.earned : null, className]
    .filter(Boolean)
    .join(' ')

  if (state === 'earned') {
    const rim = medalRim(size)
    const inner = medalPath(kind, rim)
    const enamel = `en-${kind}` as Material
    const card = termCardGeometry(rim)
    return (
      <svg
        className={classes}
        viewBox="0 0 100 100"
        width={size}
        height={size}
        aria-hidden="true"
        focusable="false"
        overflow="visible"
        data-medal={`${kind}-earned`}
      >
        <defs>
          <MaterialDefs
            prefix={prefix}
            materials={[
              kind === 'read' ? 'metal2' : 'metal',
              enamel,
              'hl',
              'pin',
              'shadow',
              'sweep',
            ]}
          />
          <clipPath id={`${prefix}-inner`}>
            <path d={inner} />
          </clipPath>
          <clipPath id={`${prefix}-outer`}>
            <path d={outer} />
          </clipPath>
        </defs>
        <ellipse cx="50" cy="97" rx="31" ry="3.4" fill={paint(prefix, 'shadow')} />
        <path d={outer} fill={paint(prefix, kind === 'read' ? 'metal2' : 'metal')} />
        <path
          d={outer}
          fill="none"
          stroke="#0b0d0c"
          strokeOpacity={0.55}
          strokeWidth={roundCoord(0.8 * unit)}
        />
        <path d={inner} fill={paint(prefix, enamel)} />
        {kind === 'terms' ? (
          <path
            d={`M${roundCoord(card.x1 - card.fold)} ${card.y0}V${roundCoord(card.y0 + card.fold)}H${card.x1}Z`}
            fill="#EAD9F7"
            stroke={INK}
            strokeOpacity={0.5}
            strokeWidth={roundCoord(0.9 * unit)}
            strokeLinejoin="round"
          />
        ) : null}
        <g clipPath={`url(#${prefix}-inner)`} style={{ color: INK }}>
          <Glyph kind={kind} tier={tier} special={special} strokeWidth={2 * unit} tone="earned" />
        </g>
        <g clipPath={`url(#${prefix}-inner)`}>
          <path d="M0 0H100V25L0 44Z" fill={paint(prefix, 'hl')} />
        </g>
        <path
          d={inner}
          fill="none"
          stroke={paint(prefix, 'pin')}
          strokeWidth={roundCoord(1.25 * unit)}
        />
        {shine ? <Sweep prefix={prefix} clipId={`${prefix}-outer`} /> : null}
      </svg>
    )
  }

  if (state === 'notYet') {
    const card = termCardGeometry(0)
    return (
      <svg
        className={classes}
        viewBox="0 0 100 100"
        width={size}
        height={size}
        aria-hidden="true"
        focusable="false"
        overflow="visible"
        data-medal={`${kind}-notYet`}
      >
        <g className={styles.muted}>
          <path className={styles.outline} d={outer} strokeWidth={roundCoord(1.5 * unit)} />
          {kind === 'terms' ? (
            <path
              d={`M${roundCoord(card.x1 - card.fold)} ${card.y0}V${roundCoord(card.y0 + card.fold)}H${card.x1}`}
              fill="none"
              stroke="currentColor"
              strokeWidth={roundCoord(1.25 * unit)}
              strokeLinejoin="round"
            />
          ) : null}
          <Glyph
            kind={kind}
            tier={tier}
            special={special}
            strokeWidth={1.25 * unit}
            tone="notYet"
          />
        </g>
        <ProgressArc
          d={medalPath(kind, -4.2 * unit)}
          unit={unit}
          progress={progress}
          animate={animate}
        />
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
      overflow="visible"
      data-medal={`${kind}-preparing`}
    >
      <path
        className={styles.outline}
        d={outer}
        strokeWidth={roundCoord(1.5 * unit)}
        strokeDasharray={`${roundCoord(4 * unit)} ${roundCoord(3 * unit)}`}
      />
    </svg>
  )
}

/**
 * 갈래의 글리프(목업 `bglyph`). 색은 감싼 요소의 `color`를 따른다. 읽은 글은 펼친 책(사다리 자리마다 쪽이 한 겹 늘고),
 * 첫 답 정답은 말풍선 속 체크(둘째 자리부터 체크 둘), 모두 맞힘은 체크 목록 종이, 익힌 용어는 형광펜 친 낱말 줄,
 * 특별은 끈기의 되돌아 도는 화살표와 탐험의 나침반이다. 어느 특별 배지인지 모르면 글리프를 두지 않는다.
 */
function Glyph({
  kind,
  tier,
  special,
  strokeWidth,
  tone,
}: {
  kind: FamilyKind
  tier: number
  special?: SpecialBadge
  strokeWidth: number
  tone: 'earned' | 'notYet'
}) {
  const line = {
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: roundCoord(strokeWidth),
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  }
  switch (kind) {
    case 'read': {
      const pages = []
      for (let j = 1; j < Math.min(tier, 3); j += 1) {
        const y = 2.9 * j
        pages.push(
          <path
            key={j}
            {...line}
            d={`M31 ${roundCoord(61 + y)}C37 ${roundCoord(59 + y)} 44 ${roundCoord(59.5 + y)} 50 ${roundCoord(63.5 + y)}C56 ${roundCoord(59.5 + y)} 63 ${roundCoord(59 + y)} 69 ${roundCoord(61 + y)}`}
          />,
        )
      }
      return (
        <>
          <path
            {...line}
            d="M50 39C44 34.5 37 34 31 36V61C37 59 44 59.5 50 63.5C56 59.5 63 59 69 61V36C63 34 56 34.5 50 39ZM50 39V63.5"
          />
          {pages}
        </>
      )
    }
    case 'first':
      return (
        <>
          <path
            {...line}
            d="M37 34H63Q70 34 70 41V53Q70 60 63 60H47L39 67V60H37Q30 60 30 53V41Q30 34 37 34Z"
          />
          {tier <= 1 ? (
            <path {...line} d="M41.5 47.5L47.5 53L58.5 41.5" />
          ) : (
            <path {...line} d="M34.5 47.5L39 52L47 43.5M51.5 47.5L56 52L64 43.5" />
          )}
        </>
      )
    case 'all':
      return (
        <>
          {tier >= 2 ? <path {...line} d="M41 26H62Q68 26 68 32V64" opacity={0.7} /> : null}
          <rect {...line} x="35" y="31" width="28" height="40" rx="4" />
          <path
            {...line}
            d="M39.5 41l2.6 2.6 4.4-4.6M39.5 51l2.6 2.6 4.4-4.6M39.5 61l2.6 2.6 4.4-4.6M51 41h8M51 51h8M51 61h8"
          />
        </>
      )
    case 'terms':
      return (
        <>
          <rect
            x="27"
            y="37"
            width="36"
            height="9"
            rx="2"
            fill="currentColor"
            opacity={tone === 'earned' ? 0.26 : 0.3}
          />
          <path
            fill="none"
            stroke="currentColor"
            strokeWidth={roundCoord(strokeWidth * 1.5)}
            strokeLinecap="round"
            d="M31 38H56"
          />
          <path {...line} d="M31 53H69M31 63H61" />
        </>
      )
    case 'special':
      if (special === 'exploration') {
        return (
          <>
            <circle {...line} cx="50" cy="50" r="15" />
            <path {...line} d="M50 37L54 50L50 63L46 50Z" />
            <path d="M50 37L54 50H46Z" fill="currentColor" />
          </>
        )
      }
      if (special === 'persistence') {
        return (
          <>
            <path {...line} d="M60.6 41.4A15 15 0 1 0 64.9 53.3" />
            <path {...line} d="M60.8 57L64.9 52.3L69.2 56.6" />
          </>
        )
      }
      return null
  }
}

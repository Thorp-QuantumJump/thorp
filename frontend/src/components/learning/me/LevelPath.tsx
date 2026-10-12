import { useId, type CSSProperties } from 'react'
import type { LevelView } from '@/types/learningMe'
import { nextLevelReachable, unreachableLevelSentence } from './meCopy'
import { MaterialDefs } from './MaterialDefs'
import { paint, svgId } from './materials'
import { roundCoord } from './svgCoord'
import styles from './LevelPath.module.css'

type Milestone = LevelView['milestones'][number]

/** 보이는 가장 높은 레벨의 문턱이 길의 80% 자리에 온다. 그 뒤 20%는 점선이다(목업 `levelCard`). */
const VISIBLE_SHARE = 0.8

/**
 * 이정표 글자('레벨 n'과 문턱) 사이의 가장 작은 간격(길 폭의 %). 이보다 가까운 이정표는 깃발만 두고 글자를 뺀다. 문턱이
 * 앞쪽에 몰리면(여섯 단계를 모두 공개해 0·100·300이 0~3,500 길에 놓일 때) 글자가 겹치기 때문이다.
 */
const LABEL_GAP = 12

/** 글자를 붙일 이정표. 가장 높은 이정표부터 왼쪽으로 가며 간격이 넉넉한 것만 고른다. */
function labelledLevels(positions: readonly { level: number; at: number }[]): ReadonlySet<number> {
  const chosen = new Set<number>()
  let lastAt = Number.POSITIVE_INFINITY
  for (let i = positions.length - 1; i >= 0; i -= 1) {
    const { level, at } = positions[i]
    if (lastAt - at >= LABEL_GAP) {
      chosen.add(level)
      lastAt = at
    }
  }
  return chosen
}

function thresholdOf(milestones: readonly Milestone[], level: number): number | null {
  return milestones.find((item) => item.level === level)?.threshold ?? null
}

/**
 * 레벨 길(013 design-notes §3.3, FR-029). 레벨 문턱마다 이정표를 세운 가로 길이다.
 *
 * <p>지금 공개된 단계로 닿을 수 있는 가장 높은 레벨(`visibleMax`)까지는 실선이고, 그 뒤는 점선으로 바뀌며 아래에
 * '레벨 N부터는 단계가(글이) 더 공개된 뒤 닿을 수 있습니다.' 문장이 붙는다. 길 끝에 '도착' 표시는 없다. 지나온
 * 이정표는 표장과 같은 초록 금속, 아직인 이정표는 회색 윤곽이다.
 *
 * <p>길은 그림이라 보조 기술에 읽히지 않는다. 경험치와 다음 레벨까지는 레벨 카드의 글자가, 닿을 수 없는 레벨은 아래
 * 문장이 원본이다.
 */
export function LevelPath({ level }: { level: LevelView }) {
  const prefix = svgId(useId())
  const visible = level.milestones.filter((item) => item.level <= level.visibleMax)
  const top =
    thresholdOf(level.milestones, level.visibleMax) ||
    thresholdOf(level.milestones, level.unreachableFrom) ||
    100
  const scale = top / VISIBLE_SHARE
  const percentAt = (value: number) => roundCoord((Math.min(value, scale) / scale) * 100)
  const at = (value: number) => `${percentAt(value)}%`
  const sentence = unreachableLevelSentence(level)
  const labelled = labelledLevels(
    visible.map((item) => ({ level: item.level, at: percentAt(item.threshold) })),
  )

  return (
    <div className={styles.full}>
      <div className={styles.mile} aria-hidden="true">
        <FlagDefs prefix={prefix} />
        <div className={styles.track}>
          <i className={styles.done} style={{ left: 0, width: at(level.exp) }} />
          <i
            className={styles.todo}
            style={{ left: at(level.exp), width: `max(0%, calc(${at(top)} - ${at(level.exp)}))` }}
          />
          <i className={styles.preparing} style={{ left: `${VISIBLE_SHARE * 100}%`, right: 0 }} />
        </div>
        {visible.map((item, index) => (
          <span
            key={item.level}
            className={index === 0 ? `${styles.flag} ${styles.firstFlag}` : styles.flag}
            style={{ left: at(item.threshold) }}
          >
            <Flag prefix={prefix} reached={level.exp >= item.threshold} />
            {labelled.has(item.level) ? (
              <span className={styles.flagLabel}>
                레벨 {item.level}
                <small>{item.threshold.toLocaleString('ko-KR')}</small>
              </span>
            ) : null}
          </span>
        ))}
        <span className={styles.dot} style={{ left: at(level.exp) }}>
          <span className={styles.bubble}>{level.exp.toLocaleString('ko-KR')}</span>
        </span>
      </div>
      {sentence ? <p className={styles.after}>{sentence}</p> : null}
    </div>
  )
}

/** 이정표 깃발이 쓰는 초록 금속. 크기 없는 그림에 한 번 두고 깃발들이 가리킨다. */
function FlagDefs({ prefix }: { prefix: string }) {
  return (
    <svg className={styles.defs} width="0" height="0" focusable="false">
      <defs>
        <MaterialDefs prefix={prefix} materials={['gmetal', 'glow']} />
      </defs>
    </svg>
  )
}

function Flag({ prefix, reached }: { prefix: string; reached: boolean }) {
  return (
    <svg
      className={reached ? `${styles.flagArt} ${styles.reached}` : styles.flagArt}
      viewBox="0 0 20 28"
      focusable="false"
    >
      <path className={styles.pole} d="M1 1 V27" strokeWidth="2" strokeLinecap="round" />
      <path
        className={styles.cloth}
        d="M2 1.5 L14 6 L2 10.5 Z"
        fill={reached ? paint(prefix, 'gmetal') : undefined}
        strokeWidth="1.3"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/**
 * 요약 패널의 작은 레벨 길(목업 `miniMile`). 지금 레벨의 이정표에서 다음 레벨의 이정표까지이고 점이 지금 경험치에 있다.
 * 다음 레벨이 지금 공개된 단계로 닿을 수 없는 레벨이면 그 구간은 점선이다.
 */
export function LevelPathMini({ level }: { level: LevelView }) {
  const prefix = svgId(useId())
  const x0 = 8
  const x1 = 200
  const span = level.nextThreshold - level.currentThreshold
  const fraction =
    span > 0 ? Math.max(0, Math.min(1, (level.exp - level.currentThreshold) / span)) : 0
  const x = roundCoord(x0 + (x1 - x0) * fraction)
  const blocked = !nextLevelReachable(level)
  return (
    <svg className={styles.mini} viewBox="0 0 220 30" aria-hidden="true" focusable="false">
      <defs>
        <MaterialDefs prefix={prefix} materials={['gmetal', 'glow']} />
      </defs>
      <path
        className={styles.todoLine}
        d={`M${x0} 22 H${x1}`}
        strokeWidth="3"
        strokeLinecap="round"
        strokeDasharray={blocked ? '6 6' : undefined}
      />
      <path
        className={styles.doneLine}
        d={`M${x0} 22 H${x}`}
        strokeWidth="4"
        strokeLinecap="round"
      />
      <g className={styles.reached}>
        <path className={styles.pole} d={`M${x0} 22 V5`} strokeWidth="2" strokeLinecap="round" />
        <path
          className={styles.cloth}
          d={`M${x0 + 1} 5 L${x0 + 11} 8.8 L${x0 + 1} 12.6 Z`}
          fill={paint(prefix, 'gmetal')}
          strokeWidth="0.8"
          strokeLinejoin="round"
        />
      </g>
      <g>
        <path className={styles.pole} d={`M${x1} 22 V5`} strokeWidth="2" strokeLinecap="round" />
        <path
          className={styles.cloth}
          d={`M${x1 + 1} 5 L${x1 + 11} 8.8 L${x1 + 1} 12.6 Z`}
          strokeWidth="1.3"
          strokeLinejoin="round"
        />
      </g>
      <circle cx={x} cy="22" r="10" fill={paint(prefix, 'glow')} opacity={0.75} />
      <circle className={styles.dotFill} cx={x} cy="22" r="5" />
      <circle cx={x - 1.4} cy="20.6" r="1.6" fill="#e9fff2" opacity={0.8} />
    </svg>
  )
}

interface LevelPathMoveProps {
  level: LevelView
  /** 이 화면에서 받은 경험치를 더하기 전의 경험치. 점이 여기서 지금 경험치로 옮겨 간다 */
  expBefore: number
  /**
   * 움직임을 준다면, 받은 것이 생긴 뒤 이 길이 그려지기까지 걸린 시간(ms). 움직임의 시작을 그만큼 당겨 완료 카드의 다른
   * 움직임과 한 장면으로 끝낸다. `null`이면 움직이지 않는다
   */
  lateMs: number | null
}

/**
 * 완료 카드의 레벨 길(목업 `mm2`). 받기 전 레벨의 이정표부터 다음 레벨의 이정표까지이고, 받은 경험치만큼 점이 앞으로
 * 옮겨 간다. 새로 지난 이정표는 회색에서 초록 금속으로 바뀐다. 움직임은 줄이기 설정이 아닐 때만 있고, 받은 것이 생긴
 * 순간부터 세어 950ms 안에 끝난다(design-notes §5). 길은 '나의 학습'을 받은 뒤에 그려지므로 늦게 그려진 만큼(`--late`)
 * 움직임을 당긴다.
 */
export function LevelPathMove({ level, expBefore, lateMs }: LevelPathMoveProps) {
  const moving = lateMs !== null
  const prefix = svgId(useId())
  const levelBefore = levelAt(level.milestones, Math.min(expBefore, level.exp))
  const end = Math.min(level.current + 1, level.unreachableFrom)
  const flags = level.milestones.filter((item) => item.level >= levelBefore && item.level <= end)
  // 지금 공개된 단계로 닿을 수 없는 레벨은 길 끝의 점선으로만 그리고 이정표·글자를 두지 않는다(FR-029). 길의 눈금은 그
  // 레벨의 문턱까지 잡아 지금 경험치의 자리를 보인다.
  const shownFlags = flags.filter((item) => item.level <= level.visibleMax)
  const startThreshold = flags[0]?.threshold ?? 0
  const endThreshold = flags[flags.length - 1]?.threshold ?? level.nextThreshold
  const left = 10
  const right = 230
  const span = Math.max(1, endThreshold - startThreshold)
  const x = (value: number) =>
    roundCoord(
      left +
        ((Math.max(startThreshold, Math.min(value, endThreshold)) - startThreshold) / span) *
          (right - left),
    )
  const from = x(expBefore)
  const to = x(level.exp)
  const blockedFrom =
    level.visibleMax < end
      ? x(thresholdOf(level.milestones, level.visibleMax) ?? endThreshold)
      : null
  const style = {
    ['--from' as string]: `${roundCoord(from - to)}px`,
    ['--sx' as string]: String(roundCoord((from - left) / Math.max(1, to - left))),
    ['--late' as string]: `${Math.max(0, Math.round(lateMs ?? 0))}ms`,
  } as CSSProperties

  return (
    <svg
      className={moving ? `${styles.move} ${styles.moving}` : styles.move}
      style={style}
      viewBox="0 0 250 54"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <MaterialDefs prefix={prefix} materials={['gmetal', 'glow']} />
      </defs>
      <path
        className={styles.todoLine}
        d={`M${left} 24 H${blockedFrom ?? right}`}
        strokeWidth="3"
        strokeLinecap="round"
      />
      {blockedFrom !== null ? (
        <path
          className={styles.todoLine}
          d={`M${blockedFrom} 24 H${right}`}
          strokeWidth="3"
          strokeLinecap="round"
          strokeDasharray="6 6"
        />
      ) : null}
      <path
        className={`${styles.doneLine} ${styles.bar}`}
        d={`M${left} 24 H${to}`}
        strokeWidth="4"
        strokeLinecap="round"
      />
      {shownFlags.map((item, index) => {
        const reached = level.exp >= item.threshold
        const fresh = moving && reached && item.threshold > expBefore
        const fx = x(item.threshold)
        const last = item.level === end
        const anchor = index === 0 ? 'start' : last ? 'end' : 'middle'
        const tx = index === 0 ? fx - 2 : last ? fx + 10 : fx
        return (
          <g
            key={item.level}
            className={
              [reached ? styles.reached : null, fresh ? styles.fresh : null]
                .filter(Boolean)
                .join(' ') || undefined
            }
          >
            <path
              className={styles.pole}
              d={`M${fx} 24 V6`}
              strokeWidth="2"
              strokeLinecap="round"
            />
            <path
              className={styles.cloth}
              d={`M${fx + 1} 6.5 L${fx + 11} 10.3 L${fx + 1} 14.1 Z`}
              fill={reached ? paint(prefix, 'gmetal') : undefined}
              strokeWidth="1.3"
              strokeLinejoin="round"
            />
            <text
              className={styles.moveLabel}
              x={tx}
              y="47"
              textAnchor={anchor}
              fontSize="15.5"
              fontWeight={650}
            >
              레벨 {item.level}
            </text>
          </g>
        )
      })}
      <g className={styles.mover}>
        <circle cx={to} cy="24" r="10" fill={paint(prefix, 'glow')} opacity={0.75} />
        <circle className={styles.dotFill} cx={to} cy="24" r="5" />
        <circle cx={to - 1.4} cy="22.6" r="1.6" fill="#e9fff2" opacity={0.85} />
      </g>
    </svg>
  )
}

/** 경험치가 닿은 가장 높은 레벨. */
function levelAt(milestones: readonly Milestone[], exp: number): number {
  let reached = milestones[0]?.level ?? 1
  for (const item of milestones) if (exp >= item.threshold) reached = item.level
  return reached
}

import { useId } from 'react'
import { stageColor } from '../stageColors'
import styles from './ArticleStamp.module.css'

/**
 * 글 도장의 다섯 모양(013 design-notes §4 상태 문법).
 *
 * - `done`: 초록 잉크로 채워 찍은 도장과 체크(완료)
 * - `reading`: 실선 회색 윤곽과 초록 진행 호(읽는 중)
 * - `unread`: 실선 회색 윤곽, 속 비움(안 읽음)
 * - `preparing`: 점선 윤곽, 속 비움(준비 중)
 * - `neutral`: 윤곽선 없이 채운 원판과 번호만. 비로그인이거나 기록이 없을 때다. 실선 회색 윤곽은 '아직 안 함'으로
 *   읽히므로 쓰지 않는다(§3.5)
 */
export type ArticleStampState = 'done' | 'reading' | 'unread' | 'preparing' | 'neutral'

interface ArticleStampProps {
  state: ArticleStampState
  /** 화면 크기(px). 같은 그림을 줄이면 상태를 나르는 선이 가늘어지므로 자리마다 크기를 정해 그린다 */
  size: number
  /** 도장 안 글자(예: `1-4`). 없으면 번호 없이 그린다 */
  label?: string
  /** `reading`의 읽은 비율(0~100) */
  percent?: number
  /** 안쪽 고리의 옅은 단계 색(꾸밈). 상태를 나타내지 않는다 */
  stageNumber?: number
  /** 도장마다 조금씩 다른 기울기의 순서 */
  tilt?: number
  /** 아래에 까는 종이 원판 */
  disc?: boolean
  /** `done`에서 번호 대신 큰 체크만 둔다. 번호가 바로 옆 카드에 있는 자리다 */
  checkOnly?: boolean
  /** `reading` 진행 호 끝의 빛 점. 지금 이어 읽을 글 하나에만 준다(§2 '지금 할 일이 빛난다') */
  tip?: boolean
  /**
   * `done`에서 방금 찍힌 도장. 속을 진하게 채우고 더 기울이며 바깥에 옅은 후광 한 겹을 둔다. 완료 카드의 마지막
   * 칸(B13 T193)이 쓴다. 그림자 번짐은 쓰지 않는다
   */
  fresh?: boolean
  className?: string
}

const INK = '#2FDC7A'
const OUTLINE = '#8B928E'
const TILTS = [-4, 3, -2, 5]

function round(value: number): number {
  return Math.round(value * 100) / 100
}

/**
 * 글 도장 하나(44×44 viewBox). 커리큘럼 보기의 글 노드, 단계 머리의 도장 줄(B13), 완료 카드(B13)가 같은 도장을
 * 쓴다.
 *
 * <p><b>그림은 보조 기술에 읽히지 않는다(`aria-hidden`).</b> 상태는 옆의 글자('완료'·'62% 읽는 중'·'안 읽음')가
 * 원본이다(design-notes §6). 그라디언트·필터 식별자는 `useId`로 만들어 같은 도장이 여러 번 그려져도 겹치지 않는다.
 */
export function ArticleStamp({
  state,
  size,
  label,
  percent = 0,
  stageNumber,
  tilt = 0,
  disc = false,
  checkOnly = false,
  tip = false,
  fresh = false,
  className,
}: ArticleStampProps) {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const u = 44 / size
  const fontSize = round(Math.max(11, 12 * u))
  const classes = [styles.stamp, className].filter(Boolean).join(' ')

  const number = (fill: string) =>
    label ? (
      <text x="22" y="22.5" textAnchor="middle" dominantBaseline="central" fontSize={fontSize} fontWeight={700} fill={fill}>
        {label}
      </text>
    ) : null

  // 원판 색: 중립은 조금 밝은 원판, 준비 중은 바탕색(채운 원으로 보이지 않게, 목업 marker 'pend'), 나머지는 면 색이다.
  const paperFill = state === 'neutral' ? '#1F2321' : state === 'preparing' ? '#0D0F0E' : '#161918'
  const paper = disc ? <circle cx="22" cy="22" r="21.3" fill={paperFill} /> : null

  if (state === 'neutral') {
    return (
      <svg className={classes} viewBox="0 0 44 44" width={size} height={size} aria-hidden="true" focusable="false" data-stamp="neutral">
        {paper}
        <circle cx="22" cy="22" r="16.6" fill="none" stroke="#000" strokeOpacity={0.3} strokeWidth={round(1.3 * u)} />
        {number('#B8BFBB')}
      </svg>
    )
  }

  if (state === 'done') {
    const markSize = round(Math.max(10, 12 * u))
    const mark =
      checkOnly || !label ? (
        <path d="M14 22.5l5 4.8 11-10.6" fill="none" stroke={INK} strokeWidth={round(Math.max(2.6, 2 * u))} strokeLinecap="round" strokeLinejoin="round" />
      ) : (
        <>
          <text x="22" y={round(16 + (markSize - 10) * 0.3)} textAnchor="middle" dominantBaseline="central" fontSize={markSize} fontWeight={800} fill={INK}>
            {label}
          </text>
          <path d="M18.4 29.6l2.7 2.5 5.3-5.1" fill="none" stroke={INK} strokeWidth={round(Math.max(2, 1.6 * u))} strokeLinecap="round" strokeLinejoin="round" />
        </>
      )
    return (
      <svg
        className={classes}
        viewBox="0 0 44 44"
        width={size}
        height={size}
        aria-hidden="true"
        focusable="false"
        overflow="visible"
        data-stamp="done"
        data-fresh={fresh ? '' : undefined}
      >
        <defs>
          {/* 도장 잉크가 종이에 번진 가장자리. 그림자 번짐은 쓰지 않는다 */}
          <filter id={`${id}-ink`} x="-15%" y="-15%" width="130%" height="130%">
            <feTurbulence type="fractalNoise" baseFrequency="1.1" numOctaves={2} seed={7} result="noise" />
            <feDisplacementMap in="SourceGraphic" in2="noise" scale={0.9} xChannelSelector="R" yChannelSelector="G" result="moved" />
            <feColorMatrix in="noise" type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 -1.3 1.62" result="mask" />
            <feComposite in="moved" in2="mask" operator="in" />
          </filter>
        </defs>
        {paper}
        {fresh ? (
          <circle cx="22" cy="22" r="23.5" fill="none" stroke={INK} strokeOpacity={0.3} strokeWidth={round(3 * u)} />
        ) : null}
        <g transform={`rotate(${fresh ? -8 : TILTS[tilt % TILTS.length]} 22 22)`} filter={`url(#${id}-ink)`}>
          <circle
            className={styles.ink}
            cx="22"
            cy="22"
            r="20"
            fill={INK}
            fillOpacity={fresh ? 0.45 : 0.2}
            stroke={INK}
            strokeWidth={round((fresh ? 2.6 : 2.2) * u)}
          />
          <circle className={styles.ink} cx="22" cy="22" r="16.6" fill="none" stroke={INK} strokeWidth={round(u)} />
          {mark}
        </g>
      </svg>
    )
  }

  const preparing = state === 'preparing'
  const dash = preparing ? `${round(4 * u)} ${round(3 * u)}` : undefined
  const tipAngle = ((-90 + percent * 3.6) * Math.PI) / 180

  return (
    <svg className={classes} viewBox="0 0 44 44" width={size} height={size} aria-hidden="true" focusable="false" overflow="visible" data-stamp={state}>
      {paper}
      <circle className={styles.outline} cx="22" cy="22" r="20" fill="none" stroke={OUTLINE} strokeWidth={round(1.5 * u)} strokeDasharray={dash} />
      {preparing ? (
        <circle className={styles.outline} cx="22" cy="22" r="16.6" fill="none" stroke={OUTLINE} strokeOpacity={0.55} strokeWidth={round(u)} strokeDasharray={`${round(3 * u)} ${round(3 * u)}`} />
      ) : (
        <circle cx="22" cy="22" r="16.6" fill="none" stroke={stageNumber ? stageColor(stageNumber) : OUTLINE} strokeOpacity={0.28} strokeWidth={round(u)} />
      )}
      {number(preparing ? OUTLINE : '#B8BFBB')}
      {state === 'reading' ? (
        <>
          <circle
            className={styles.progress}
            cx="22"
            cy="22"
            r="20"
            fill="none"
            stroke={INK}
            strokeWidth={round(3 * u)}
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={`${percent} 100`}
            transform="rotate(-90 22 22)"
          />
          {tip ? (
            <g className={styles.tip} data-tip="">
              <defs>
                {/* 빛 점의 번짐. 한 화면에서 빛나는 곳 두 곳 가운데 하나다(design-notes §2) */}
                <filter id={`${id}-soft`} x="-1" y="-1" width="3" height="3">
                  <feGaussianBlur stdDeviation="2.4" />
                </filter>
                <filter id={`${id}-glow`} x="-4" y="-4" width="9" height="9">
                  <feDropShadow dx="0" dy="0" stdDeviation="4" floodColor={INK} floodOpacity="0.8" />
                </filter>
              </defs>
              <circle
                cx={round(22 + 20 * Math.cos(tipAngle))}
                cy={round(22 + 20 * Math.sin(tipAngle))}
                r={round(7 * u)}
                fill={INK}
                opacity={0.3}
                filter={`url(#${id}-soft)`}
              />
              <circle
                cx={round(22 + 20 * Math.cos(tipAngle))}
                cy={round(22 + 20 * Math.sin(tipAngle))}
                r={round(3.4 * u)}
                fill="#EAFFF2"
                filter={`url(#${id}-glow)`}
              />
            </g>
          ) : null}
        </>
      ) : null}
    </svg>
  )
}

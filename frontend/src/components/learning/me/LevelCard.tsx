import { useId, type ReactNode } from 'react'
import type { LearnerOverview } from '@/types/learningMe'
import { LevelEmblem } from './LevelEmblem'
import { LevelPath } from './LevelPath'
import { medalPath, termCardGeometry, type MedalKind } from './medalShapes'
import { NewPill } from './NewPill'
import styles from './LevelCard.module.css'

/**
 * '나의 학습' 화면 맨 위의 레벨 카드와 숫자 줄(013 FR-020·FR-029, design-notes §3.3, 목업 `levelCard`).
 *
 * <p>레벨 표장, '레벨 n'과 7일 안에 닿은 레벨의 '새', 경험치와 다음 레벨까지 남은 경험치, 레벨 길과 닿을 수 없는 레벨의
 * 문장을 두고, 아래 숫자 줄에 읽은 글·익힌 용어·받은 배지를 분모 없이 수로만 보인다. 읽은 글과 익힌 용어 아래에는 지금
 * 공개된 수를 분모가 아닌 별도 문장으로 덧붙인다. 레벨에 이름을 붙이지 않고 경험치 내역을 보이지 않는다.
 */
export function LevelCard({ overview }: { overview: LearnerOverview }) {
  const titleId = useId()
  const { level, counts } = overview
  return (
    <section className={styles.card} aria-labelledby={titleId}>
      <div className={styles.head}>
        <span className={styles.emblem} aria-hidden="true">
          <LevelEmblem level={level.current} size={112} shine={level.isNew} />
        </span>
        <div className={styles.top}>
          <h2 id={titleId} className={styles.level}>
            레벨 {level.current}
            {level.isNew ? <NewPill spoken="새로 닿은 레벨" /> : null}
          </h2>
          <p className={styles.sub}>
            경험치 <b>{level.exp.toLocaleString('ko-KR')}</b> · 다음 레벨까지{' '}
            <b>{level.remainingToNext.toLocaleString('ko-KR')}</b>
          </p>
        </div>
      </div>
      <LevelPath level={level} />
      <div className={styles.numbers}>
        <Count icon="read" caption="읽은 글" value={counts.completedArticles} unit="편">
          지금 공개된 글은 {counts.publishedArticles}편입니다.
        </Count>
        <Count icon="terms" caption="익힌 용어" value={counts.masteredTerms} unit="개">
          지금 공개된 용어는 {counts.publishedTerms}개입니다.
        </Count>
        <Count icon="badges" caption="받은 배지" value={counts.badges} unit="개">
          기준은 모두 아래에 있습니다.
        </Count>
      </div>
    </section>
  )
}

function Count({
  icon,
  caption,
  value,
  unit,
  children,
}: {
  icon: 'read' | 'terms' | 'badges'
  caption: string
  value: number
  unit: string
  children: ReactNode
}) {
  return (
    <div className={styles.count}>
      <CountIcon kind={icon} />
      <div>
        <span className={styles.caption}>{caption}</span>
        <b className={styles.value}>
          {value}
          <small>{unit}</small>
        </b>
        <span className={styles.extra}>{children}</span>
      </div>
    </div>
  )
}

/**
 * 숫자 칸의 아이콘(목업 `numIcon`). 그 갈래 메달의 실루엣을 초록 선으로 그린다. 받은 배지는 책갈피·로제트·육각
 * 실루엣을 겹쳐 놓은 묶음이다. 그림이라 보조 기술에 읽히지 않는다.
 */
function CountIcon({ kind }: { kind: 'read' | 'terms' | 'badges' }) {
  if (kind === 'badges') {
    const one = (shape: MedalKind, x: number, y: number, scale: number, opacity: number) => (
      <path
        key={shape}
        className={styles.iconShape}
        d={medalPath(shape)}
        transform={`translate(${x} ${y}) scale(${scale}) translate(-50 -50)`}
        strokeWidth={round(4.5 / scale)}
        strokeLinejoin="round"
        opacity={opacity}
      />
    )
    return (
      <svg className={styles.icon} viewBox="0 0 100 100" aria-hidden="true" focusable="false">
        {one('read', 30, 42, 0.56, 0.6)}
        {one('all', 70, 42, 0.56, 0.6)}
        {one('first', 50, 60, 0.66, 1)}
      </svg>
    )
  }
  const card = termCardGeometry(0)
  return (
    <svg className={styles.icon} viewBox="0 0 100 100" aria-hidden="true" focusable="false">
      <path
        className={`${styles.iconShape} ${styles.iconFill}`}
        d={medalPath(kind)}
        strokeWidth="4.5"
        strokeLinejoin="round"
      />
      {kind === 'read' ? (
        <path
          className={styles.iconLine}
          d="M50 39C44 34.5 37 34 31 36V61C37 59 44 59.5 50 63.5C56 59.5 63 59 69 61V36C63 34 56 34.5 50 39ZM50 39V63.5"
          strokeWidth="4"
        />
      ) : (
        <>
          <path
            className={styles.iconLine}
            d={`M${card.x1 - card.fold} ${card.y0}V${card.y0 + card.fold}H${card.x1}`}
            strokeWidth="4"
          />
          <rect className={styles.iconMark} x="27" y="37" width="36" height="9" rx="2" />
          <path className={styles.iconLine} d="M31 38H56" strokeWidth="6" />
          <path className={styles.iconLine} d="M31 53H69M31 63H61" strokeWidth="4" />
        </>
      )}
    </svg>
  )
}

function round(value: number): number {
  return Math.round(value * 100) / 100
}

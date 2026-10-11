import { useId, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import type { StageView } from '@/types/learning'
import { ArticleNode } from './ArticleNode'
import { stageMarker, type ArticleProgressMap, type RoadKind } from './articleProgress'
import { PreviousEditionCards } from './PreviousEditionCards'
import { ReleaseBoundary } from './ReleaseBoundary'
import { readRoadPoints, roadPointProps, stageAnchorProps } from './stageAnchors'
import { StageBand } from './StageBand'
import { isReleased } from './stages'
import styles from './JourneyMap.module.css'

interface JourneyMapProps {
  stages: readonly StageView[]
  /** 글별 나의 학습 상태(B13). 없으면 노드·표식·길이 모두 중립 모양이다 */
  progress?: ArticleProgressMap
  /** 단계 띠의 진행 자리(B13의 글 도장 줄과 단계 완주 메달). 없으면 글 수와 예상 읽기 시간이다 */
  renderStageProgress?: (stage: StageView) => ReactNode
  /** 지금 이어 읽을 글(나의 학습의 '이어서 읽기'). 그 글의 카드가 빛난다 */
  currentArticleId?: string | null
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

/**
 * 커리큘럼 보기: 여섯 단계의 여정 지도(013 FR-023, design-notes §3.1).
 *
 * <p>공개된 단계마다 지역 띠와 굽이치는 길 위의 글 노드를 두고, 공개된 단계가 끝나는 자리에 공개 경계 한 줄을 둔 뒤
 * 공개하지 않은 단계를 점선 '설계 도면'으로 그린다. 공개했지만 글이 0편인 단계는 공개 경계 앞 제자리에서 같은 점선
 * 모습이다. 길은 읽는 순서를 제안할 뿐이고 어느 글도 잠그지 않는다.
 *
 * <p><b>길은 노드 위치를 재서 그린다.</b> 노드·띠·경계에 붙은 길 점(`stageAnchors`의 `roadPointProps`)을 문서
 * 순서로 이어 SVG 곡선을 만들고, 크기가 바뀌면(`ResizeObserver`) 다시 잰다. 1280px에서는 노드가 가로로 비켜 놓여 완만한
 * S자가 되고, 720px 미만에서는 노드가 한 줄로 서서 곧은 세로 길이 된다. 길을 그리는 700ms 움직임은
 * `prefers-reduced-motion: no-preference`에서만 있다. 길은 그림이라 보조 기술에 읽히지 않는다.
 */
export function JourneyMap({ stages, progress, renderStageProgress, currentArticleId, linkState }: JourneyMapProps) {
  const idPrefix = useId()
  const mapRef = useRef<HTMLDivElement>(null)
  const road = useMeasuredRoad(mapRef, stages, progress)

  const released = stages.filter(isReleased)
  const preparing = stages.filter((stage) => !isReleased(stage))

  const region = (stage: StageView) => {
    const headingId = `${idPrefix}-stage-${stage.number}`
    if (stage.status !== 'OPEN') {
      return (
        <section
          key={stage.stageKey}
          className={`${styles.region} ${styles.pendingRegion}`}
          aria-labelledby={headingId}
          {...stageAnchorProps(stage.number)}
        >
          <StageBand stage={stage} headingId={headingId} />
          <PreviousEditionCards articles={stage.previousEditionArticles} linkState={linkState} />
        </section>
      )
    }
    const { marker, road: bandRoad } = stageMarker(stage, progress)
    return (
      <section key={stage.stageKey} className={styles.region} aria-labelledby={headingId} {...stageAnchorProps(stage.number)}>
        <StageBand
          stage={stage}
          headingId={headingId}
          // 길은 지도의 첫 구역에서 출발한다. 첫 공개 단계가 글이 0편인 단계라 점선 표식이 먼저 오면, 이 띠는 그 표식에서
          // 들어오는 구간의 도착점이므로 출발점으로 두지 않는다.
          first={stage === released[0]}
          markerState={marker}
          road={bandRoad}
          progress={renderStageProgress?.(stage)}
        />
        <ol className={styles.stops} aria-label={`${stage.number}단계 글`}>
          {stage.articles.map((article, index) => (
            <ArticleNode
              key={article.articleId}
              article={article}
              stageNumber={stage.number}
              index={index}
              progress={progress?.get(article.articleId)}
              current={article.articleId === currentArticleId}
              linkState={linkState}
            />
          ))}
        </ol>
        {/* 단계 끝: 길이 단계 기둥으로 돌아오는 꺾임점만 둔다. 완주 메달을 길 위에 세우면 지나야 하는 문처럼 읽힌다. */}
        <div className={styles.stageEnd} aria-hidden="true">
          <i className={styles.anchor} {...roadPointProps(bandRoad)} />
        </div>
      </section>
    )
  }

  return (
    <div className={styles.map} ref={mapRef}>
      <h2 className={styles.srOnly}>단계별 글</h2>
      <RoadLayer road={road} tracked={progress !== undefined} />
      {released.map(region)}
      <ReleaseBoundary stages={stages} />
      {preparing.map(region)}
    </div>
  )
}

type Road = {
  width: number
  height: number
  /** 길 아래에 까는 넓고 옅은 바닥 */
  bed: string
  /** 같은 모양이 이어지는 구간마다 경로 하나 */
  groups: { kind: RoadKind; d: string }[]
  /** 공개 경계 앞에서 길이 끝나는 점 */
  ends: { kind: RoadKind; x: number; y: number }[]
}

function round1(value: number): number {
  return Math.round(value * 10) / 10
}

/**
 * 지도 안의 길 점을 재서 길을 만든다. 이웃한 두 점은 세로 가운데에서 꺾이는 S 곡선으로 잇고, 구간의 모양은 도착하는
 * 점이 정한다(읽은 글로 가는 구간이 초록). 끊는 점(공개 경계) 앞에서는 길을 끊고 끝 표시를 둔다. 출발점은 어느 구간의
 * 도착점도 아니므로, 끝 표시가 출발점에 놓이면 중립 길의 끝으로 그린다.
 */
function measureRoad(map: HTMLElement): Road | null {
  const box = map.getBoundingClientRect()
  if (box.width === 0 || box.height === 0) return null
  const points = readRoadPoints(map).map(({ element, kind, gap }) => {
    const r = element.getBoundingClientRect()
    return {
      x: round1(r.left + r.width / 2 - box.left),
      y: round1(r.top + r.height / 2 - box.top),
      kind: kind === 'start' ? ('neutral' as const) : kind,
      gap,
    }
  })
  if (points.length < 2) return null

  const groups: Road['groups'] = []
  const ends: Road['ends'] = []
  let current: Road['groups'][number] | null = null
  let bed = ''
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1]
    const b = points[i]
    if (b.gap) {
      if (a.kind !== 'preparing') ends.push({ kind: a.kind, x: a.x, y: a.y })
      current = null
      continue
    }
    const mid = round1((a.y + b.y) / 2)
    const segment = ` C${a.x} ${mid} ${b.x} ${mid} ${b.x} ${b.y}`
    if (!current || current.kind !== b.kind) {
      current = { kind: b.kind, d: `M${a.x} ${a.y}` }
      groups.push(current)
    }
    current.d += segment
    if (b.kind !== 'preparing') bed += `M${a.x} ${a.y}${segment}`
  }
  return { width: round1(box.width), height: round1(box.height), bed, groups, ends }
}

function sameRoad(a: Road | null, b: Road | null): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function useMeasuredRoad(
  mapRef: RefObject<HTMLDivElement | null>,
  stages: readonly StageView[],
  progress: ArticleProgressMap | undefined,
): Road | null {
  const [road, setRoad] = useState<Road | null>(null)

  useLayoutEffect(() => {
    const map = mapRef.current
    if (!map) return
    const update = () => {
      const next = measureRoad(map)
      setRoad((previous) => (sameRoad(previous, next) ? previous : next))
    }
    update()
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', update)
      return () => window.removeEventListener('resize', update)
    }
    const observer = new ResizeObserver(update)
    observer.observe(map)
    return () => observer.disconnect()
  }, [mapRef, stages, progress])

  return road
}

/**
 * 잰 길을 그린다. 구간의 모양은 `data-kind`로 넘기고 색·굵기·점선은 `JourneyMap.module.css`가 정한다. 준비 중 단계로
 * 가는 점선 길에는 그리는 움직임을 주지 않는다.
 */
function RoadLayer({ road, tracked }: { road: Road | null; tracked: boolean }) {
  if (!road) return null
  return (
    <svg
      // 나의 학습 상태로 칠한 길이면 상태 표시다. 로그인 상태를 다시 확인하는 동안 화면이 숨긴다(research R33).
      data-learner-mark={tracked ? '' : undefined}
      className={styles.road}
      width={road.width}
      height={road.height}
      viewBox={`0 0 ${road.width} ${road.height}`}
      aria-hidden="true"
      focusable="false"
      data-road-layer=""
    >
      <path className={styles.roadBed} d={road.bed} />
      {road.groups.map((group, index) => (
        <path
          // 크기가 바뀌어 다시 재도 같은 구간은 같은 요소로 두어 그리는 움직임이 되풀이되지 않게 한다.
          key={`${index}-${group.kind}`}
          className={group.kind === 'preparing' ? styles.segment : `${styles.segment} ${styles.draw}`}
          data-kind={group.kind}
          pathLength={group.kind === 'preparing' ? undefined : 1}
          d={group.d}
        />
      ))}
      {road.ends.map((end, index) => (
        <circle key={`end-${index}`} className={styles.roadEnd} data-kind={end.kind} cx={end.x} cy={end.y} r="5.5" />
      ))}
    </svg>
  )
}

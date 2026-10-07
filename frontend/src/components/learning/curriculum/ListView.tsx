import { Fragment, useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { StageView } from '@/types/learning'
import { stageColor } from '../stageColors'
import type { ArticleProgress, ArticleProgressMap, RoadKind } from './articleProgress'
import { PREVIOUS_EDITION_NOTE, boundarySentence, isReleased, preparingReason, stageMinutes } from './curriculumCopy'
import { StageLandscape } from './StageLandscape'
import { ChipRow, PreparingChip, PreviousEditionChip, ProgressChip, ReadingMinutes, TermCount } from './StatusChips'
import styles from './ListView.module.css'

interface ListViewProps {
  stages: readonly StageView[]
  /** 글별 나의 학습 상태(B13). 있으면 상태 열과 익힌 용어 수를 보이고, 없으면 상태 없이 그 글의 용어 개수다 */
  progress?: ArticleProgressMap
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

type Row =
  | { type: 'group'; stage: StageView }
  | { type: 'article'; stage: StageView; index: number }
  | { type: 'previous'; stage: StageView; index: number }
  /** 공개된 단계가 끝나는 자리의 한 줄(FR-031) */
  | { type: 'boundary'; sentence: string }

type Rails = { top: RoadKind | null; bottom: RoadKind | null }

/**
 * 목록 보기(013 FR-023, design-notes §3.2). 모든 글을 읽는 순서대로 한 표에 보이고, 단계는 표 안의 구분 줄로만 보인다.
 *
 * <p>1280px에서는 단계마다 `<tbody>`와 구분 줄(`th scope="rowgroup"`)을 둔 표이고 맨 왼쪽 열에 세로 노선과 정거장 점이
 * 있다. 720px 미만에서는 같은 데이터를 `<ol>` 목록으로 다시 그리고 가로 스크롤을 두지 않는다. 두 모양을 함께 그리고
 * 화면 폭에 맞는 하나만 CSS로 보인다. 어느 글도 잠그지 않는다.
 *
 * <p>공개하지 않은 단계와 글이 0편인 공개 단계는 번호·이름·수준·한 줄 설명과 '준비 중'·사유를 보이고, 공개하지 않은
 * 단계에 남은 기존 글은 번호 없이 '이전 판'이다. 공개된 단계가 끝나는 자리에 공개 경계 한 줄을 둔다. 두 보기 모두에
 * 해당하는 FR-031 규칙이다(목업의 목록 보기는 이 둘을 빼고 그렸지만 spec을 따른다).
 */
export function ListView({ stages, progress, linkState }: ListViewProps) {
  const rows = toRows(stages)
  const rails = railsOf(rows, progress)
  const tracked = progress !== undefined
  const columnCount = tracked ? 5 : 4
  // 표와 좁은 화면 목록은 같은 줄 목록을 단계 묶음으로 나눠 그린다. 경계 줄은 자기 묶음이다.
  const sections = toSections(rows)

  return (
    <>
      <h2 className={styles.srOnly}>글 목록</h2>
      <table className={styles.table}>
        <caption className={styles.srOnly}>
          배우기 글 목록. 읽는 순서대로 번호, 제목과 질문{tracked ? ', 상태' : ''}, 읽기 시간,{' '}
          {tracked ? '익힌 용어' : '그 글의 용어 개수'}를 보입니다.
        </caption>
        <thead>
          <tr>
            <td className={styles.rail} aria-hidden="true" />
            <th scope="col">번호</th>
            <th scope="col">제목과 질문</th>
            {tracked ? <th scope="col">상태</th> : null}
            <th scope="col">읽기</th>
            <th scope="col">{tracked ? '용어' : '이 글의 용어'}</th>
          </tr>
        </thead>
        {sections.map((section) => {
          if (section.kind === 'boundary') {
            const { row, index } = section.rows[0]
            return (
              <tbody key="boundary" className={styles.boundaryGroup}>
                <tr>
                  <td className={styles.rail} aria-hidden="true">
                    <Rail {...rails[index]} />
                  </td>
                  <td colSpan={columnCount} className={styles.boundary}>
                    {row.type === 'boundary' ? row.sentence : null}
                  </td>
                </tr>
              </tbody>
            )
          }
          const { stage } = section
          return (
            <tbody
              key={stage.stageKey}
              className={stage.status === 'OPEN' ? undefined : styles.pending}
              style={{ ['--stage' as string]: stageColor(stage.number) }}
              data-stage-anchor={stage.number}
            >
              {section.rows.map(({ row, index }) => {
                const rail = <Rail {...rails[index]} />
                if (row.type === 'group') {
                  return (
                    <tr key="group" className={styles.groupRow}>
                      <td className={styles.rail} aria-hidden="true">
                        {rail}
                        <GroupMark stage={stage} tracked={tracked} />
                      </td>
                      <th colSpan={columnCount} scope="rowgroup" tabIndex={-1}>
                        <GroupText stage={stage} progress={progress} />
                      </th>
                    </tr>
                  )
                }
                if (row.type === 'article') {
                  const article = stage.articles[row.index]
                  const state = progress?.get(article.articleId)
                  return (
                    <tr key={article.articleId}>
                      <td className={styles.rail} aria-hidden="true">
                        {rail}
                        <StationDot progress={state} tracked={tracked} />
                      </td>
                      <td className={styles.number}>{article.number}</td>
                      <td className={styles.titleCell}>
                        <Link to={`/learn/${encodeURIComponent(article.articleId)}`} state={linkState}>
                          {article.title}
                        </Link>
                        <span className={styles.question}>{article.question}</span>
                      </td>
                      {tracked ? <td>{state ? <ProgressChip progress={state} /> : null}</td> : null}
                      <td className={styles.minor}>약 {article.readingMinutes}분</td>
                      <td className={styles.minor}>
                        <TermCell termCount={article.termCount} progress={state} />
                      </td>
                    </tr>
                  )
                }
                if (row.type !== 'previous') return null
                const article = stage.previousEditionArticles[row.index]
                return (
                  <tr key={article.articleId} className={styles.previousRow}>
                    <td className={styles.rail} aria-hidden="true">
                      {rail}
                    </td>
                    <td className={styles.number} />
                    <td className={styles.titleCell}>
                      <span className={styles.titleLine}>
                        <Link to={`/learn/${encodeURIComponent(article.articleId)}`} state={linkState}>
                          {article.title}
                        </Link>
                        <PreviousEditionChip />
                      </span>
                      <span className={styles.question}>{article.question}</span>
                      <span className={styles.srOnly}>{PREVIOUS_EDITION_NOTE}</span>
                    </td>
                    {tracked ? <td /> : null}
                    <td className={styles.minor}>약 {article.readingMinutes}분</td>
                    <td className={styles.minor} />
                  </tr>
                )
              })}
            </tbody>
          )
        })}
      </table>

      <ol className={styles.narrowList} aria-label="배우기 글 목록">
        {sections.map((section, sectionIndex) => {
          if (section.kind === 'boundary') return null
          const { stage } = section
          // 좁은 화면 목록의 항목은 단계뿐이다. 경계 한 줄은 그 뒤 첫 단계 항목의 맨 앞에 둔다.
          const before = sections[sectionIndex - 1]
          const boundary = before?.kind === 'boundary' ? before.rows[0] : null
          return (
            <li
              key={stage.stageKey}
              className={stage.status === 'OPEN' ? undefined : styles.pending}
              style={{ ['--stage' as string]: stageColor(stage.number) }}
              data-stage-anchor={stage.number}
            >
              {boundary && boundary.row.type === 'boundary' ? (
                <p className={styles.narrowBoundary}>
                  <span className={styles.rail} aria-hidden="true">
                    <Rail {...rails[boundary.index]} />
                  </span>
                  {boundary.row.sentence}
                </p>
              ) : null}
              {section.rows.map(({ row, index }) => {
                if (row.type !== 'group') return null
                return (
                  <div key="group" className={styles.groupHead}>
                    <span className={styles.rail} aria-hidden="true">
                      <Rail {...rails[index]} />
                      <GroupMark stage={stage} tracked={tracked} />
                    </span>
                    <h3 tabIndex={-1}>
                      <GroupText stage={stage} progress={progress} />
                    </h3>
                  </div>
                )
              })}
              <ol className={styles.plain}>
                {section.rows.map(({ row, index }) => {
                  if (row.type === 'group' || row.type === 'boundary') return null
                  const state = row.type === 'article' ? progress?.get(stage.articles[row.index].articleId) : undefined
                  const rail = (
                    <span className={styles.rail} aria-hidden="true">
                      <Rail {...rails[index]} />
                      {row.type === 'article' ? <StationDot progress={state} tracked={tracked} /> : null}
                    </span>
                  )
                  if (row.type === 'article') {
                    const article = stage.articles[row.index]
                    return (
                      <li key={article.articleId}>
                        <Link className={styles.row} to={`/learn/${encodeURIComponent(article.articleId)}`} state={linkState}>
                          {rail}
                          <span className={styles.rowTitle}>
                            <span className={styles.rowNumber}>{article.number}</span>
                            {article.title}
                          </span>
                          <span className={styles.rowQuestion}>{article.question}</span>
                          <ChipRow>
                            {state ? <ProgressChip progress={state} /> : null}
                            <ReadingMinutes minutes={article.readingMinutes} />
                            <TermCount termCount={article.termCount} progress={state} />
                          </ChipRow>
                        </Link>
                      </li>
                    )
                  }
                  const article = stage.previousEditionArticles[row.index]
                  return (
                    <li key={article.articleId}>
                      <Link className={styles.row} to={`/learn/${encodeURIComponent(article.articleId)}`} state={linkState}>
                        {rail}
                        <span className={styles.rowTitle}>{article.title}</span>
                        <span className={styles.rowQuestion}>{article.question}</span>
                        <ChipRow>
                          <PreviousEditionChip />
                          <ReadingMinutes minutes={article.readingMinutes} />
                        </ChipRow>
                        <span className={styles.srOnly}>{PREVIOUS_EDITION_NOTE}</span>
                      </Link>
                    </li>
                  )
                })}
              </ol>
            </li>
          )
        })}
      </ol>
    </>
  )
}

/** 읽는 순서대로 단계 구분 줄과 글 줄을 늘어놓고, 공개된 단계가 끝나는 자리에 경계 줄을 끼운다. */
function toRows(stages: readonly StageView[]): Row[] {
  const rows: Row[] = []
  const sentence = boundarySentence(stages)
  let boundaryPlaced = sentence === null
  for (const stage of stages) {
    if (!boundaryPlaced && !isReleased(stage)) {
      rows.push({ type: 'boundary', sentence: sentence! })
      boundaryPlaced = true
    }
    rows.push({ type: 'group', stage })
    if (stage.status === 'OPEN') {
      stage.articles.forEach((_, index) => rows.push({ type: 'article', stage, index }))
    } else {
      stage.previousEditionArticles.forEach((_, index) => rows.push({ type: 'previous', stage, index }))
    }
  }
  return rows
}

type Section =
  | { kind: 'stage'; stage: StageView; rows: { row: Row; index: number }[] }
  | { kind: 'boundary'; rows: { row: Row; index: number }[] }

function toSections(rows: Row[]): Section[] {
  const sections: Section[] = []
  rows.forEach((row, index) => {
    if (row.type === 'boundary') {
      sections.push({ kind: 'boundary', rows: [{ row, index }] })
      return
    }
    if (row.type === 'group') {
      sections.push({ kind: 'stage', stage: row.stage, rows: [{ row, index }] })
      return
    }
    sections[sections.length - 1].rows.push({ row, index })
  })
  return sections
}

/** 그 줄로 들어오는 노선의 종류. 준비 중 단계는 점선, 상태를 모르면 중립, 읽은 글은 초록이다. */
function railKind(row: Row, progress: ArticleProgressMap | undefined): RoadKind {
  if (row.type === 'boundary' || row.stage.status !== 'OPEN') return 'd'
  if (!progress) return 'n'
  if (row.type === 'article') {
    return progress.get(row.stage.articles[row.index].articleId)?.state === 'done' ? 'g' : 's'
  }
  return 's'
}

/**
 * 줄마다 위아래 노선. 커리큘럼 보기의 길처럼 공개 경계 앞에서 노선이 끝나고, 경계 줄에서 점선이 새로 시작한다.
 */
function railsOf(rows: Row[], progress: ArticleProgressMap | undefined): Rails[] {
  return rows.map((row, i) => {
    const next = rows[i + 1]
    const top = i === 0 || row.type === 'boundary' ? null : railKind(row, progress)
    const bottom = next === undefined || next.type === 'boundary' ? null : railKind(next, progress)
    return { top, bottom }
  })
}

const RAIL_CLASS: Record<RoadKind, string | undefined> = {
  g: styles.railRead,
  s: styles.railTodo,
  n: styles.railNeutral,
  d: styles.railPreparing,
}

/** 줄 칸을 위아래로 지나는 노선. 위 반은 이 줄로 들어오는 구간, 아래 반은 다음 줄로 가는 구간이다. */
function Rail({ top, bottom }: Rails) {
  return (
    <>
      {top ? <span className={`${styles.railTop} ${RAIL_CLASS[top]}`} /> : null}
      {bottom ? <span className={`${styles.railBottom} ${RAIL_CLASS[bottom]}`} /> : null}
    </>
  )
}

/** 단계 구분 줄의 표식(24px). 상태를 모르면 채운 원판, 공개된 단계는 실선 칸, 준비 중은 점선 칸이다. */
function GroupMark({ stage, tracked }: { stage: StageView; tracked: boolean }) {
  const kind = stage.status !== 'OPEN' ? 'preparing' : tracked ? 'open' : 'neutral'
  const shape =
    kind === 'open' ? (
      <circle cx="12" cy="12" r="11.25" fill="#161918" stroke="#8B928E" strokeWidth="1.5" />
    ) : kind === 'neutral' ? (
      <circle cx="12" cy="12" r="12" fill="#2C3230" />
    ) : (
      <circle cx="12" cy="12" r="11.25" fill="#0D0F0E" stroke="#8B928E" strokeWidth="1.5" strokeDasharray="4 3" />
    )
  return (
    <span className={kind === 'preparing' ? `${styles.groupMark} ${styles.groupMarkPreparing}` : styles.groupMark}>
      <svg viewBox="0 0 24 24" focusable="false">
        {shape}
      </svg>
      <span>{stage.number}</span>
    </span>
  )
}

/** 글 줄의 정거장 점(14px). 상태 모양이며 글자 상태는 상태 열에 있다. 완료 점은 도장 잉크와 같은 초록 구슬이다. */
function StationDot({ progress, tracked }: { progress?: ArticleProgress; tracked: boolean }) {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  let shape: ReactNode
  if (!tracked || !progress) {
    shape = <circle cx="7" cy="7" r="6.5" fill="#3A403D" />
  } else if (progress.state === 'done') {
    shape = (
      <>
        <defs>
          <radialGradient id={`${id}-done`} cx="0.35" cy="0.3" r="0.75">
            <stop offset="0" stopColor="#7EF0B0" />
            <stop offset="0.45" stopColor="#2FDC7A" />
            <stop offset="1" stopColor="#03C75A" />
          </radialGradient>
        </defs>
        <circle cx="7" cy="7" r="7" fill={`url(#${id}-done)`} />
        <path d="M3.9 7.2 L6.1 9.4 L10.2 4.9" fill="none" stroke="#01170A" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
      </>
    )
  } else {
    shape = (
      <>
        <circle cx="7" cy="7" r="6.25" fill="#161918" stroke="#8B928E" strokeWidth="1.5" />
        {progress.state === 'reading' ? (
          <circle cx="7" cy="7" r="9.5" fill="none" stroke="#2FDC7A" strokeWidth="2" strokeLinecap="round" pathLength={100} strokeDasharray={`${progress.percent} 100`} transform="rotate(-90 7 7)" />
        ) : null}
      </>
    )
  }
  return (
    <span className={styles.dot} data-dot={tracked && progress ? progress.state : 'neutral'}>
      <svg viewBox="0 0 14 14" focusable="false">
        {shape}
      </svg>
    </span>
  )
}

/**
 * 단계 구분 줄의 글자. 공개된 단계는 번호·이름·수준과 진행이고, 공개하지 않았거나 글이 0편인 단계는 번호·이름·수준,
 * '준비 중', 한 줄 설명, 사유다(FR-031).
 */
function GroupText({ stage, progress }: { stage: StageView; progress?: ArticleProgressMap }) {
  const separator = <span className={styles.separator}>·</span>
  const name = (
    <span className={styles.groupName}>
      {stage.number}단계{separator}
      {stage.name}
      {separator}
      {stage.levelLabel}
    </span>
  )
  const reason = preparingReason(stage.status)
  if (reason) {
    return (
      <>
        <span className={styles.groupLine}>
          <span className={styles.groupIcon} aria-hidden="true">
            <StageLandscape stageNumber={stage.number} preparing mini />
          </span>
          {name}
          <span className={styles.groupChip}>
            <PreparingChip />
          </span>
        </span>
        {stage.status === 'PREPARING' ? <span className={styles.srOnly}>아직 공개되지 않은 단계입니다.</span> : null}
        <span className={styles.groupSummary}>{stage.summary}</span>
        <span className={styles.groupReason}>{reason}</span>
      </>
    )
  }
  const states = progress ? stage.articles.map((article) => progress.get(article.articleId)) : []
  const tracked = progress !== undefined && states.every(Boolean)
  const read = states.filter((state) => state?.state === 'done').length
  const total = stage.articles.length
  return (
    <span className={styles.groupLine}>
      <span className={styles.groupIcon} aria-hidden="true">
        <StageLandscape stageNumber={stage.number} mini />
      </span>
      {name}
      <span className={styles.groupProgress}>
        {separator}
        {!tracked ? (
          <Fragment>
            {total}편 · 약 {stageMinutes(stage)}분
          </Fragment>
        ) : read === total ? (
          // 다 읽은 단계는 '완주 · n편'이다(FR-031 '진행', design-notes §4).
          `완주 · ${total}편`
        ) : (
          `${total}편 중 ${read}편 읽음`
        )}
      </span>
    </span>
  )
}

/** 목록 보기 표의 용어 칸. 상태를 알면 '1/2'(화면 낭독은 '2개 중 1개 익힘'), 모르면 '2개'다. */
function TermCell({ termCount, progress }: { termCount: number; progress?: ArticleProgress }) {
  if (termCount <= 0) return null
  if (!progress) return <>{termCount}개</>
  return (
    <>
      <span aria-hidden="true">
        {progress.masteredTermCount}/{termCount}
      </span>
      <span className={styles.srOnly}>
        {termCount}개 중 {progress.masteredTermCount}개 익힘
      </span>
    </>
  )
}

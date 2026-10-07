import { Fragment, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { StageView } from '@/types/learning'
import { stageColor } from '../stageColors'
import type { ArticleProgress, ArticleProgressMap, RoadKind } from './articleProgress'
import { PREVIOUS_EDITION_NOTE, preparingReason, stageMinutes } from './curriculumCopy'
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

/**
 * 목록 보기(013 FR-023, design-notes §3.2). 모든 글을 읽는 순서대로 한 표에 보이고, 단계는 표 안의 구분 줄로만 보인다.
 *
 * <p>1280px에서는 단계마다 `<tbody>`와 구분 줄(`th scope="rowgroup"`)을 둔 표이고 맨 왼쪽 열에 세로 노선과 정거장 점이
 * 있다. 720px 미만에서는 같은 데이터를 `<ol>` 목록으로 다시 그리고 가로 스크롤을 두지 않는다. 두 모양을 함께 그리고
 * 화면 폭에 맞는 하나만 CSS로 보인다. 공개하지 않은 단계는 '준비 중'과 사유만 있고, 남은 기존 글은 번호 없이 '이전
 * 판'이다(FR-031). 어느 글도 잠그지 않는다.
 */
export function ListView({ stages, progress, linkState }: ListViewProps) {
  const rows = toRows(stages)
  const rails = rows.map((row, i) => ({
    top: i === 0 ? null : railKind(row, progress),
    bottom: i === rows.length - 1 ? null : railKind(rows[i + 1], progress),
  }))
  const tracked = progress !== undefined
  const columnCount = tracked ? 5 : 4

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
        {stages.map((stage) => (
          <tbody
            key={stage.stageKey}
            className={stage.status === 'OPEN' ? undefined : styles.pending}
            style={{ ['--stage' as string]: stageColor(stage.number) }}
            data-stage-anchor={stage.number}
          >
            {rows.map((row, i) => {
              if (row.stage !== stage) return null
              const rail = <Rail {...rails[i]} />
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
        ))}
      </table>

      <ol className={styles.narrowList} aria-label="배우기 글 목록">
        {stages.map((stage) => (
          <li
            key={stage.stageKey}
            className={stage.status === 'OPEN' ? undefined : styles.pending}
            style={{ ['--stage' as string]: stageColor(stage.number) }}
            data-stage-anchor={stage.number}
          >
            {rows.map((row, i) => {
              if (row.stage !== stage || row.type !== 'group') return null
              return (
                <div key="group" className={styles.groupHead}>
                  <span className={styles.rail} aria-hidden="true">
                    <Rail {...rails[i]} />
                    <GroupMark stage={stage} tracked={tracked} />
                  </span>
                  <h3 tabIndex={-1}>
                    <GroupText stage={stage} progress={progress} />
                  </h3>
                </div>
              )
            })}
            <ol className={styles.plain}>
              {rows.map((row, i) => {
                if (row.stage !== stage || row.type === 'group') return null
                const rail = (
                  <span className={styles.rail} aria-hidden="true">
                    <Rail {...rails[i]} />
                    {row.type === 'article' ? (
                      <StationDot progress={progress?.get(stage.articles[row.index].articleId)} tracked={tracked} />
                    ) : null}
                  </span>
                )
                if (row.type === 'article') {
                  const article = stage.articles[row.index]
                  const state = progress?.get(article.articleId)
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
        ))}
      </ol>
    </>
  )
}

function toRows(stages: readonly StageView[]): Row[] {
  const rows: Row[] = []
  for (const stage of stages) {
    rows.push({ type: 'group', stage })
    if (stage.status === 'OPEN') {
      stage.articles.forEach((_, index) => rows.push({ type: 'article', stage, index }))
    } else {
      stage.previousEditionArticles.forEach((_, index) => rows.push({ type: 'previous', stage, index }))
    }
  }
  return rows
}

/** 그 줄로 들어오는 노선의 종류. 준비 중 단계는 점선, 상태를 모르면 중립, 읽은 글은 초록이다. */
function railKind(row: Row, progress: ArticleProgressMap | undefined): RoadKind {
  if (row.stage.status !== 'OPEN') return 'd'
  if (!progress) return 'n'
  if (row.type === 'article') {
    return progress.get(row.stage.articles[row.index].articleId)?.state === 'done' ? 'g' : 's'
  }
  return 's'
}

const RAIL_CLASS: Record<RoadKind, string | undefined> = {
  g: styles.railRead,
  s: styles.railTodo,
  n: styles.railNeutral,
  d: styles.railPreparing,
}

/** 줄 칸을 위아래로 지나는 노선. 위 반은 이 줄로 들어오는 구간, 아래 반은 다음 줄로 가는 구간이다. */
function Rail({ top, bottom }: { top: RoadKind | null; bottom: RoadKind | null }) {
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

/** 글 줄의 정거장 점(14px). 상태 모양이며 글자 상태는 상태 열에 있다. */
function StationDot({ progress, tracked }: { progress?: ArticleProgress; tracked: boolean }) {
  let shape: ReactNode
  if (!tracked || !progress) {
    shape = <circle cx="7" cy="7" r="6.5" fill="#3A403D" />
  } else if (progress.state === 'done') {
    shape = (
      <>
        <circle cx="7" cy="7" r="7" fill="#2FDC7A" />
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
    <span className={styles.dot}>
      <svg viewBox="0 0 14 14" focusable="false">
        {shape}
      </svg>
    </span>
  )
}

/** 단계 구분 줄의 글자. 단계 번호·이름·수준과 진행(또는 '준비 중'과 사유)이다. */
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
          <PreparingChip />
        </span>
        <span className={styles.groupReason}>{reason}</span>
        {stage.status === 'PREPARING' ? <span className={styles.srOnly}>아직 공개되지 않은 단계입니다.</span> : null}
      </>
    )
  }
  const states = progress ? stage.articles.map((article) => progress.get(article.articleId)) : []
  const tracked = progress !== undefined && states.every(Boolean)
  const read = states.filter((state) => state?.state === 'done').length
  return (
    <span className={styles.groupLine}>
      <span className={styles.groupIcon} aria-hidden="true">
        <StageLandscape stageNumber={stage.number} mini />
      </span>
      {name}
      <span className={styles.groupProgress}>
        {separator}
        {tracked ? (
          `${stage.articles.length}편 중 ${read}편 읽음`
        ) : (
          <Fragment>
            {stage.articles.length}편 · 약 {stageMinutes(stage)}분
          </Fragment>
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

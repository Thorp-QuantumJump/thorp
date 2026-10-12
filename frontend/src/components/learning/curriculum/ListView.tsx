import { Link } from 'react-router-dom'
import type { StageView } from '@/types/learning'
import { articlePath } from '../learningPaths'
import { stageColor } from '../stageColors'
import { stageProgressLabel, type ArticleProgress, type ArticleProgressMap, type StageCount } from './articleProgress'
import { PREVIOUS_EDITION_NOTE, UNRELEASED_STAGE_NOTE, boundarySentence, preparingReason } from './curriculumCopy'
import { learnerMarkProps, stageAnchorProps, stageHeadingProps } from './stageAnchors'
import { StageLandscape } from './StageLandscape'
import { isReleased, stageMinutes } from './stages'
import { ChipRow, PreparingChip, PreviousEditionChip, ProgressChip, ReadingMinutes, TermCount } from './StatusChips'
import styles from './ListView.module.css'

interface ListViewProps {
  stages: readonly StageView[]
  /**
   * 글별 나의 학습 상태(B13). 있으면 로그인 사용자의 모습이다. 상태 열과 '완료'·'62% 읽는 중'·'안 읽음' 꼬리표, 익힌
   * 용어 'm/n', 정거장 점의 상태 모양, 초록·회색 노선이 생긴다. 없으면 상태를 모르는 모습(비로그인·기록 없음)이다
   */
  progress?: ArticleProgressMap
  /** 단계별 완료 수(B13). 있으면 단계 구분 줄에 'n편 중 m편 읽음'·'완주 · n편'을 보인다 */
  stageCounts?: ReadonlyMap<number, StageCount>
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

type Row =
  | { type: 'group'; stage: StageView }
  | { type: 'article'; stage: StageView; index: number }
  | { type: 'previous'; stage: StageView; index: number }
  /** 공개된 단계가 끝나는 자리의 한 줄(FR-031) */
  | { type: 'boundary'; sentence: string }

/**
 * 노선의 모양. 준비 중 단계로 가는 노선은 점선이다. 글별 상태를 알면 읽은 글로 가는 노선은 초록(`read`), 그 밖은 회색
 * 실선(`unread`)이고, 모르면 짙은 중립 노선(`neutral`)이다(목업 `listRows`).
 */
type RailKind = 'read' | 'unread' | 'neutral' | 'preparing'

type Rails = { top: RailKind | null; bottom: RailKind | null }

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
 *
 * <p>로그인 사용자의 나의 학습 상태(`progress`)를 받으면 상태 열, 단계별 진행('n편 중 m편 읽음', '완주 · n편'), 상태에
 * 따른 정거장 점과 노선을 더한다. 상태를 모르는 글은 상태 칸을 비우고 '안 읽음'으로 채우지 않는다(FR-020).
 */
export function ListView({ stages, progress, stageCounts, linkState }: ListViewProps) {
  const rows = toRows(stages)
  const rails = railsOf(rows, progress)
  // 표와 좁은 화면 목록은 같은 줄 목록을 단계 묶음으로 나눠 그린다. 경계 줄은 자기 묶음이다.
  const sections = toSections(rows)
  const signedIn = progress !== undefined
  const columnCount = signedIn ? 5 : 4

  return (
    <>
      <h2 className={styles.srOnly}>글 목록</h2>
      <table className={styles.table}>
        <caption className={styles.srOnly}>
          {signedIn
            ? '배우기 글 목록. 읽는 순서대로 번호, 제목과 질문, 상태, 읽기 시간, 익힌 용어를 보입니다.'
            : '배우기 글 목록. 읽는 순서대로 번호, 제목과 질문, 읽기 시간, 그 글의 용어 개수를 보입니다.'}
        </caption>
        <thead>
          <tr>
            <td className={styles.rail} aria-hidden="true" />
            <th scope="col">번호</th>
            <th scope="col">제목과 질문</th>
            {signedIn ? <th scope="col">상태</th> : null}
            <th scope="col">읽기</th>
            <th scope="col">{signedIn ? '용어' : '이 글의 용어'}</th>
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
              {...stageAnchorProps(stage.number)}
            >
              {section.rows.map(({ row, index }) => {
                const rail = <Rail {...rails[index]} />
                if (row.type === 'group') {
                  return (
                    <tr key="group" className={styles.groupRow}>
                      <td className={styles.rail} aria-hidden="true">
                        {rail}
                        <GroupMark stage={stage} tracked={signedIn} />
                      </td>
                      <th colSpan={columnCount} scope="rowgroup" tabIndex={-1} {...stageHeadingProps}>
                        <GroupText stage={stage} count={stageCounts?.get(stage.number)} />
                      </th>
                    </tr>
                  )
                }
                if (row.type === 'article') {
                  const article = stage.articles[row.index]
                  const entry = progress?.get(article.articleId)
                  return (
                    <tr key={article.articleId}>
                      <td className={styles.rail} aria-hidden="true">
                        {rail}
                        <StationDot progress={entry} />
                      </td>
                      <td className={styles.number}>{article.number}</td>
                      <td className={styles.titleCell}>
                        <Link to={articlePath(article.articleId)} state={linkState}>
                          {article.title}
                        </Link>
                        <span className={styles.question}>{article.question}</span>
                      </td>
                      {signedIn ? <td className={styles.status}>{entry ? <ProgressChip progress={entry} /> : null}</td> : null}
                      <td className={styles.minor}>약 {article.readingMinutes}분</td>
                      <td className={styles.minor}>
                        <TermCount termCount={article.termCount} progress={entry} withLabel={false} />
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
                        <Link to={articlePath(article.articleId)} state={linkState}>
                          {article.title}
                        </Link>
                        <PreviousEditionChip />
                      </span>
                      <span className={styles.question}>{article.question}</span>
                      <span className={styles.srOnly}>{PREVIOUS_EDITION_NOTE}</span>
                    </td>
                    {signedIn ? <td className={styles.status} /> : null}
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
              {...stageAnchorProps(stage.number)}
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
                      <GroupMark stage={stage} tracked={signedIn} />
                    </span>
                    <h3 tabIndex={-1} {...stageHeadingProps}>
                      <GroupText stage={stage} count={stageCounts?.get(stage.number)} />
                    </h3>
                  </div>
                )
              })}
              <ol className={styles.plain}>
                {section.rows.map(({ row, index }) => {
                  if (row.type === 'group' || row.type === 'boundary') return null
                  const entry = row.type === 'article' ? progress?.get(stage.articles[row.index].articleId) : undefined
                  const rail = (
                    <span className={styles.rail} aria-hidden="true">
                      <Rail {...rails[index]} />
                      {row.type === 'article' ? <StationDot progress={entry} /> : null}
                    </span>
                  )
                  if (row.type === 'article') {
                    const article = stage.articles[row.index]
                    return (
                      <li key={article.articleId}>
                        <Link className={styles.row} to={articlePath(article.articleId)} state={linkState}>
                          {rail}
                          <span className={styles.rowTitle}>
                            <span className={styles.rowNumber}>{article.number}</span>
                            {article.title}
                          </span>
                          <span className={styles.rowQuestion}>{article.question}</span>
                          <ChipRow>
                            {entry ? <ProgressChip progress={entry} /> : null}
                            <ReadingMinutes minutes={article.readingMinutes} />
                            <TermCount termCount={article.termCount} progress={entry} />
                          </ChipRow>
                        </Link>
                      </li>
                    )
                  }
                  const article = stage.previousEditionArticles[row.index]
                  return (
                    <li key={article.articleId}>
                      <Link className={styles.row} to={articlePath(article.articleId)} state={linkState}>
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
    if (sentence !== null && !boundaryPlaced && !isReleased(stage)) {
      rows.push({ type: 'boundary', sentence })
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

/**
 * 그 줄로 들어오는 노선의 모양. 준비 중 단계와 경계 줄은 점선이다. 상태를 알면 읽은 글로 들어오는 노선은 초록, 그 밖은
 * 회색 실선이고, 모르면 중립이다.
 */
function railKind(row: Row, progress: ArticleProgressMap | undefined): RailKind {
  if (row.type === 'boundary' || row.stage.status !== 'OPEN') return 'preparing'
  if (!progress) return 'neutral'
  if (row.type !== 'article') return 'unread'
  const entry = progress.get(row.stage.articles[row.index].articleId)
  if (!entry) return 'neutral'
  return entry.state === 'done' ? 'read' : 'unread'
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

/**
 * 줄 칸을 위아래로 지나는 노선. 위 반은 이 줄로 들어오는 구간, 아래 반은 다음 줄로 가는 구간이다. 모양은 `data-kind`로
 * 넘기고 색·점선은 `ListView.module.css`가 정한다.
 */
function Rail({ top, bottom }: Rails) {
  // 초록·회색 노선은 나의 학습 상태 표시다. 로그인 상태를 다시 확인하는 동안 화면이 숨긴다(research R33).
  const mark = (kind: RailKind) => learnerMarkProps(kind === 'read' || kind === 'unread')
  return (
    <>
      {top ? <span className={styles.railTop} data-kind={top} {...mark(top)} /> : null}
      {bottom ? <span className={styles.railBottom} data-kind={bottom} {...mark(bottom)} /> : null}
    </>
  )
}

/**
 * 단계 구분 줄의 표식(24px). 준비 중은 점선 칸, 공개된 단계는 상태를 알면 실선 회색 칸(`open`), 모르면 채운 원판이다.
 * 색은 CSS가 정한다.
 */
function GroupMark({ stage, tracked }: { stage: StageView; tracked: boolean }) {
  const preparing = stage.status !== 'OPEN'
  const kind = preparing ? 'preparing' : tracked ? 'open' : 'neutral'
  return (
    <span className={styles.groupMark} data-kind={kind} {...learnerMarkProps(kind === 'open')}>
      <svg viewBox="0 0 24 24" focusable="false">
        {kind === 'neutral' ? (
          <circle cx="12" cy="12" r="12" />
        ) : (
          <circle cx="12" cy="12" r="11.25" strokeWidth="1.5" strokeDasharray={preparing ? '4 3' : undefined} />
        )}
      </svg>
      <span>{stage.number}</span>
    </span>
  )
}

/**
 * 글 줄의 정거장 점(14px, 목업 `sdot`). 완료는 초록 채움과 체크, 읽는 중은 실선 회색 칸과 초록 진행 호, 안 읽음은 실선
 * 회색 칸, 상태를 모르면 중립 점이다. 글자는 상태 칸이 원본이다.
 */
function StationDot({ progress }: { progress?: ArticleProgress }) {
  const kind = progress ? (progress.state === 'done' ? 'done' : progress.state === 'reading' ? 'reading' : 'unread') : 'neutral'
  return (
    <span className={styles.dot} data-kind={kind} {...learnerMarkProps(progress !== undefined)}>
      <svg viewBox="0 0 14 14" focusable="false">
        {kind === 'neutral' ? <circle cx="7" cy="7" r="6.5" /> : null}
        {kind === 'done' ? (
          <>
            <circle cx="7" cy="7" r="7" />
            <path d="M3.9 7.2 L6.1 9.4 L10.2 4.9" fill="none" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
          </>
        ) : null}
        {kind === 'reading' || kind === 'unread' ? <circle cx="7" cy="7" r="6.25" strokeWidth="1.5" /> : null}
        {progress?.state === 'reading' ? (
          <circle
            className={styles.dotArc}
            cx="7"
            cy="7"
            r="9.5"
            strokeWidth="2"
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={`${progress.percent} 100`}
            transform="rotate(-90 7 7)"
          />
        ) : null}
      </svg>
    </span>
  )
}

/**
 * 단계 구분 줄의 글자. 공개된 단계는 번호·이름·수준과, 나의 학습 완료 수를 알면 'n편 중 m편 읽음'(다 읽었으면 '완주 ·
 * n편'), 모르면 글 수·예상 읽기 시간이다. 공개하지 않았거나 글이 0편인 단계는 번호·이름·수준, '준비 중', 한 줄 설명,
 * 사유다(FR-031).
 */
function GroupText({ stage, count }: { stage: StageView; count?: StageCount }) {
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
        {stage.status === 'PREPARING' ? <span className={styles.srOnly}>{UNRELEASED_STAGE_NOTE}</span> : null}
        <span className={styles.groupSummary}>{stage.summary}</span>
        <span className={styles.groupReason}>{reason}</span>
      </>
    )
  }
  return (
    <span className={styles.groupLine}>
      <span className={styles.groupIcon} aria-hidden="true">
        <StageLandscape stageNumber={stage.number} mini />
      </span>
      {name}
      <span className={styles.groupProgress} {...learnerMarkProps(count !== undefined)}>
        {separator}
        {count ? stageProgressLabel(count) : `${stage.articles.length}편 · 약 ${stageMinutes(stage)}분`}
      </span>
    </span>
  )
}

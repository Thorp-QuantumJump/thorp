import { ArrowLeft, ArrowUpRight } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { fetchLearningArticle, type LearningArticleResult } from '@/api/learning'
import { useAuth } from '@/auth/auth-context'
import { ArticleBody } from '@/components/learning/article/ArticleBody'
import { ArticleEnd, type ArticleEndMode } from '@/components/learning/article/ArticleEnd'
import { EditionNotice } from '@/components/learning/article/EditionNotice'
import { ResumeLine } from '@/components/learning/article/ResumeLine'
import { useArticleCompletion } from '@/components/learning/article/useArticleCompletion'
import { useLearnerArticle } from '@/components/learning/article/useLearnerArticle'
import {
  focusReadPosition,
  scrollToReadPercent,
  useReadingPosition,
} from '@/components/learning/article/useReadingPosition'
import { useRestoreFocusAfterConceal } from '@/components/learning/article/useRestoreFocusAfterConceal'
import { useSettledAuth, type SettledAuth } from '@/components/learning/article/useSettledAuth'
import { ExampleEntry } from '@/components/learning/ExampleEntry'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import { stageHash } from '@/components/learning/curriculum/curriculumCopy'
import { ReturnLink } from '@/components/learning/ReturnLink'
import { carriedLearningReturn } from '@/components/learning/returnTargets'
import { AppHeader } from '@/components/navigation/AppHeader'
import type { LearningArticle, LearningExample, LearningReturnState } from '@/types/learning'
import heading from '@/components/common/PageHeading.module.css'
import styles from './LearningArticlePage.module.css'

type ArticleState = { status: 'loading' } | LearningArticleResult

/**
 * '이어서 읽기'로 열었는가(navigation state `learningResume: true`, data-model §15). 위치는 실려 오지 않는다. 글
 * 화면이 받은 글 기록의 `percent`로 옮긴다. `true` 밖의 값은 받지 않는다.
 */
function resumeRequested(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    'learningResume' in state &&
    state.learningResume === true
  )
}

/** 센 시간·읽은 비율·받은 기록을 처음부터 할 단위. 확인 중과 `error`는 여기 오지 않는다(research R33). */
function sessionKeyOf(settled: SettledAuth): string {
  return settled.kind === 'authenticated' ? `authenticated:${settled.userId}` : settled.kind
}

/**
 * 배우기 글 하나(`/learn/:articleId`, 013 US1·US2·US6).
 *
 * <p><b>응답만으로 그린다.</b> 제목·질문·본문·예시·확인 문제가 모두 공개 중인 판에서 온다(013 FR-015).
 * 판(`edition`)에 따라 머리와 글 끝이 다르다.
 *
 * - `CURRENT`: 경로·번호·예상 읽기 시간 머리, 새 기준 본문, (예시가 있으면) 예시, 글 끝 자리(목업 '글 끝')
 * - `PREVIOUS`: 경로와 '이전 판' 머리, 9월 판 본문과 예시, 글 끝에는 기록하지 않는다는 한 줄과 목록 연결만
 *   (FR-031). 9월 판의 글 끝 출처 목록은 두지 않는다(FR-007)
 * - `PRE_RELEASE`: 첫 단계 공개 전 모습 그대로(FR-031)
 *
 * <p><b>볼 수 없는 글은 오류가 아니다.</b> 없는 글과 게시되지 않은 글을 서버가 가르지 않으므로 화면도
 * 한 문장과 목록 연결만 보인다(spec Edge Cases). 받지 못한 것만 다시 시도를 준다.
 *
 * <p><b>로그인 사용자의 새 기준 글은 기록한다</b>(FR-020~FR-022). 글 기록을 받아 완료 카드와 확인 문제를 그리고,
 * 본문 끝과 머문 시간으로 완료를 보내며, 떠날 때 읽은 위치를 보낸다. 확인 중(`checking`)과 `error` 동안에는 기록
 * 자리를 숨기기만 하고 받은 것을 버리지 않는다. 로그인 상태나 사용자가 바뀌면 버리고 처음부터 한다(research R33).
 *
 * <p>학습 기록을 브라우저 저장소에 쓰지 않는다(013 FR-020). 비로그인 확인 문제도 화면 안에서만 푼다.
 */
export function LearningArticlePage() {
  const { articleId = '' } = useParams()
  const location = useLocation()
  const { settled, concealed } = useSettledAuth()
  const learningReturn = carriedLearningReturn(location.state)
  const [state, setState] = useState<ArticleState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  /** 화면을 둔 채 글을 다시 받은 횟수. 확인 문제를 새 글로 다시 그리고 완료 판정을 처음부터 한다 */
  const [revision, setRevision] = useState(0)
  const articleIdRef = useRef(articleId)
  useLayoutEffect(() => {
    articleIdRef.current = articleId
  })

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    void fetchLearningArticle(articleId, controller.signal).then((result) => {
      if (active) setState(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [articleId, attempt])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  /**
   * 화면을 둔 채 글을 다시 받는다. 다른 곳에서 확인 문제나 글이 바뀌었을 때 쓴다(계약 §5). 글 화면을 내리지 않으므로
   * 이번에 받은 경험치와 스크롤 위치를 잃지 않는다. 볼 수 없는 글이 되었으면 그 문장으로 바꾸고, 받지 못하면 지금
   * 글을 그대로 두어 다시 누를 수 있게 한다.
   */
  const refresh = useCallback(() => {
    const requested = articleId
    void fetchLearningArticle(requested).then((result) => {
      if (articleIdRef.current !== requested) return
      if (result.status === 'loaded') {
        setState(result)
        setRevision((count) => count + 1)
      } else if (result.status === 'unavailable') {
        setState(result)
      }
    })
  }, [articleId])

  // 도움말에서 온 돌아갈 자리(term-help-ui §3)를 목록으로 옮겨 가도 잃지 않게 받은 state를 넘긴다.
  // 목록으로 돌아가는 길. 전략 선택 화면의 되돌아가기와 같은 모양(화살표 + 글자)이다. 첫 단계 공개 전 글이면
  // 글 끝의 출처 줄 왼쪽에, 글이 없으면(볼 수 없음·실패) 바닥 줄에 둔다.
  const listLink = (
    <Link
      className={styles.listLink}
      to="/learn"
      state={learningReturn ? { learningReturn } : undefined}
    >
      <ArrowLeft size={15} aria-hidden="true" />
      목록으로 돌아가기
    </Link>
  )

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        <ReturnLink state={location.state} />

        {state.status === 'loading' ? (
          <p className={styles.loading} role="status">
            글을 불러오는 중입니다.
          </p>
        ) : null}

        {state.status === 'loaded' ? (
          <ArticleView
            article={state.article}
            listLink={listLink}
            settled={settled}
            concealed={concealed}
            learningReturn={learningReturn}
            resumeRequested={resumeRequested(location.state)}
            revision={revision}
            onRefresh={refresh}
          />
        ) : null}

        {state.status === 'unavailable' ? (
          <p className={styles.unavailable}>지금은 볼 수 없는 글입니다.</p>
        ) : null}

        {state.status === 'failed' ? (
          <LearningLoadFailure
            message={state.kind === 'api' ? state.message : undefined}
            onRetry={retry}
          />
        ) : null}

        {state.status === 'unavailable' || state.status === 'failed' ? (
          <p className={styles.footer}>
            {/* 글을 받지 못해도 진단으로 가는 길은 상단 메뉴의 '전략 진단'이 남긴다(US1 시나리오 7, FR-015). */}
            {listLink}
          </p>
        ) : null}
      </main>
    </div>
  )
}

interface ArticleViewProps {
  article: LearningArticle
  listLink: ReactNode
  /** 마지막으로 확인된 로그인 상태(`useSettledAuth`) */
  settled: SettledAuth
  /** 로그인 상태를 다시 확인하는 중이라 기록 자리를 숨기기만 하는가(research R33) */
  concealed: boolean
  learningReturn?: LearningReturnState
  /** '이어서 읽기'로 열었는가. 받은 글 기록의 위치로 한 번 옮긴다 */
  resumeRequested: boolean
  /** 화면을 둔 채 글을 다시 받은 횟수 */
  revision: number
  /** 화면을 둔 채 글을 다시 받는다(확인 문제나 글이 바뀌었을 때) */
  onRefresh: () => void
}

function ArticleView({
  article,
  listLink,
  settled,
  concealed,
  learningReturn,
  resumeRequested,
  revision,
  onRefresh,
}: ArticleViewProps) {
  const articleRef = useRef<HTMLElement>(null)
  const resumeRef = useRef<HTMLDivElement>(null)
  const resumedRef = useRef(false)
  const { recheck } = useAuth()
  const sessionKey = sessionKeyOf(settled)
  // 기록 요청이 로그인 만료로 거부되었는가. 만료 동안에만 '기록되지 않았습니다'를 보이는 근거다(spec Edge Cases).
  const [rejected, setRejected] = useState(false)
  // 기록은 공개된 단계의 새 기준 글에서만 한다. 이전 판·첫 단계 공개 전 글은 기록하지 않는다(FR-031).
  const recording = article.edition === 'CURRENT' && settled.kind === 'authenticated'
  const learner = useLearnerArticle(article.articleId, recording, sessionKey, {
    onUnauthenticated: () => setRejected(true),
  })
  const record = learner.load.status === 'loaded' ? learner.load.record : null
  useRestoreFocusAfterConceal(resumeRef, concealed)

  // 만료 뒤 다시 로그인했거나 비로그인으로 확인되면 앞선 거부를 잊는다. 거부 직후에 상태가 만료로 바뀌므로
  // 만료로 바뀔 때는 지우지 않는다.
  useEffect(() => {
    if (sessionKey !== 'expired') setRejected(false)
  }, [sessionKey])

  useArticleCompletion({
    root: articleRef,
    active:
      recording &&
      record !== null &&
      record.state !== 'COMPLETED' &&
      learner.completion.status === 'idle',
    minDwellSeconds: article.edition === 'CURRENT' ? article.minDwellSeconds : 0,
    // 글을 다시 받으면 처음부터 센다. 다른 곳에서 글이 바뀐 뒤의 완료는 바뀐 글로 판정한다.
    sessionKey: `${article.articleId}|${sessionKey}|${revision}`,
    held: concealed,
    onReady: learner.complete,
  })
  const furthest = useReadingPosition({
    root: articleRef,
    articleId: article.articleId,
    active: recording,
    sessionKey,
  })

  // '이어서 읽기'로 열었으면 글 기록을 받은 뒤 한 번 그 위치로 옮긴다(FR-020, data-model §15). 다시 받은 기록으로
  // 다시 옮기지 않는다. 완료한 글은 맨 위에서 연다.
  useEffect(() => {
    if (!resumeRequested || resumedRef.current || record === null) return
    resumedRef.current = true
    if (record.state === 'READING' && record.percent > 0) {
      scrollToReadPercent(articleRef.current, record.percent)
    }
  }, [resumeRequested, record])

  const reloadArticle = () => {
    onRefresh()
    learner.reloadRecord()
  }
  const endMode: ArticleEndMode =
    settled.kind === 'anonymous'
      ? { kind: 'guest' }
      : settled.kind === 'authenticated'
        ? {
            kind: 'record',
            learner: { ...learner, reloadArticle, articleRevision: revision },
          }
        : settled.kind === 'expired'
          ? { kind: 'expired', rejected, onRetry: () => void recheck() }
          : { kind: 'none' }

  // '읽은 정도' 막대(목업 '글 끝'의 `rbar`). 받은 기록과 이 화면에서 잰 가장 큰 비율 가운데 큰 쪽이고, 완료한 글은
  // 다 찬다. 기록을 받지 못했으면 그리지 않는다. 그림이라 화면 낭독기는 읽지 않는다(design-notes §6).
  const completed = record?.state === 'COMPLETED' || learner.completion.status === 'done'
  const readPercent =
    recording && record !== null ? (completed ? 100 : Math.max(record.percent, furthest)) : null

  // 읽는 중 글을 목록·도움말·주소로 열면 맨 위에서 열고 이 줄을 보인다. 완료한 글에는 없다(FR-020).
  const resumeAt =
    recording &&
    record?.state === 'READING' &&
    record.percent > 0 &&
    learner.completion.status !== 'done'
      ? record.percent
      : null

  return (
    <article ref={articleRef} className={styles.article} aria-labelledby="learning-article-title">
      <header className={styles.header}>
        {readPercent !== null ? (
          <div
            className={concealed ? `${styles.readBar} ${styles.concealed}` : styles.readBar}
            aria-hidden="true"
          >
            <span className={styles.readBarFill} style={{ width: `${readPercent}%` }} />
          </div>
        ) : null}
        <ArticleHeadLine article={article} learningReturn={learningReturn} />
        <h1 id="learning-article-title" className={styles.title}>
          {article.title}
        </h1>
        {article.edition !== 'PRE_RELEASE' ? (
          <p className={styles.meta}>약 {article.readingMinutes}분</p>
        ) : null}
        <p className={styles.question}>{article.question}</p>
        {resumeAt !== null ? (
          <div ref={resumeRef} className={concealed ? styles.concealed : undefined}>
            <ResumeLine
              percent={resumeAt}
              onResume={() => {
                scrollToReadPercent(articleRef.current, resumeAt)
                focusReadPosition(articleRef.current, resumeAt)
              }}
            />
          </div>
        ) : null}
      </header>

      <ArticleBody article={article} learningReturn={learningReturn} />

      {/* 예시는 본문 끝 표지 뒤, 글 끝 자리 앞이다(FR-021). 예시가 없는 새 기준 글은 구역 전체가 없다(FR-003). */}
      {article.example ? (
        <ExampleSection articleId={article.articleId} example={article.example} />
      ) : null}

      {article.edition === 'CURRENT' ? (
        <ArticleEnd
          article={article}
          mode={endMode}
          concealed={concealed}
          learningReturn={learningReturn}
        />
      ) : null}

      {/*
        첫 단계 공개 전 글만 9월 판 글 끝(목록 연결과 출처 목록)을 그대로 둔다(FR-031). 새 기준 글은 출처를 글자 옆
        '(*출처)'로 보이고, 이전 판 글의 글 끝에는 기록하지 않는다는 한 줄과 목록 연결만 둔다(FR-007, FR-031).
      */}
      {article.edition === 'PRE_RELEASE' ? (
        <div className={styles.articleFooter}>
          {listLink}
          <SourceList article={article} />
        </div>
      ) : null}

      <EditionNotice edition={article.edition} place="end" learningReturn={learningReturn} />
    </article>
  )
}

/**
 * 제목 위. 첫 단계 공개 전 글은 1판 그대로 'LEARN'이다. 새 기준 글과 이전 판 글은 목업 '글 끝'처럼 경로
 * ('배우기 › 2단계 · 이름')를 두고, 그 아래에 단계 안 번호를 둔다. 이전 판 글은 번호 대신 '이전 판' 표시다
 * (FR-031). 경로의 두 연결은 목록으로 가므로 받은 돌아갈 자리를 싣는다(term-help-ui §3).
 */
function ArticleHeadLine({
  article,
  learningReturn,
}: {
  article: LearningArticle
  learningReturn?: LearningReturnState
}) {
  if (article.edition === 'PRE_RELEASE') {
    return (
      <p className={heading.eyebrow} aria-hidden="true">
        LEARN
      </p>
    )
  }
  const carried = learningReturn ? { learningReturn } : undefined
  return (
    <>
      <nav className={styles.crumb} aria-label="경로">
        <Link to="/learn" state={carried}>
          배우기
        </Link>
        <span className={styles.crumbSeparator} aria-hidden="true">
          ›
        </span>
        {/* 배우기 목록의 그 단계 자리로 간다. 공개 안내의 '○단계 보기'와 같은 주소 조각이다(`/learn#stage-n`). */}
        <Link to={{ pathname: '/learn', hash: stageHash(article.stage.number) }} state={carried}>
          {article.stage.number}단계 · {article.stage.name}
        </Link>
      </nav>
      {article.edition === 'PREVIOUS' ? (
        <p className={styles.number}>
          <EditionNotice edition={article.edition} place="head" />
        </p>
      ) : article.number ? (
        <p className={styles.number}>{article.number}</p>
      ) : null}
    </>
  )
}

function ExampleSection({ articleId, example }: { articleId: string; example: LearningExample }) {
  return (
    <section className={styles.example} aria-labelledby="learning-example-title">
      {/* 왼쪽 절반은 예시 섹터와 비중, 오른쪽 절반은 그 예시로 시작하는 행동이다. */}
      <h2 id="learning-example-title" className={styles.sectionTitle}>
        예시
      </h2>
      <div className={styles.exampleSectors}>
        <ul className={styles.exampleList}>
          {example.sectors.map((sector) => (
            <li key={sector.code}>
              <span>{sector.name ?? sector.code}</span>
              <span className={styles.weight}>{formatWeight(sector.weightTenths)}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className={styles.exampleAction}>
        <ExampleEntry articleId={articleId} example={example} />
      </div>
      <p className={styles.disclaimer}>설명을 위한 예시이며 투자 권고가 아닙니다.</p>
    </section>
  )
}

function SourceList({ article }: { article: LearningArticle }) {
  if (article.sources.length === 0) return null
  return (
    <section className={styles.sources} aria-labelledby="learning-sources-title">
      {/* '출처'와 기관 이름을 한 줄에 잇는다. 주소가 있으면 밑줄과 화살표로 링크임을 보인다. */}
      <h2 id="learning-sources-title" className={styles.sourcesLabel}>
        출처
      </h2>
      <ul>
        {article.sources.map((source, index) => (
          <li key={index}>
            {source.url ? (
              <a href={source.url} target="_blank" rel="noopener noreferrer">
                {source.label}
                <ArrowUpRight size={14} aria-hidden="true" />
              </a>
            ) : (
              source.label
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

/** 0.1%p 정수를 소수 한 자리 %로 보인다. 1000이 100%다. */
function formatWeight(weightTenths: number): string {
  return `${(weightTenths / 10).toFixed(1)}%`
}

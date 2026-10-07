import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowUpRight } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import { fetchCurriculum, type CurriculumResult } from '@/api/learning'
import { useAuth } from '@/auth/auth-context'
import { useSettledAuth, type SettledAuth } from '@/components/learning/article/useSettledAuth'
import { ChangeLog } from '@/components/learning/curriculum/ChangeLog'
import { READING_ORDER_NOTE } from '@/components/learning/curriculum/curriculumCopy'
import { JourneyMap } from '@/components/learning/curriculum/JourneyMap'
import { ListView } from '@/components/learning/curriculum/ListView'
import { dismissNotices, readDismissedNotices } from '@/components/learning/curriculum/noticeStorage'
import { ReleaseNotice } from '@/components/learning/curriculum/ReleaseNotice'
import { moveToStage } from '@/components/learning/curriculum/stageAnchors'
import { ViewToggle } from '@/components/learning/curriculum/ViewToggle'
import { readStoredView, type LearningView } from '@/components/learning/curriculum/viewStorage'
import { articlePath, stageNumberFromHash } from '@/components/learning/learningPaths'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import { GuestSample } from '@/components/learning/me/GuestSample'
import { AppHeader } from '@/components/navigation/AppHeader'
import type { Curriculum, LearningArticleSummary } from '@/types/learning'
import heading from '@/components/common/PageHeading.module.css'
import styles from './LearningListPage.module.css'

type ListState = { status: 'loading' } | CurriculumResult

/**
 * 배우기 목록(`/learn`, 013 US1·FR-023·FR-031).
 *
 * <p><b>첫 단계를 공개하기 전에는 9월 판 목록 그대로다.</b> 커리큘럼의 `preReleaseArticles`를 서버가 준 순서대로
 * 보이고 보기 전환·공개 안내·나의 학습 요소를 두지 않는다(FR-031 "지금 모습 그대로"). `preReleaseArticles`는 공개
 * 전에만 배열이므로(계약 §1, `readCurriculum`이 공개한 단계 수와 맞는지 확인한다) 그 값으로 두 모습을 가른다.
 *
 * <p><b>공개한 뒤에는 단계별 커리큘럼이다.</b> 보기 전환, 공개 안내, 커리큘럼 보기(여정 지도) 또는 목록 보기, 공개
 * 경계, 변경 기록을 보인다. 비로그인 사용자에게는 기록 자리에 견본 그림과 로그인 연결을 둔다. 로그인 사용자의 요약
 * 패널과 글별 상태는 '나의 학습'(B13)이 더한다. 읽는 순서는 제안이고 어느 글도 잠그지 않는다(FR-023).
 *
 * <p><b>학습 기록을 브라우저 저장소에 쓰지 않는다.</b> 이 화면이 쓰는 키는 탭 단위 화면 상태 둘(고른 보기
 * `thorp.learning.view`, 닫은 안내 `thorp.learning.dismissedNotices`)이고, 로그인 연결을 누르면 014가 돌아올 자리를
 * 적는다. 목록을 받지 못해도 진단으로 가는 길(상단 메뉴)은 그대로다.
 */
export function LearningListPage() {
  const location = useLocation()
  const { status: authStatus } = useAuth()
  const { settled } = useSettledAuth()
  const [state, setState] = useState<ListState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    void fetchCurriculum(controller.signal).then((result) => {
      if (active) setState(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [attempt])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  // 공개 안내의 '○단계 보기'와 글 머리의 단계 연결(`/learn#stage-n`)이 가리키는 단계 자리로 옮기고 그 제목에 초점을
  // 둔다. 같은 주소를 다시 눌러도 옮기도록 이동마다(`location.key`) 한 번 한다.
  //
  // 로그인 상태를 확인하는 동안에는 기다린다. 확인이 끝나 비로그인 견본이 목록 위에 들어오면 먼저 옮긴 자리가 그만큼
  // 아래로 밀리기 때문이다. 014는 탭이 다시 보일 때마다 상태를 `checking`으로 두므로, 같은 이동에서 두 번 옮기지 않게
  // 옮긴 이동을 기억한다.
  const loaded = state.status === 'loaded'
  const authChecking = authStatus === 'checking'
  const movedFor = useRef<string | null>(null)
  useEffect(() => {
    if (!loaded || authChecking || movedFor.current === location.key) return
    movedFor.current = location.key
    const stageNumber = stageNumberFromHash(location.hash)
    if (stageNumber === null) return
    moveToStage(stageNumber)
  }, [loaded, authChecking, location.hash, location.key])

  const curriculum = state.status === 'loaded' ? state.curriculum : null

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="learning-list-title">
        {/* 다른 주요 화면과 같은 머리: 제목은 왼쪽, 안내 문장은 오른쪽 인용 모양이다. */}
        <header className={heading.hero}>
          <div>
            <p className={heading.eyebrow} aria-hidden="true">LEARN</p>
            <h1 id="learning-list-title" className={styles.title}>
              <em className={heading.emphasis}>배우기</em>
            </h1>
          </div>
          {/*
            첫 단계 공개 전과 뒤에 같은 문장이다. 응답을 받기 전에는 공개 여부를 모르므로, 문장이 갈리면 불러오는 동안
            보인 문장이 받은 뒤 바뀐다.
          */}
          <p className={heading.lead}>
            <span>투자 개념과 지표 읽는 법을 짧은 글로 익힙니다.</span>
            <span>글에 나온 예시는 그대로 전략 진단으로 이어 볼 수 있습니다.</span>
          </p>
        </header>

        {state.status === 'loading' ? (
          <p className={styles.loading} role="status">
            글 목록을 불러오는 중입니다.
          </p>
        ) : null}

        {curriculum?.preReleaseArticles ? (
          <PreReleaseList articles={curriculum.preReleaseArticles} linkState={location.state} />
        ) : null}

        {curriculum && curriculum.preReleaseArticles === null ? (
          <ReleasedCurriculum curriculum={curriculum} auth={settled.kind} linkState={location.state} />
        ) : null}

        {state.status === 'failed' ? (
          <LearningLoadFailure
            message={state.kind === 'api' ? state.message : undefined}
            onRetry={retry}
          />
        ) : null}
      </main>
    </div>
  )
}

/**
 * 첫 단계 공개 전 목록. 9월 판 목록 화면과 같은 모양·같은 문장이다(FR-031). 번호는 서버가 준 목록의 자리이고 읽는
 * 순서를 강제하지 않는다(FR-001).
 */
function PreReleaseList({ articles, linkState }: { articles: LearningArticleSummary[]; linkState: unknown }) {
  if (articles.length === 0) return <p className={styles.empty}>아직 게시된 글이 없습니다.</p>
  return (
    <ol className={styles.list}>
      {articles.map((article, index) => (
        <li key={article.articleId}>
          {/* 도움말에서 온 돌아갈 자리를 다른 글로 옮겨 가도 잃지 않게 받은 state를 넘긴다. */}
          <Link className={styles.item} to={articlePath(article.articleId)} state={linkState}>
            <span className={styles.itemIndex} aria-hidden="true">
              {String(index + 1).padStart(2, '0')}
            </span>
            <span className={styles.itemTitle}>{article.title}</span>
            <span className={styles.itemQuestion}>{article.question}</span>
            <ArrowUpRight className={styles.itemArrow} size={18} aria-hidden="true" />
          </Link>
        </li>
      ))}
    </ol>
  )
}

interface ReleasedCurriculumProps {
  curriculum: Curriculum
  /** 마지막으로 확인된 로그인 상태. 비로그인이면 견본을, 로그인이면 공개 안내에 기록 문장을 더한다 */
  auth: SettledAuth['kind']
  linkState: unknown
}

/**
 * 첫 단계를 공개한 뒤의 목록: 기록 자리, 공개 안내, 보기 전환, 커리큘럼 보기 또는 목록 보기, 변경 기록.
 *
 * <p>고른 보기와 닫은 안내는 이 부분만 쓰는 탭 단위 화면 상태라 여기서 읽고 쓴다. 첫 단계 공개 전 목록은 이 부분을
 * 그리지 않으므로 저장소를 읽지도 쓰지도 않는다(quickstart 4-1).
 */
function ReleasedCurriculum({ curriculum, auth, linkState }: ReleasedCurriculumProps) {
  const [view, setView] = useState<LearningView>(readStoredView)
  const [dismissed, setDismissed] = useState<string[]>(readDismissedNotices)
  const [changeLogOpen, setChangeLogOpen] = useState(false)
  const pressedViewRef = useRef<HTMLButtonElement>(null)
  const changeLogSummaryRef = useRef<HTMLElement>(null)

  const notices = curriculum.notices.filter((entry) => !dismissed.includes(entry.entryKey))

  // [닫기]를 누르면 안내와 함께 그 단추가 사라진다. 초점이 문서 처음으로 떨어지지 않게 눌린 보기 단추로 옮긴다
  // (목업의 닫기 동작).
  const dismiss = (entryKeys: string[]) => {
    pressedViewRef.current?.focus()
    setDismissed(dismissNotices(entryKeys))
  }

  // 공개 안내의 '변경 기록': 변경 기록을 펼치고 그 펼침 단추로 옮긴다.
  const openChangeLog = () => {
    setChangeLogOpen(true)
    changeLogSummaryRef.current?.scrollIntoView?.({ block: 'start' })
    changeLogSummaryRef.current?.focus({ preventScroll: true })
  }

  return (
    <>
      {auth === 'anonymous' ? <GuestSample place="list" /> : null}

      <ReleaseNotice
        notices={notices}
        signedIn={auth === 'authenticated'}
        onDismiss={dismiss}
        onOpenChangeLog={openChangeLog}
        linkState={linkState}
      />

      <div className={styles.viewRow}>
        <ViewToggle view={view} onChange={setView} pressedRef={pressedViewRef} />
        <p className={styles.order}>
          <ReadingOrderIcon />
          <span>{READING_ORDER_NOTE}</span>
        </p>
      </div>

      {view === 'curriculum' ? (
        <JourneyMap stages={curriculum.stages} linkState={linkState} />
      ) : (
        <ListView stages={curriculum.stages} linkState={linkState} />
      )}

      <ChangeLog
        entries={curriculum.changeLog}
        open={changeLogOpen}
        onToggle={setChangeLogOpen}
        summaryRef={changeLogSummaryRef}
      />
    </>
  )
}

/** 완만한 곡선 위 조약돌 세 개(가운데만 채움). 순서가 제안이라는 문장 옆의 그림이다. 색은 CSS가 정한다. */
function ReadingOrderIcon() {
  return (
    <svg width="38" height="20" viewBox="0 0 38 20" aria-hidden="true" focusable="false">
      <path className={styles.orderPath} d="M4 13 C12 5 26 5 34 13" strokeWidth="2" strokeLinecap="round" />
      <circle className={styles.orderStop} cx="5" cy="12.5" r="3.6" strokeWidth="1.3" />
      <circle className={styles.orderStopFilled} cx="19" cy="7" r="3.8" />
      <circle className={styles.orderStop} cx="33" cy="12.5" r="3.6" strokeWidth="1.3" />
    </svg>
  )
}

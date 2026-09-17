import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { fetchLearningArticles, type LearningListResult } from '@/api/learning'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import { AppHeader } from '@/components/navigation/AppHeader'
import styles from './LearningListPage.module.css'

type ListState = { status: 'loading' } | LearningListResult

/**
 * 배우기 주제 목록(`/learn`, 013 US1).
 *
 * <p><b>서버가 준 순서 그대로 보인다.</b> 읽는 순서는 제안일 뿐이라 화면이 다시 정렬하거나 순서를
 * 강제하지 않는다(013 FR-001).
 *
 * <p><b>배우기는 진단의 전제가 아니다.</b> 설문·숙련도 질문·진도 표시를 두지 않고, 브라우저 저장소에
 * 아무것도 쓰지 않는다(013 FR-011·FR-012). 목록을 받지 못해도 진단을 시작하는 연결은 그대로 둔다.
 */
export function LearningListPage() {
  const location = useLocation()
  const [state, setState] = useState<ListState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    void fetchLearningArticles(controller.signal).then((result) => {
      if (active) setState(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [attempt])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="learning-list-title">
        <h1 id="learning-list-title" className={styles.title}>
          배우기
        </h1>

        {state.status === 'loading' ? (
          <p className={styles.loading} role="status">
            글 목록을 불러오는 중입니다.
          </p>
        ) : null}

        {state.status === 'loaded' ? (
          <ol className={styles.list}>
            {state.articles.map((article) => (
              <li key={article.articleId}>
                {/* 도움말에서 온 돌아갈 자리를 다른 글로 옮겨 가도 잃지 않게 받은 state를 넘긴다. */}
                <Link
                  className={styles.item}
                  to={`/learn/${encodeURIComponent(article.articleId)}`}
                  state={location.state}
                >
                  <span className={styles.itemTitle}>{article.title}</span>
                  <span className={styles.itemQuestion}>{article.question}</span>
                </Link>
              </li>
            ))}
          </ol>
        ) : null}

        {state.status === 'empty' ? <p className={styles.empty}>아직 게시된 글이 없습니다.</p> : null}

        {state.status === 'failed' ? (
          <LearningLoadFailure
            message={state.kind === 'api' ? state.message : undefined}
            onRetry={retry}
          />
        ) : null}

        {/* 목록의 상태와 무관하게 늘 둔다. 응답이 늦어도 진단 진입을 기다리게 하지 않는다(FR-015). */}
        <p className={styles.diagnosis}>
          <Link to="/diagnosis/sectors">글을 읽지 않고 바로 비중 비교 시작하기</Link>
        </p>
      </main>
    </div>
  )
}

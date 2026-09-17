import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { fetchLearningArticle, type LearningArticleResult } from '@/api/learning'
import { ArticleBlocks } from '@/components/learning/ArticleBlocks'
import { ExampleEntry } from '@/components/learning/ExampleEntry'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import { ReturnToDiagnosisLink } from '@/components/learning/ReturnToDiagnosisLink'
import { AppHeader } from '@/components/navigation/AppHeader'
import type { LearningArticle } from '@/types/learning'
import styles from './LearningArticlePage.module.css'

type ArticleState = { status: 'loading' } | LearningArticleResult

/**
 * 배우기 글 하나(`/learn/:articleId`, 013 US1·US2).
 *
 * <p><b>응답만으로 그린다.</b> 제목·질문·본문·예시·출처가 모두 공개 중인 판에서 온다(013 FR-015).
 *
 * <p><b>볼 수 없는 글은 오류가 아니다.</b> 없는 글과 게시되지 않은 글을 서버가 가르지 않으므로 화면도
 * 한 문장과 목록 연결만 보인다(spec Edge Cases). 받지 못한 것만 다시 시도를 준다.
 *
 * <p>브라우저 저장소에 아무것도 쓰지 않는다(013 FR-012).
 */
export function LearningArticlePage() {
  const { articleId = '' } = useParams()
  const location = useLocation()
  const [state, setState] = useState<ArticleState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

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

  // 도움말에서 온 돌아갈 자리(term-help-ui §3)를 목록으로 옮겨 가도 잃지 않게 받은 state를 넘긴다.
  const listLink = (
    <Link className={styles.listLink} to="/learn" state={location.state}>
      주제 목록으로
    </Link>
  )

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        <ReturnToDiagnosisLink state={location.state} />

        {state.status === 'loading' ? (
          <p className={styles.loading} role="status">
            글을 불러오는 중입니다.
          </p>
        ) : null}

        {state.status === 'loaded' ? <ArticleView article={state.article} /> : null}

        {state.status === 'unavailable' ? (
          <p className={styles.unavailable}>지금은 볼 수 없는 글입니다.</p>
        ) : null}

        {state.status === 'failed' ? (
          <LearningLoadFailure
            message={state.kind === 'api' ? state.message : undefined}
            onRetry={retry}
          />
        ) : null}

        {state.status !== 'loading' ? (
          <p className={styles.footer}>
            {listLink}
            {/* 글을 받지 못해도 진단을 시작하는 길은 막지 않는다(US1 시나리오 7, FR-015). */}
            {state.status === 'failed' ? (
              <Link className={styles.listLink} to="/diagnosis/sectors">
                글을 읽지 않고 바로 비중 비교 시작하기
              </Link>
            ) : null}
          </p>
        ) : null}
      </main>
    </div>
  )
}

function ArticleView({ article }: { article: LearningArticle }) {
  return (
    <article className={styles.article} aria-labelledby="learning-article-title">
      <header className={styles.header}>
        <h1 id="learning-article-title" className={styles.title}>
          {article.title}
        </h1>
        <p className={styles.question}>{article.question}</p>
      </header>

      <ArticleBlocks blocks={article.blocks} />

      <section className={styles.example} aria-labelledby="learning-example-title">
        <h2 id="learning-example-title" className={styles.sectionTitle}>
          예시
        </h2>
        <ul className={styles.exampleList}>
          {article.example.sectors.map((sector) => (
            <li key={sector.code}>
              <span>{sector.name ?? sector.code}</span>
              <span className={styles.weight}>{formatWeight(sector.weightTenths)}</span>
            </li>
          ))}
        </ul>
        <p className={styles.disclaimer}>설명을 위한 예시이며 투자 권고가 아닙니다.</p>
        <ExampleEntry articleId={article.articleId} example={article.example} />
      </section>

      {article.sources.length > 0 ? (
        <section className={styles.sources} aria-labelledby="learning-sources-title">
          <h2 id="learning-sources-title" className={styles.sectionTitle}>
            출처
          </h2>
          <ul>
            {article.sources.map((source, index) => (
              <li key={index}>
                {source.url ? (
                  <a href={source.url} target="_blank" rel="noopener noreferrer">
                    {source.label}
                  </a>
                ) : (
                  source.label
                )}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <p className={styles.published}>
        게시 <time dateTime={article.publishedAt}>{formatPublishedAt(article.publishedAt)}</time>
      </p>
    </article>
  )
}

/** 0.1%p 정수를 소수 한 자리 %로 보인다. 1000이 100%다. */
function formatWeight(weightTenths: number): string {
  return `${(weightTenths / 10).toFixed(1)}%`
}

function formatPublishedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })
}

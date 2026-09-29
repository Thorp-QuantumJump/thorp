import { ArrowLeft, ArrowUpRight } from 'lucide-react'
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { fetchLearningArticle, type LearningArticleResult } from '@/api/learning'
import { ArticleBlocks } from '@/components/learning/ArticleBlocks'
import { ExampleEntry } from '@/components/learning/ExampleEntry'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import { ReturnToDiagnosisLink } from '@/components/learning/ReturnToDiagnosisLink'
import { AppHeader } from '@/components/navigation/AppHeader'
import type { LearningArticle } from '@/types/learning'
import heading from '@/components/common/PageHeading.module.css'
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
  // 목록으로 돌아가는 길. 전략 선택 화면의 되돌아가기와 같은 모양(화살표 + 글자)이다. 글이 보이면 글 끝의 출처 줄
  // 왼쪽에, 글이 없으면(볼 수 없음·실패) 바닥 줄에 둔다.
  const listLink = (
    <Link className={styles.listLink} to="/learn" state={location.state}>
      <ArrowLeft size={15} aria-hidden="true" />
      목록으로 돌아가기
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

        {state.status === 'loaded' ? <ArticleView article={state.article} listLink={listLink} /> : null}

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
            {listLink}
            {/* 글을 받지 못해도 진단을 시작하는 길은 막지 않는다(US1 시나리오 7, FR-015). */}
            {state.status === 'failed' ? (
              <Link className={styles.listLink} to="/diagnosis/sectors">
                글을 읽지 않고 바로 전략 진단하기
              </Link>
            ) : null}
          </p>
        ) : null}
      </main>
    </div>
  )
}

function ArticleView({ article, listLink }: { article: LearningArticle; listLink: ReactNode }) {
  return (
    <article className={styles.article} aria-labelledby="learning-article-title">
      <header className={styles.header}>
        <p className={heading.eyebrow} aria-hidden="true">LEARN</p>
        <h1 id="learning-article-title" className={styles.title}>
          {article.title}
        </h1>
        <p className={styles.question}>{article.question}</p>
      </header>

      <ArticleBlocks blocks={article.blocks} />

      <section className={styles.example} aria-labelledby="learning-example-title">
        {/* 왼쪽 절반은 예시 섹터와 비중, 오른쪽 절반은 그 예시로 시작하는 행동이다. */}
        <h2 id="learning-example-title" className={styles.sectionTitle}>
          예시
        </h2>
        <div className={styles.exampleSectors}>
          <ul className={styles.exampleList}>
            {article.example.sectors.map((sector) => (
              <li key={sector.code}>
                <span>{sector.name ?? sector.code}</span>
                <span className={styles.weight}>{formatWeight(sector.weightTenths)}</span>
              </li>
            ))}
          </ul>
        </div>
        <div className={styles.exampleAction}>
          <ExampleEntry articleId={article.articleId} example={article.example} />
        </div>
        <p className={styles.disclaimer}>설명을 위한 예시이며 투자 권고가 아닙니다.</p>
      </section>

      {/* 글 끝 한 줄: 왼쪽은 목록으로 돌아가기, 오른쪽은 출처. */}
      <div className={styles.articleFooter}>
        {listLink}
        {article.sources.length > 0 ? (
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
        ) : null}
      </div>
    </article>
  )
}

/** 0.1%p 정수를 소수 한 자리 %로 보인다. 1000이 100%다. */
function formatWeight(weightTenths: number): string {
  return `${(weightTenths / 10).toFixed(1)}%`
}

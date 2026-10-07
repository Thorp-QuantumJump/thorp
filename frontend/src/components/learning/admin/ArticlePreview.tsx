import { ArrowUpRight } from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  fetchArticlePreview,
  type ArticlePreviewResult,
  type IncompleteQuestion,
} from '@/api/learningAdmin'
import type { LearningArticleContent } from '@/api/learning'
import { ArticleBody } from '@/components/learning/article/ArticleBody'
import { EditionNotice } from '@/components/learning/article/EditionNotice'
import { QuizPanel } from '@/components/learning/article/QuizPanel'
import { ExampleEntry } from '@/components/learning/ExampleEntry'
import type { Edition, LearningExample } from '@/types/learning'
import reader from '@/pages/LearningArticlePage.module.css'
import styles from './AdminEditor.module.css'

/**
 * 지금 서버 초안을 독자 화면과 똑같이 그린다(013 FR-016, contracts/learning-admin-api §8, T172).
 *
 * <p><b>독자 화면(`LearningArticlePage`)과 같은 구성이다.</b> 판에 따라 머리(이전 판 표시·예상 읽기 시간),
 * 본문(`ArticleBody`), 예시(섹터·비중과 '투자 권고가 아닙니다'), 확인 문제(새 기준 글), 9월 판 출처 목록(첫 단계
 * 공개 전 글), 이전 판 글 끝 안내를 같은 순서로 둔다. 모양도 독자 화면의 CSS 모듈을 그대로 쓴다. 공용 부품으로
 * 빼면 B11·B12가 고치는 글 화면 파일과 겹쳐, 지금은 같은 구조를 따르기만 한다.
 *
 * <p><b>저장된 초안을 그린다.</b> 미리 보기 응답은 서버 초안에서 오므로 저장하지 않은 편집은 보이지 않는다.
 * 그 사실을 한 줄로 알린다.
 *
 * <p><b>학습 기록과 완료 카드는 없다.</b> 확인 문제만 비로그인 모드로 풀어 볼 수 있다. 비로그인 모드는 요청도
 * 브라우저 저장도 하지 않으므로(QuizPanel) 미리 보기가 나의 학습을 바꾸지 않는다. 게시 전 검사는 하지 않는다 —
 * 검사 결과는 게시나 단계 미리 검사로 본다. 그래서 정답 보기가 하나가 아닌 문제처럼 독자 화면이 그릴 수 없는
 * 자리는 그 사실을 글로 보인다.
 */

interface ArticlePreviewProps {
  articleId: string
  /** 저장하지 않은 편집이 있는가 */
  dirty: boolean
  /** 401을 받았다. 편집 화면이 재로그인 안내로 넘긴다 */
  onUnauthenticated: () => void
}

type PreviewState = { status: 'closed' } | { status: 'loading' } | ArticlePreviewResult

/** 게시하면 될 판의 이름이다. 미리 보기는 아직 게시되지 않았다. */
const EDITION_NAMES: Record<Edition, string> = {
  CURRENT: '새 기준 글로 보입니다',
  PRE_RELEASE: '첫 단계 공개 전 모습으로 보입니다',
  PREVIOUS: '이전 판으로 보입니다',
}

export function ArticlePreview({ articleId, dirty, onUnauthenticated }: ArticlePreviewProps) {
  const [state, setState] = useState<PreviewState>({ status: 'closed' })
  /** 다시 불러올 때마다 확인 문제의 답을 처음 상태로 돌린다 */
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (attempt === 0) return
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    void fetchArticlePreview(articleId, controller.signal).then((result) => {
      if (!active) return
      if (result.status === 'unauthenticated') onUnauthenticated()
      setState(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [articleId, attempt, onUnauthenticated])

  return (
    <section className={styles.editor} aria-labelledby="article-preview-title">
      <h2 id="article-preview-title">미리 보기</h2>
      <p className={styles.guide}>
        저장된 초안을 독자 화면과 같은 모습으로 보입니다. 학습 기록과 완료 카드 없이 확인 문제만
        기록하지 않고 풀어 볼 수 있습니다. 게시 전 검사는 하지 않습니다.
        {dirty ? ' 저장하지 않은 변경은 미리 보기에 없습니다.' : ''}
      </p>
      <div className={styles.controls}>
        <button type="button" onClick={() => setAttempt((count) => count + 1)}>
          {state.status === 'closed' ? '미리 보기 열기' : '미리 보기 새로 고침'}
        </button>
        {state.status === 'closed' ? null : (
          <button type="button" onClick={() => setState({ status: 'closed' })}>
            미리 보기 닫기
          </button>
        )}
      </div>

      {state.status === 'loading' ? <p role="status">미리 보기를 불러오는 중입니다.</p> : null}
      {state.status === 'notFound' ? <p role="alert">없는 글입니다.</p> : null}
      {state.status === 'failed' ? <p role="alert">{state.message}</p> : null}

      {state.status === 'loaded' ? (
        <PreviewArticle
          key={attempt}
          article={state.preview.article}
          incompleteQuestions={state.preview.incompleteQuestions}
        />
      ) : null}
    </section>
  )
}

function PreviewArticle({
  article,
  incompleteQuestions,
}: {
  article: LearningArticleContent
  incompleteQuestions: IncompleteQuestion[]
}) {
  return (
    <article className={reader.article} aria-label={`${article.title} 미리 보기`}>
      <header className={reader.header}>
        <p className={styles.hint}>
          {article.stage.number}단계 · {article.stage.name} · {EDITION_NAMES[article.edition]}
        </p>
        {article.edition === 'PREVIOUS' ? (
          <p className={reader.number}>
            <EditionNotice edition={article.edition} place="head" />
          </p>
        ) : null}
        <h3 className={reader.title}>{article.title}</h3>
        {article.edition !== 'PRE_RELEASE' ? (
          <p className={reader.meta}>약 {article.readingMinutes}분</p>
        ) : null}
        <p className={reader.question}>{article.question}</p>
      </header>

      <ArticleBody article={article} />

      {article.example ? (
        <PreviewExample articleId={article.articleId} example={article.example} />
      ) : null}

      {article.edition === 'CURRENT' && article.quiz.length > 0 ? (
        <QuizPanel questions={article.quiz} mode="guest" />
      ) : null}

      {incompleteQuestions.length > 0 ? (
        <div role="note" className={styles.notice}>
          <p>
            정답 보기가 하나가 아닌 문제 {incompleteQuestions.length}개는 독자 화면에 그릴 수 없어
            미리 보기에서 뺐습니다. 게시 전 검사가 막는 자리입니다.
          </p>
          <ul className={styles.issues}>
            {incompleteQuestions.map((question) => (
              <li key={question.questionId}>
                {question.questionId}: {question.prompt === '' ? '(질문 없음)' : question.prompt}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {/* 독자 화면처럼 9월 판 글 끝 출처 목록은 첫 단계 공개 전 글에만 둔다(FR-007, FR-031). */}
      {article.edition === 'PRE_RELEASE' && article.sources.length > 0 ? (
        <section className={reader.sources} aria-label="출처">
          <h4 className={reader.sourcesLabel}>출처</h4>
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

      <EditionNotice edition={article.edition} place="end" />
    </article>
  )
}

/** 독자 화면의 예시 구역과 같은 구성이다. 섹터 이름은 지금 대응표의 이름이고 없으면 코드다. */
function PreviewExample({ articleId, example }: { articleId: string; example: LearningExample }) {
  return (
    <section className={reader.example} aria-label="예시">
      <h4 className={reader.sectionTitle}>예시</h4>
      <div className={reader.exampleSectors}>
        <ul className={reader.exampleList}>
          {example.sectors.map((sector) => (
            <li key={sector.code}>
              <span>{sector.name ?? sector.code}</span>
              <span className={reader.weight}>{`${(sector.weightTenths / 10).toFixed(1)}%`}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className={reader.exampleAction}>
        <ExampleEntry articleId={articleId} example={example} />
      </div>
      <p className={reader.disclaimer}>설명을 위한 예시이며 투자 권고가 아닙니다.</p>
    </section>
  )
}

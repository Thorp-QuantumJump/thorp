import { useEffect, useState } from 'react'
import { fetchArticlePreview, type ArticlePreviewResult } from '@/api/learningAdmin'
import { ArticleBody } from '@/components/learning/article/ArticleBody'
import { EditionNotice } from '@/components/learning/article/EditionNotice'
import { QuizPanel } from '@/components/learning/article/QuizPanel'
import { ExampleEntry } from '@/components/learning/ExampleEntry'
import type { Edition } from '@/types/learning'
import styles from './AdminEditor.module.css'

/**
 * 지금 서버 초안을 독자 화면과 같은 부품으로 그린다(013 FR-016, contracts/learning-admin-api §8, T172).
 *
 * <p><b>저장된 초안을 그린다.</b> 미리 보기 응답은 서버 초안에서 오므로 저장하지 않은 편집은 보이지 않는다.
 * 그 사실을 한 줄로 알린다.
 *
 * <p><b>학습 기록과 완료 카드는 없다.</b> 확인 문제만 비로그인 모드로 풀어 볼 수 있다. 비로그인 모드는 요청도
 * 브라우저 저장도 하지 않으므로(QuizPanel) 미리 보기가 나의 학습을 바꾸지 않는다. 게시 전 검사는 하지 않는다 —
 * 검사 결과는 게시나 단계 미리 검사로 본다.
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
        <article className={styles.item} aria-label={`${state.article.title} 미리 보기`}>
          <p className={styles.hint}>
            {state.article.stage.number}단계 · {state.article.stage.name} ·{' '}
            {EDITION_NAMES[state.article.edition]}
          </p>
          <h3>{state.article.title}</h3>
          {state.article.edition === 'PRE_RELEASE' ? null : (
            <p className={styles.hint}>약 {state.article.readingMinutes}분</p>
          )}
          <p>{state.article.question}</p>
          <ArticleBody article={state.article} />
          {state.article.example ? (
            <ExampleEntry articleId={state.article.articleId} example={state.article.example} />
          ) : null}
          {state.article.edition === 'CURRENT' && state.article.quiz.length > 0 ? (
            <QuizPanel key={attempt} questions={state.article.quiz} mode="guest" />
          ) : null}
          {state.article.edition !== 'CURRENT' && state.article.sources.length > 0 ? (
            <ul className={styles.list} aria-label="출처">
              {state.article.sources.map((source, index) => (
                <li key={index}>
                  {source.label}
                  {source.url ? ` (${source.url})` : ''}
                </li>
              ))}
            </ul>
          ) : null}
          <EditionNotice edition={state.article.edition} place="end" />
        </article>
      ) : null}
    </section>
  )
}

import { Link } from 'react-router-dom'
import { useLoginReturn } from '@/auth/useLoginReturn'
import type { CurrentLearningArticle, LearningReturnState } from '@/types/learning'
import { QuizPanel } from './QuizPanel'
import styles from './ArticleEnd.module.css'

interface ArticleEndProps {
  article: Pick<CurrentLearningArticle, 'quiz' | 'next' | 'hasUnreleasedStages'>
  /**
   * 비로그인 사용자의 글 끝인가. 비로그인은 확인 문제를 기록 없이 풀고 로그인 연결을 본다(FR-020, FR-022).
   * 로그인 사용자의 확인 문제와 완료 카드는 B12가 이 자리에 더한다. 그 전까지는 다음 글 연결만 보인다.
   */
  guest: boolean
  /** 다음 글·목록으로 옮겨 가도 처음 떠난 화면을 잃지 않게 받은 돌아갈 자리를 싣는다(term-help-ui §3) */
  learningReturn?: LearningReturnState
}

/**
 * 새 기준 글의 글 끝 자리(013 FR-021, design-notes §3.4·§3.5).
 *
 * <p>위에서부터 확인 문제, 다음 글 연결, 로그인 안내다. 다음 글이 없으면 '배우기 목록으로'를 두고, 공개되지
 * 않은 단계가 있으면 '다음 단계는 준비 중입니다'를 덧붙인다.
 *
 * <p>로그인 연결은 014의 `useLoginReturn`으로 지금 글 주소를 적어 두고 떠난다(research R34). 013이 따로
 * 저장 키를 만들지 않는다.
 */
export function ArticleEnd({ article, guest, learningReturn }: ArticleEndProps) {
  const rememberReturn = useLoginReturn()
  const carried = learningReturn ? { learningReturn } : undefined

  return (
    <div className={styles.end}>
      {guest ? <QuizPanel questions={article.quiz} mode="guest" /> : null}

      <nav className={styles.next} aria-label="다음 읽을 글">
        {article.next ? (
          <Link
            className={styles.nextLink}
            to={`/learn/${encodeURIComponent(article.next.articleId)}`}
            state={carried}
          >
            <span className={styles.nextLabel}>다음 글</span>
            <span className={styles.nextTitle}>
              {article.next.number ? `${article.next.number} ` : null}
              {article.next.title}
            </span>
          </Link>
        ) : (
          <>
            <Link className={styles.listLink} to="/learn" state={carried}>
              배우기 목록으로
            </Link>
            {article.hasUnreleasedStages ? (
              <p className={styles.preparing}>다음 단계는 준비 중입니다</p>
            ) : null}
          </>
        )}
      </nav>

      {guest ? (
        <p className={styles.guest}>
          <span>로그인하면 학습 기록이 남습니다.</span>
          <Link className={styles.loginLink} to="/login" onClick={rememberReturn}>
            로그인
          </Link>
        </p>
      ) : null}
    </div>
  )
}

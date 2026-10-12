import type { RenamedQuestion, TransferredTerm } from '@/types/learningAdmin'
import styles from './AdminEditor.module.css'

/**
 * 게시가 초안에 한 일을 알린다(계약 §6·§11, data-model §6-3·§7-2). 글 하나의 게시와 단계 공개가 함께 쓴다.
 *
 * <p>바뀐 문제 식별자는 이미 답한 사용자에게도 새 문제로 보이므로 그 사실을 함께 적는다. 넘겨받은 용어는 어느
 * 글에서 어느 글로 갔는지 적는다. 아무것도 바뀌지 않았으면 그리지 않는다.
 */

export type RenamedQuestionGroup = {
  /** 단계 공개처럼 글 여럿을 함께 알릴 때 앞에 붙이는 글 이름. 글 하나면 비운다 */
  title?: string
  articleId: string
  renamedQuestions: readonly RenamedQuestion[]
}

interface PublishedChangesProps {
  renamed: readonly RenamedQuestionGroup[]
  transferredTerms: readonly TransferredTerm[]
}

export function PublishedChanges({ renamed, transferredTerms }: PublishedChangesProps) {
  return (
    <>
      {renamed
        .filter((group) => group.renamedQuestions.length > 0)
        .map((group) => (
          <p key={group.articleId} className={styles.hint}>
            {group.title === undefined ? '' : `${group.title}: `}이 문제는 이미 답한 사용자에게도 새
            문제로 보입니다:{' '}
            {group.renamedQuestions.map((rename) => `${rename.from} → ${rename.to}`).join(', ')}
          </p>
        ))}
      {transferredTerms.length > 0 ? (
        <p className={styles.hint}>
          넘겨받은 용어:{' '}
          {transferredTerms
            .map((term) => `${term.termId}(${term.fromArticleId} → ${term.toArticleId})`)
            .join(', ')}
        </p>
      ) : null}
    </>
  )
}

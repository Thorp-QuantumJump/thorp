import type { AdminFieldViolation, PublishCheckFailure, PublishResult } from '@/types/learningAdmin'
import { describeFieldPath } from './draftLabels'
import type { DraftSyntaxIssue, EditableDraft } from './editableDraft'
import { PublishCheckResultList } from './PublishCheckResultList'
import { PublishedChanges } from './PublishedChanges'
import { describePosition } from './richTextSyntax'
import styles from './AdminEditPanels.module.css'

/**
 * 저장·게시·게시 취소가 끝난 뒤 편집 화면 한 자리에 보이는 안내다(013 US5, 계약 §5·§6·§7).
 *
 * <p><b>거절 문장은 서버 것 그대로다.</b> 화면은 그 거절을 운영자가 어떻게 풀 수 있는지만 덧붙인다. 넘겨받기
 * 거절(`termIdTaken`·`termTransferInvalid`)은 소유 글과 화면 초안의 넘겨받기를 비교해, 고칠 수 있는 경우에만
 * 고치는 단추를 둔다(research R24). 단추는 화면 초안만 고치고, 저장은 운영자가 누른다.
 */

/** 게시한 뒤 초안을 다시 읽는 중인가, 읽었는가, 읽지 못했는가, 로그인이 풀려 읽지 못했는가(계약 §6). */
export type RereadState = 'reading' | 'done' | 'failed' | 'unauthenticated'

export type ActionState =
  | { kind: 'idle' }
  | { kind: 'saved' }
  | { kind: 'syntaxErrors'; issues: DraftSyntaxIssue[] }
  | { kind: 'draftChanged'; message: string; currentDraftVersion: number | null }
  | { kind: 'termIdTaken'; message: string; termId: string | null; ownerArticleId: string | null }
  | {
      kind: 'termTransferInvalid'
      message: string
      termId: string | null
      ownerArticleId: string | null
    }
  | { kind: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | {
      kind: 'checkFailed'
      /** 서버 문장. 항목을 하나도 읽지 못해도 이 문장은 보인다(계약 §6) */
      message: string
      checkVersion: string | null
      failures: PublishCheckFailure[]
    }
  | { kind: 'published'; result: PublishResult; reread: RereadState }
  | { kind: 'unpublished'; revisionNo: number | null }
  | { kind: 'stageNotReleased'; message: string }
  | { kind: 'formatOutdated'; message: string }
  | { kind: 'notice'; message: string }

interface EditActionNoticeProps {
  action: ActionState
  articleId: string
  draft: EditableDraft
  /** 서버에서 초안을 새로 읽는다 */
  onReload: () => void
  /** 화면 초안의 용어 하나에 넘겨받기를 걸거나 비운다 */
  onSetTransferFrom: (termId: string, transferFrom: string | null) => void
}

export function EditActionNotice({
  action,
  articleId,
  draft,
  onReload,
  onSetTransferFrom,
}: EditActionNoticeProps) {
  switch (action.kind) {
    case 'idle':
      return null
    case 'saved':
      return <p role="status">초안을 저장했습니다.</p>
    case 'syntaxErrors':
      return (
        <section className={styles.conflict}>
          <p role="alert">
            편집 문법이 틀린 칸이 있어 저장하지 않았습니다. 아래 위치를 고쳐 주세요.
          </p>
          <ul>
            {action.issues.map((issue, index) => (
              <li key={index}>
                {issue.where} {describePosition(issue)}: {issue.message}
              </li>
            ))}
          </ul>
        </section>
      )
    case 'draftChanged':
      return (
        <section className={styles.conflict}>
          <p role="alert">다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요.</p>
          <p className={styles.detail}>
            {action.message}
            {action.currentDraftVersion === null
              ? ''
              : ` (서버 초안 번호 ${action.currentDraftVersion})`}
          </p>
          <button type="button" onClick={onReload}>
            새로 불러오기
          </button>
        </section>
      )
    case 'termIdTaken':
      return <TermIdTaken action={action} draft={draft} onSetTransferFrom={onSetTransferFrom} />
    case 'termTransferInvalid':
      return (
        <TermTransferInvalid
          action={action}
          articleId={articleId}
          draft={draft}
          onSetTransferFrom={onSetTransferFrom}
        />
      )
    case 'invalid':
      return (
        <section className={styles.conflict}>
          <p role="alert">{action.message}</p>
          <ul>
            {action.fields.map((field) => (
              <li key={`${field.field}:${field.message}`}>
                {describeFieldPath(field.field)}: {field.message}
              </li>
            ))}
          </ul>
        </section>
      )
    case 'checkFailed':
      return (
        <section className={styles.conflict}>
          {/* 항목을 하나도 읽지 못하면 목록이 비므로, 그때는 이 문장이 거절을 알린다(계약 §6). */}
          <p role={action.failures.length === 0 ? 'alert' : undefined}>{action.message}</p>
          <PublishCheckResultList failures={action.failures} checkVersion={action.checkVersion} />
        </section>
      )
    case 'formatOutdated':
      return (
        <section className={styles.conflict}>
          <p role="alert">{action.message}</p>
          <p className={styles.detail}>
            V1로 되돌리는 저장은 9월 판(LEGACY)을 공개한 적이 있는 글에서만 됩니다. 공개된 단계의
            글은 V1 초안으로 게시할 수 없으니 V2 원고를 가져와 저장한 뒤 게시하세요.
          </p>
        </section>
      )
    case 'stageNotReleased':
      return (
        <section className={styles.conflict}>
          <p role="alert">{action.message}</p>
          <p className={styles.detail}>
            공개 전 단계의 새 기준 글은 관리 목록의 &lsquo;단계 공개&rsquo;로 함께 게시됩니다.
          </p>
        </section>
      )
    case 'published':
      return <Published action={action} articleId={articleId} onReload={onReload} />
    case 'unpublished':
      return (
        <p role="status">
          {action.revisionNo === null
            ? '이미 초안이라 바뀐 것이 없습니다.'
            : `${action.revisionNo}판의 게시를 취소했습니다.`}
        </p>
      )
    case 'notice':
      return <p role="alert">{action.message}</p>
  }
}

function Published({
  action,
  articleId,
  onReload,
}: {
  action: Extract<ActionState, { kind: 'published' }>
  articleId: string
  onReload: () => void
}) {
  const { result, reread } = action
  return (
    <section className={styles.conflict}>
      <p role="status">
        {result.revisionNo}판({result.edition})으로 게시했습니다. 검사 규칙 {result.checkVersion} ·
        대응표 {result.sectorMapVersion} · {result.publishedAt}
      </p>
      <PublishedChanges
        renamed={[{ articleId, renamedQuestions: result.renamedQuestions }]}
        transferredTerms={result.transferredTerms}
      />
      {reread === 'reading' ? <p role="status">게시한 초안을 다시 읽는 중입니다.</p> : null}
      {reread === 'failed' ? (
        <>
          <p role="alert">
            게시한 뒤 초안을 다시 읽지 못했습니다. 게시가 바꾼 문제 식별자를 받아야 다음 저장이
            거절되지 않습니다.
          </p>
          <button type="button" onClick={onReload}>
            새로 불러오기
          </button>
        </>
      ) : null}
      {/*
        게시는 끝났다. 화면 전체를 로그인 안내로 바꾸면 게시 결과(새 판 번호·바뀐 식별자)를 보지 못한다.
      */}
      {reread === 'unauthenticated' ? (
        <p role="alert">
          게시는 끝났지만 로그인이 풀려 게시한 초안을 다시 읽지 못했습니다. 다시 로그인한 뒤 이 글을
          새로 불러와야 다음 저장이 거절되지 않습니다.
        </p>
      ) : null}
    </section>
  )
}

type TermConflict = { message: string; termId: string | null; ownerArticleId: string | null }

/** 화면 초안에서 그 용어에 적힌 넘겨받기다. V1 초안이나 없는 용어면 `null`이다. */
function transferFromOf(draft: EditableDraft, termId: string): string | null {
  return draft.format === 'V2'
    ? (draft.terms.find((term) => term.termId === termId)?.transferFrom ?? null)
    : null
}

function TermIdTaken({
  action,
  draft,
  onSetTransferFrom,
}: {
  action: TermConflict
  draft: EditableDraft
  onSetTransferFrom: (termId: string, transferFrom: string | null) => void
}) {
  const { termId, ownerArticleId } = action
  const canTransfer =
    draft.format === 'V2' &&
    termId !== null &&
    ownerArticleId !== null &&
    draft.terms.some((term) => term.termId === termId)
  return (
    <section className={styles.conflict}>
      <p role="alert">
        {action.message}
        {termId === null ? '' : ` (용어 ${termId}`}
        {ownerArticleId === null ? '' : `, 쓰는 글 ${ownerArticleId}`}
        {termId === null ? '' : ')'}
      </p>
      {canTransfer ? (
        <>
          <p className={styles.detail}>
            이 용어를 이 글로 옮기려면 넘겨받기를 걸고 다시 저장하세요. 이 글이 게시될 때{' '}
            {ownerArticleId} 글에서 이 글로 넘어옵니다.
          </p>
          <button type="button" onClick={() => onSetTransferFrom(termId, ownerArticleId)}>
            이 글로 넘겨받기
          </button>
        </>
      ) : null}
    </section>
  )
}

function TermTransferInvalid({
  action,
  articleId,
  draft,
  onSetTransferFrom,
}: {
  action: TermConflict
  articleId: string
  draft: EditableDraft
  onSetTransferFrom: (termId: string, transferFrom: string | null) => void
}) {
  const { termId, ownerArticleId } = action
  return (
    <section className={styles.conflict}>
      <p role="alert">
        {action.message}
        {termId === null ? '' : ` (용어 ${termId})`}
      </p>
      {termId === null ? null : (
        <TransferFix
          termId={termId}
          ownerArticleId={ownerArticleId}
          articleId={articleId}
          transferFrom={transferFromOf(draft, termId)}
          onSetTransferFrom={onSetTransferFrom}
        />
      )}
    </section>
  )
}

/** 넘겨받기 거절을 푸는 길이다. 소유 글이 누구인지에 따라 넷 가운데 하나다. */
function TransferFix({
  termId,
  ownerArticleId,
  articleId,
  transferFrom,
  onSetTransferFrom,
}: {
  termId: string
  ownerArticleId: string | null
  articleId: string
  transferFrom: string | null
  onSetTransferFrom: (termId: string, transferFrom: string | null) => void
}) {
  if (ownerArticleId === null || ownerArticleId === articleId) {
    return (
      <>
        <p className={styles.detail}>
          {ownerArticleId === null
            ? '지금 이 용어를 소유한 글이 없습니다. 넘겨받기를 비우고 다시 저장하세요.'
            : '이 용어는 이미 이 글로 넘어왔습니다. 넘겨받기를 비우고 다시 저장하세요.'}
        </p>
        <button type="button" onClick={() => onSetTransferFrom(termId, null)}>
          넘겨받기 비우기
        </button>
      </>
    )
  }
  if (ownerArticleId === transferFrom) {
    return (
      <p className={styles.detail}>
        넘겨받기에 적은 글은 지금 소유 글과 같지만, 지금은 넘겨받을 수 없습니다. 다른 글이 같은
        용어를 넘겨받는 중인지 관리 목록의 &lsquo;넘겨받는 중&rsquo;을 확인하세요.
      </p>
    )
  }
  return (
    <>
      <p className={styles.detail}>지금 이 용어를 소유한 글은 {ownerArticleId}입니다.</p>
      <button type="button" onClick={() => onSetTransferFrom(termId, ownerArticleId)}>
        {ownerArticleId}에서 넘겨받기로 고치기
      </button>
    </>
  )
}

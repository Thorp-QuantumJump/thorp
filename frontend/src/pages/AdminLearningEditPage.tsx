import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  checkAdminAccess,
  fetchAdminArticle,
  fetchAdminStages,
  publishArticle,
  saveDraft,
  unpublishArticle,
  type AdminAccessResult,
  type DraftRejection,
  type LoadFailure,
} from '@/api/learningAdmin'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import {
  applyPublishToUnsentDraft,
  clearUnsentDraft,
  readUnsentDraft,
  saveUnsentDraft,
  type UnsentDraft,
} from '@/components/learning/admin/adminDraftStorage'
import panels from '@/components/learning/admin/AdminEditPanels.module.css'
import { ArticlePreview } from '@/components/learning/admin/ArticlePreview'
import { BlockEditor } from '@/components/learning/admin/BlockEditor'
import { EditActionNotice, type ActionState } from '@/components/learning/admin/EditActionNotice'
import {
  toEditableDraft,
  toServerDraft,
  withEmptyBody,
  type EditableDraft,
} from '@/components/learning/admin/editableDraft'
import { ExampleEditor } from '@/components/learning/admin/ExampleEditor'
import { knownQuizIds } from '@/components/learning/admin/manuscript'
import { ManuscriptTransfer } from '@/components/learning/admin/ManuscriptTransfer'
import { PlacementEditor } from '@/components/learning/admin/PlacementEditor'
import { QuizEditor } from '@/components/learning/admin/QuizEditor'
import { RestorePrompt } from '@/components/learning/admin/RestorePrompt'
import { SourceListEditor } from '@/components/learning/admin/SourceListEditor'
import { TermListEditor } from '@/components/learning/admin/TermListEditor'
import type {
  AdminLearningArticle,
  DraftQuizQuestion,
  PublishedEdition,
  PublishResult,
  StageAdminView,
} from '@/types/learningAdmin'
import styles from './AdminLearningEditPage.module.css'

/**
 * 배우기 글 편집(`/admin/learning/:articleId`, 013 US5).
 *
 * <p><b>거절을 받아도 입력을 화면에서 지우지 않는다.</b> `draftChanged`(R12)와 `unauthenticated`
 * (spec Edge Cases) 둘 다 운영자가 쓴 내용을 말없이 버리지 않는다. 자동 병합도 자동 재전송도 하지
 * 않는다. 덮어쓰기를 막으려고 만든 거절을 화면이 자동으로 뚫으면 막은 뜻이 사라진다.
 *
 * <p><b>보관본은 저장에 성공했을 때, 운영자가 버리기를 골랐을 때, 이전 내용 버전 보관본을 알렸을 때만
 * 지운다.</b> 401 뒤에 지우면 재로그인해서 돌아온 운영자가 복원할 것을 잃는다(R12).
 *
 * <p><b>게시 전 검사 문장을 새로 짓지 않는다.</b> 걸린 항목은 서버가 준 그대로 `PublishCheckResultList`
 * 가 보인다(계약 §6).
 *
 * <p><b>(2판) 편집기는 편집 문법 문자열을 들고 있다.</b> 저장할 때 글자 조각으로 바꾸며, 문법 오류가 있으면
 * 요청을 보내지 않고 위치를 보인다(R26). <b>게시에 성공하면 초안을 다시 읽는다.</b> 게시가 초안 번호를 올리지
 * 않고 문제 식별자와 `transferFrom`을 바꾸므로, 다시 읽지 않고 저장하면 400·409를 받는다(계약 §6). 이 탭에
 * 보관한 이 글의 편집 내용도 같은 두 가지를 고친다. 다시 읽기가 401이면 게시 결과는 그대로 두고 다시 로그인해
 * 새로 불러오라고 알린다. 화면 전체를 로그인 안내로 바꾸면 게시가 끝났다는 사실과 그 결과를 잃는다.
 *
 * <p>복원 안내·출처 목록·작업 결과 안내는 따로 둔 조각(`RestorePrompt`·`SourceListEditor`·`EditActionNotice`)이
 * 그린다. 이 화면은 상태와 서버 요청만 가진다.
 *
 * <p>관리 화면은 FR-019 대상이 아니지만 폼 요소는 `label`을 가진다.
 */

type AccessState = { status: 'checking' } | AdminAccessResult

type LoadState =
  | { status: 'loading' }
  | { status: 'loaded' }
  | { status: 'notFound' }
  | { status: 'failed'; message?: string }

export function AdminLearningEditPage() {
  const { articleId = '' } = useParams<{ articleId: string }>()

  const [access, setAccess] = useState<AccessState>({ status: 'checking' })
  const [load, setLoad] = useState<LoadState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  const [status, setStatus] = useState<AdminLearningArticle['status']>('DRAFT')
  /** 서버가 마지막으로 알려 준 초안 번호다. 화면에 보인다 */
  const [draftVersion, setDraftVersion] = useState(0)
  /**
   * 화면의 초안이 기반한 번호다. 저장·게시가 이 값을 싣는다(R12).
   *
   * <p><b>`draftVersion`과 따로 둔다.</b> 보관본을 복원하면 화면의 초안은 보관할 때의 번호에 기반한다.
   * 서버의 지금 번호로 저장하면 그 사이 다른 곳에서 저장한 초안을 409 없이 덮는다.
   */
  const [baseVersion, setBaseVersion] = useState(0)
  const [publishedRevisionNo, setPublishedRevisionNo] = useState<number | null>(null)
  const [publishedEdition, setPublishedEdition] = useState<PublishedEdition | null>(null)
  /** 9월 판(LEGACY)을 공개한 적이 있는가. V1로 되돌리기를 보일지 정한다(계약 §4·§5) */
  const [hasLegacyRevision, setHasLegacyRevision] = useState(false)
  const [draft, setDraft] = useState<EditableDraft | null>(null)
  /** 마지막으로 불러오거나 저장한 초안이다. 화면의 초안과 다르면 저장하지 않은 변경이 있다 */
  const [savedDraft, setSavedDraft] = useState<EditableDraft | null>(null)
  /** 서버 초안의 확인 문제. 원고 가져오기가 아는 식별자를 여기서 고른다(R26) */
  const [savedQuiz, setSavedQuiz] = useState<DraftQuizQuestion[]>([])
  /** 서버에 이미 저장된 용어 식별자. 이 값의 `termId`는 편집기가 잠근다(R4) */
  const [savedTermIds, setSavedTermIds] = useState<readonly string[]>([])
  const [stages, setStages] = useState<StageAdminView[] | null>(null)

  const [action, setAction] = useState<ActionState>({ kind: 'idle' })
  const [pendingRestore, setPendingRestore] = useState<UnsentDraft | null>(null)
  /** 이전 내용 버전의 보관본이 있어 버렸다. 복원하지 않았다는 사실을 알린다 */
  const [discardedOutdated, setDiscardedOutdated] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setAccess({ status: 'checking' })
    void checkAdminAccess(controller.signal).then((result) => {
      if (active) setAccess(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [attempt])

  useEffect(() => {
    if (access.status !== 'admin' || articleId === '') return
    const controller = new AbortController()
    let active = true
    setLoad({ status: 'loading' })

    void fetchAdminArticle(articleId, controller.signal).then((result) => {
      if (!active) return
      if (result.status === 'loaded') {
        applyArticle(result.article)
        setLoad({ status: 'loaded' })
        // 보관본이 있으면 먼저 묻는다. 묻지 않고 덮으면 서버 값이 소리 없이 사라진다(R12).
        const lookup = readUnsentDraft(result.article.articleId)
        setPendingRestore(lookup.status === 'found' ? lookup.unsent : null)
        // 복원할 수 없는 보관본은 알린 뒤 지운다. 알리지 않고 지우는 곳은 없다.
        if (lookup.status === 'outdated') clearUnsentDraft(result.article.articleId)
        setDiscardedOutdated(lookup.status === 'outdated')
        return
      }
      if (result.status === 'notFound') {
        setLoad({ status: 'notFound' })
        return
      }
      if (result.status === 'unauthenticated') {
        setAccess({ status: 'unauthenticated' })
        return
      }
      setLoad({ status: 'failed', message: result.kind === 'api' ? result.message : undefined })
    })

    return () => {
      active = false
      controller.abort()
    }
  }, [access.status, articleId, attempt])

  // 단계 목록은 배치 편집의 선택지다. 받지 못해도 편집은 막지 않고 지금 단계만 고르게 한다.
  useEffect(() => {
    if (access.status !== 'admin') return
    const controller = new AbortController()
    let active = true
    void fetchAdminStages(controller.signal).then((result) => {
      if (active) setStages(result.status === 'loaded' ? result.stages : null)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [access.status, attempt])

  function applyArticle(article: AdminLearningArticle) {
    const editable = toEditableDraft(article.draft)
    setStatus(article.status)
    setDraftVersion(article.draftVersion)
    setBaseVersion(article.draftVersion)
    setPublishedRevisionNo(article.publishedRevisionNo)
    setPublishedEdition(article.publishedEdition)
    setHasLegacyRevision(article.hasLegacyRevision)
    setDraft(editable)
    setSavedDraft(editable)
    setSavedQuiz(article.draft.quiz)
    setSavedTermIds(article.draft.terms.map((term) => term.termId))
  }

  const reload = useCallback(() => {
    setAction({ kind: 'idle' })
    setAttempt((count) => count + 1)
  }, [])

  const onPreviewUnauthenticated = useCallback(() => setAccess({ status: 'unauthenticated' }), [])

  const known = useMemo(() => knownQuizIds(savedQuiz), [savedQuiz])

  /**
   * 저장하지 않은 변경이 있는가.
   *
   * <p>게시는 서버에 저장된 초안을 게시한다. 화면에서 고친 내용이 있는데 게시를 누르면 화면과 다른
   * 초안이 공개되거나, 고친 줄 알았던 항목으로 같은 422를 다시 받는다(계약 §6 "운영자가 보지 않은 초안을
   * 게시하지 않는다").
   */
  const hasUnsavedChanges =
    draft !== null && savedDraft !== null && JSON.stringify(draft) !== JSON.stringify(savedDraft)

  /** 편집기가 바꾼 초안을 상태와 보관본에 함께 넣는다. */
  const updateDraft = (next: EditableDraft) => {
    setDraft(next)
    saveUnsentDraft(articleId, next, baseVersion)
  }

  const restoreUnsentDraft = (unsent: UnsentDraft) => {
    setDraft(unsent.draft)
    // 보관본이 기반한 번호로 저장한다. 그 사이 서버가 바뀌었으면 409가 알려 준다(R12).
    setBaseVersion(unsent.draftVersion)
    setPendingRestore(null)
  }

  /** V2 초안의 용어 하나에 넘겨받기를 걸거나 비운다(R24). 저장은 운영자가 누른다. */
  const setTransferFrom = (termId: string, transferFrom: string | null) => {
    if (!draft || draft.format !== 'V2') return
    updateDraft({
      ...draft,
      terms: draft.terms.map((term) => (term.termId === termId ? { ...term, transferFrom } : term)),
    })
    setAction({ kind: 'idle' })
  }

  const onSave = async () => {
    if (!draft || busy) return
    const converted = toServerDraft(draft)
    if (!converted.ok) {
      setAction({ kind: 'syntaxErrors', issues: converted.issues })
      return
    }
    setBusy(true)
    setAction({ kind: 'idle' })
    const result = await saveDraft(articleId, {
      ...converted.draft,
      expectedDraftVersion: baseVersion,
    })
    setBusy(false)

    switch (result.status) {
      case 'saved':
        applyArticle(result.article)
        clearUnsentDraft(articleId)
        setAction({ kind: 'saved' })
        return
      case 'invalid':
        setAction({ kind: 'invalid', message: result.message, fields: result.fields })
        return
      default:
        handleRejection(result)
    }
  }

  /** 저장과 게시가 함께 받는 거절을 화면 상태로 옮긴다. */
  const handleRejection = (result: DraftRejection | LoadFailure) => {
    switch (result.status) {
      case 'draftChanged':
        // 입력은 그대로 둔다. 새로 불러올지는 운영자가 정한다(quickstart 3-6).
        setAction({
          kind: 'draftChanged',
          message: result.message,
          currentDraftVersion: result.currentDraftVersion,
        })
        return
      case 'termIdTaken':
        setAction({
          kind: 'termIdTaken',
          message: result.message,
          termId: result.termId,
          ownerArticleId: result.ownerArticleId,
        })
        return
      case 'termTransferInvalid':
        setAction({
          kind: 'termTransferInvalid',
          message: result.message,
          termId: result.termId,
          ownerArticleId: result.ownerArticleId,
        })
        return
      case 'unauthenticated':
        // 보관본을 지우지 않는다. 재로그인 뒤 같은 글을 열면 복원할 수 있어야 한다(R12).
        setAccess({ status: 'unauthenticated' })
        return
      case 'notFound':
        setLoad({ status: 'notFound' })
        return
      case 'formatOutdated':
        setAction({ kind: 'formatOutdated', message: result.message })
        return
      default:
        setAction({ kind: 'notice', message: result.message })
    }
  }

  /**
   * 게시한 뒤 초안을 다시 읽는다. 게시가 바꾼 문제 식별자와 비운 `transferFrom`을 받기 위해서다(계약 §6).
   * 게시 안내는 그대로 둔다.
   */
  const rereadAfterPublish = async (result: PublishResult) => {
    setAction({ kind: 'published', result, reread: 'reading' })
    const reread = await fetchAdminArticle(articleId)
    if (reread.status === 'loaded') {
      applyArticle(reread.article)
      setAction({ kind: 'published', result, reread: 'done' })
      return
    }
    if (reread.status === 'unauthenticated') {
      // 보관본은 지우지 않았다. 다시 로그인해 이 글을 열면 게시 결과대로 고친 보관본을 복원할 수 있다.
      setAction({ kind: 'published', result, reread: 'unauthenticated' })
      return
    }
    setAction({ kind: 'published', result, reread: 'failed' })
  }

  const onPublish = async () => {
    // 저장하지 않은 변경이 있으면 게시하지 않는다. 자동으로 저장한 뒤 게시하지도 않는다. 운영자가
    // 저장 결과를 보고 게시를 정한다.
    if (busy || hasUnsavedChanges) return
    setBusy(true)
    setAction({ kind: 'idle' })
    const outcome = await publishArticle(articleId, baseVersion)

    switch (outcome.status) {
      case 'published':
        setStatus(outcome.result.status)
        setPublishedRevisionNo(outcome.result.revisionNo)
        setPublishedEdition(outcome.result.edition)
        applyPublishToUnsentDraft(
          articleId,
          outcome.result.renamedQuestions,
          outcome.result.transferredTerms,
        )
        await rereadAfterPublish(outcome.result)
        break
      case 'checkFailed':
        setAction({
          kind: 'checkFailed',
          message: outcome.message,
          checkVersion: outcome.checkVersion,
          failures: outcome.failures,
        })
        break
      case 'stageNotReleased':
        setAction({ kind: 'stageNotReleased', message: outcome.message })
        break
      case 'invalid':
        setAction({ kind: 'invalid', message: outcome.message, fields: outcome.fields })
        break
      default:
        handleRejection(outcome)
    }
    setBusy(false)
  }

  const onUnpublish = async () => {
    if (busy) return
    if (!window.confirm('이 글과 이 글의 용어 설명이 함께 공개에서 빠집니다. 게시를 취소할까요?')) {
      return
    }
    setBusy(true)
    setAction({ kind: 'idle' })
    const outcome = await unpublishArticle(articleId)
    setBusy(false)

    if (outcome.status === 'unpublished') {
      setStatus(outcome.result.status)
      setPublishedRevisionNo(null)
      setAction({ kind: 'unpublished', revisionNo: outcome.result.unpublishedRevisionNo })
      return
    }
    if (outcome.status === 'unauthenticated') {
      setAccess({ status: 'unauthenticated' })
      return
    }
    if (outcome.status === 'notFound') {
      setLoad({ status: 'notFound' })
      return
    }
    setAction({ kind: 'notice', message: outcome.message })
  }

  if (access.status === 'checking') {
    return (
      <main className={styles.main}>
        <p role="status">관리 권한을 확인하고 있습니다.</p>
      </main>
    )
  }

  if (access.status === 'denied') {
    return (
      <main className={styles.main} aria-labelledby="admin-edit-denied-title">
        <h1 id="admin-edit-denied-title">볼 수 없는 화면입니다</h1>
        <p>
          <Link to="/learn">배우기로 가기</Link>
        </p>
      </main>
    )
  }

  if (access.status === 'unauthenticated') {
    return (
      <main className={styles.main} aria-labelledby="admin-edit-auth-title">
        <h1 id="admin-edit-auth-title">다시 로그인해 주세요</h1>
        <p role="alert">
          로그인이 풀려 저장하지 못했습니다. 쓰던 내용은 이 탭에 남아 있으니, 다시 로그인한 뒤 이
          글을 열면 복원할지 묻습니다.
        </p>
      </main>
    )
  }

  if (access.status === 'failed') {
    return (
      <main className={styles.main}>
        <LearningLoadFailure message={access.message} onRetry={reload} />
      </main>
    )
  }

  if (load.status === 'notFound') {
    return (
      <main className={styles.main} aria-labelledby="admin-edit-missing-title">
        <h1 id="admin-edit-missing-title">없는 글입니다</h1>
        <p>
          <Link to="/admin/learning">관리 목록으로</Link>
        </p>
      </main>
    )
  }

  if (load.status === 'failed') {
    return (
      <main className={styles.main}>
        <LearningLoadFailure message={load.message} onRetry={reload} />
      </main>
    )
  }

  if (load.status === 'loading' || !draft) {
    return (
      <main className={styles.main}>
        <p role="status">초안을 불러오는 중입니다.</p>
      </main>
    )
  }

  return (
    <main className={styles.main} aria-labelledby="admin-edit-title">
      <h1 id="admin-edit-title" className={styles.title}>
        {articleId} 편집
      </h1>
      <p className={styles.meta}>
        상태 {status} · 형식 {draft.format} · 초안 번호 {draftVersion} · 공개 중인 판{' '}
        {publishedRevisionNo === null
          ? '없음'
          : `${publishedRevisionNo}판${publishedEdition === null ? '' : `(${publishedEdition})`}`}
      </p>
      <p>
        <Link to="/admin/learning">관리 목록으로</Link>
      </p>

      {discardedOutdated ? (
        <p role="status" className={panels.detail}>
          이전 편집기에서 보관한 편집 내용이 있었지만 지금 편집기의 모양과 달라 복원하지 않고
          버렸습니다.
        </p>
      ) : null}

      {pendingRestore === null ? null : (
        <RestorePrompt
          unsent={pendingRestore}
          draftVersion={draftVersion}
          onRestore={restoreUnsentDraft}
          onDiscard={() => {
            clearUnsentDraft(articleId)
            setPendingRestore(null)
          }}
        />
      )}

      {/*
        복원 여부에 답하기 전에는 편집기와 저장·게시를 막는다. 먼저 고치면 새 입력이 보관본을 덮어
        복원할 것이 사라진다(R12). fieldset의 disabled가 안의 입력과 버튼을 한 번에 막는다.
      */}
      <fieldset className={styles.editorArea} disabled={pendingRestore !== null}>
        <div className={styles.fields}>
          <label className={panels.field}>
            <span>제목</span>
            <input
              type="text"
              value={draft.title}
              onChange={(event) => updateDraft({ ...draft, title: event.target.value })}
            />
          </label>
          <label className={panels.field}>
            <span>질문</span>
            <textarea
              rows={2}
              value={draft.question}
              onChange={(event) => updateDraft({ ...draft, question: event.target.value })}
            />
          </label>
          {/* V2 글은 읽는 순서 대신 단계 안 순서를 쓴다(계약 §3). V1 글만 지금처럼 고친다. */}
          {draft.format === 'V1' ? (
            <label className={panels.field}>
              <span>읽는 순서</span>
              <input
                type="number"
                value={draft.readingOrder}
                onChange={(event) =>
                  updateDraft({ ...draft, readingOrder: Number(event.target.value) || 0 })
                }
              />
            </label>
          ) : null}
        </div>

        <PlacementEditor
          format={draft.format}
          stageKey={draft.stageKey}
          stagePosition={draft.stagePosition}
          stages={stages}
          canRevertToV1={hasLegacyRevision}
          onPlacementChange={(placement) => updateDraft({ ...draft, ...placement })}
          onFormatChange={(format) => updateDraft(withEmptyBody(draft, format))}
        />

        <ManuscriptTransfer
          articleId={articleId}
          draft={draft}
          known={known}
          publishedEdition={publishedEdition}
          dirty={hasUnsavedChanges}
          onImport={updateDraft}
        />

        <BlockEditor draft={draft} onChange={updateDraft} />

        <ExampleEditor
          example={draft.example}
          required={draft.format === 'V1'}
          onChange={(example) => updateDraft({ ...draft, example })}
        />

        <TermListEditor draft={draft} savedTermIds={savedTermIds} onChange={updateDraft} />

        {draft.format === 'V2' ? (
          <QuizEditor questions={draft.quiz} onChange={(quiz) => updateDraft({ ...draft, quiz })} />
        ) : null}

        <SourceListEditor draft={draft} onChange={updateDraft} />

        <div className={panels.actions}>
          <button type="button" onClick={() => void onSave()} disabled={busy}>
            저장
          </button>
          <button
            type="button"
            onClick={() => void onPublish()}
            disabled={busy || hasUnsavedChanges}
            aria-describedby={hasUnsavedChanges ? 'admin-edit-unsaved' : undefined}
          >
            게시
          </button>
          {status === 'PUBLISHED' ? (
            <button type="button" onClick={() => void onUnpublish()} disabled={busy}>
              게시 취소
            </button>
          ) : null}
        </div>
        {hasUnsavedChanges ? (
          <p id="admin-edit-unsaved" className={panels.detail}>
            저장한 뒤 게시할 수 있습니다.
          </p>
        ) : null}
      </fieldset>

      <EditActionNotice
        action={action}
        articleId={articleId}
        draft={draft}
        onReload={reload}
        onSetTransferFrom={setTransferFrom}
      />

      <ArticlePreview
        articleId={articleId}
        dirty={hasUnsavedChanges}
        onUnauthenticated={onPreviewUnauthenticated}
      />
    </main>
  )
}

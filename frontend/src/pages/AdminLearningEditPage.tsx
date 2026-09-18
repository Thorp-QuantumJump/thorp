import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  checkAdminAccess,
  fetchAdminArticle,
  publishArticle,
  saveDraft,
  unpublishArticle,
  type AdminAccessResult,
} from '@/api/learningAdmin'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import {
  clearUnsentDraft,
  loadUnsentDraft,
  saveUnsentDraft,
  type UnsentDraft,
} from '@/components/learning/admin/adminDraftStorage'
import { BlockEditor } from '@/components/learning/admin/BlockEditor'
import { ExampleEditor } from '@/components/learning/admin/ExampleEditor'
import { PublishCheckResultList } from '@/components/learning/admin/PublishCheckResultList'
import { TermListEditor } from '@/components/learning/admin/TermListEditor'
import type {
  AdminFieldViolation,
  AdminLearningArticle,
  AdminLearningDraft,
  PublishCheckFailure,
  PublishResult,
} from '@/types/learningAdmin'
import styles from './AdminLearningEditPage.module.css'

/**
 * 배우기 글 편집(`/admin/learning/:articleId`, 013 US5).
 *
 * <p><b>거절을 받아도 입력을 화면에서 지우지 않는다.</b> `draftChanged`(R12)와 `unauthenticated`
 * (spec Edge Cases) 둘 다 운영자가 쓴 내용을 말없이 버리지 않는다. 자동 병합도 자동 재전송도 하지
 * 않는다. 덮어쓰기를 막으려고 만든 거절을 화면이 자동으로 뚫으면 막은 뜻이 사라진다.
 *
 * <p><b>보관본은 저장에 성공했을 때만 지운다.</b> 401 뒤에 지우면 재로그인해서 돌아온 운영자가
 * 복원할 것을 잃는다(R12).
 *
 * <p><b>게시 전 검사 문장을 새로 짓지 않는다.</b> 걸린 항목은 서버가 준 그대로 `PublishCheckResultList`
 * 가 보인다(계약 §6).
 *
 * <p>관리 화면은 FR-019 대상이 아니지만 폼 요소는 `label`을 가진다.
 */

type AccessState = { status: 'checking' } | AdminAccessResult

type LoadState =
  | { status: 'loading' }
  | { status: 'loaded' }
  | { status: 'notFound' }
  | { status: 'failed'; message?: string }

/** 저장·게시·게시 취소가 끝난 뒤 그 자리에 보일 것이다. 셋이 한 자리를 쓴다. */
type ActionState =
  | { kind: 'idle' }
  | { kind: 'saved' }
  | { kind: 'draftChanged'; message: string; currentDraftVersion: number | null }
  | { kind: 'termIdTaken'; message: string; termId: string | null; ownerArticleId: string | null }
  | { kind: 'invalid'; message: string; fields: AdminFieldViolation[] }
  | { kind: 'checkFailed'; checkVersion: string | null; failures: PublishCheckFailure[] }
  | { kind: 'published'; result: PublishResult }
  | { kind: 'unpublished'; revisionNo: number | null }
  | { kind: 'notice'; message: string }

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
  const [draft, setDraft] = useState<AdminLearningDraft | null>(null)
  /** 마지막으로 불러오거나 저장한 초안이다. 화면의 초안과 다르면 저장하지 않은 변경이 있다 */
  const [savedDraft, setSavedDraft] = useState<AdminLearningDraft | null>(null)
  /** 서버에 이미 저장된 용어 식별자. 이 값의 `termId`는 편집기가 잠근다(R4) */
  const [savedTermIds, setSavedTermIds] = useState<readonly string[]>([])

  const [action, setAction] = useState<ActionState>({ kind: 'idle' })
  const [pendingRestore, setPendingRestore] = useState<UnsentDraft | null>(null)
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
        setPendingRestore(loadUnsentDraft(result.article.articleId))
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

  function applyArticle(article: AdminLearningArticle) {
    setStatus(article.status)
    setDraftVersion(article.draftVersion)
    setBaseVersion(article.draftVersion)
    setPublishedRevisionNo(article.publishedRevisionNo)
    setDraft(article.draft)
    setSavedDraft(article.draft)
    setSavedTermIds(article.draft.terms.map((term) => term.termId))
  }

  const reload = useCallback(() => {
    setAction({ kind: 'idle' })
    setAttempt((count) => count + 1)
  }, [])

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
  const updateDraft = (next: AdminLearningDraft) => {
    setDraft(next)
    saveUnsentDraft(articleId, next, baseVersion)
  }

  const restoreUnsentDraft = (unsent: UnsentDraft) => {
    setDraft(unsent.draft)
    // 보관본이 기반한 번호로 저장한다. 그 사이 서버가 바뀌었으면 409가 알려 준다(R12).
    setBaseVersion(unsent.draftVersion)
    setPendingRestore(null)
  }

  const onSave = async () => {
    if (!draft || busy) return
    setBusy(true)
    setAction({ kind: 'idle' })
    const result = await saveDraft(articleId, { ...draft, expectedDraftVersion: baseVersion })
    setBusy(false)

    switch (result.status) {
      case 'saved':
        applyArticle(result.article)
        clearUnsentDraft(articleId)
        setAction({ kind: 'saved' })
        return
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
      case 'invalid':
        setAction({ kind: 'invalid', message: result.message, fields: result.fields })
        return
      case 'unauthenticated':
        // 보관본을 지우지 않는다. 재로그인 뒤 같은 글을 열면 복원할 수 있어야 한다(R12).
        setAccess({ status: 'unauthenticated' })
        return
      case 'notFound':
        setLoad({ status: 'notFound' })
        return
      default:
        setAction({ kind: 'notice', message: result.message })
    }
  }

  const onPublish = async () => {
    // 저장하지 않은 변경이 있으면 게시하지 않는다. 자동으로 저장한 뒤 게시하지도 않는다. 운영자가
    // 저장 결과를 보고 게시를 정한다.
    if (busy || hasUnsavedChanges) return
    setBusy(true)
    setAction({ kind: 'idle' })
    const result = await publishArticle(articleId, baseVersion)
    setBusy(false)

    switch (result.status) {
      case 'published':
        setStatus(result.result.status)
        setPublishedRevisionNo(result.result.revisionNo)
        setAction({ kind: 'published', result: result.result })
        return
      case 'checkFailed':
        setAction({
          kind: 'checkFailed',
          checkVersion: result.checkVersion,
          failures: result.failures,
        })
        return
      case 'draftChanged':
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
      case 'unauthenticated':
        setAccess({ status: 'unauthenticated' })
        return
      case 'notFound':
        setLoad({ status: 'notFound' })
        return
      default:
        setAction({ kind: 'notice', message: result.message })
    }
  }

  const onUnpublish = async () => {
    if (busy) return
    if (!window.confirm('이 글과 이 글의 용어 설명이 함께 공개에서 빠집니다. 게시를 취소할까요?')) {
      return
    }
    setBusy(true)
    setAction({ kind: 'idle' })
    const result = await unpublishArticle(articleId)
    setBusy(false)

    if (result.status === 'unpublished') {
      setStatus(result.result.status)
      setPublishedRevisionNo(null)
      setAction({ kind: 'unpublished', revisionNo: result.result.unpublishedRevisionNo })
      return
    }
    if (result.status === 'unauthenticated') {
      setAccess({ status: 'unauthenticated' })
      return
    }
    if (result.status === 'notFound') {
      setLoad({ status: 'notFound' })
      return
    }
    setAction({ kind: 'notice', message: result.message })
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
        상태 {status} · 초안 번호 {draftVersion} · 공개 중인 판{' '}
        {publishedRevisionNo === null ? '없음' : `${publishedRevisionNo}판`}
      </p>
      <p>
        <Link to="/admin/learning">관리 목록으로</Link>
      </p>

      {pendingRestore === null ? null : (
        <section className={styles.restore} aria-labelledby="admin-edit-restore-title">
          <h2 id="admin-edit-restore-title">저장하지 못한 편집 내용이 있습니다</h2>
          <p>{new Date(pendingRestore.savedAt).toLocaleString('ko-KR')}에 보관한 내용입니다.</p>
          {pendingRestore.draftVersion === draftVersion ? null : (
            <p role="alert">
              보관한 뒤 다른 곳에서 초안이 바뀌었습니다(보관본 {pendingRestore.draftVersion}번, 지금{' '}
              {draftVersion}번). 복원해 저장하면 초안이 바뀌었다는 안내를 받습니다. 새 초안에 옮길
              내용을 확인하는 데 쓰세요.
            </p>
          )}
          <p>복원할지 정하기 전에는 편집할 수 없습니다.</p>
          <div className={styles.actions}>
            <button type="button" onClick={() => restoreUnsentDraft(pendingRestore)}>
              복원하기
            </button>
            <button
              type="button"
              onClick={() => {
                clearUnsentDraft(articleId)
                setPendingRestore(null)
              }}
            >
              버리기
            </button>
          </div>
        </section>
      )}

      {/*
        복원 여부에 답하기 전에는 편집기와 저장·게시를 막는다. 먼저 고치면 새 입력이 보관본을 덮어
        복원할 것이 사라진다(R12). fieldset의 disabled가 안의 입력과 버튼을 한 번에 막는다.
      */}
      <fieldset className={styles.editorArea} disabled={pendingRestore !== null}>
        <div className={styles.fields}>
          <label className={styles.field}>
            <span>제목</span>
            <input
              type="text"
              value={draft.title}
              onChange={(event) => updateDraft({ ...draft, title: event.target.value })}
            />
          </label>
          <label className={styles.field}>
            <span>질문</span>
            <textarea
              rows={2}
              value={draft.question}
              onChange={(event) => updateDraft({ ...draft, question: event.target.value })}
            />
          </label>
          <label className={styles.field}>
            <span>읽는 순서</span>
            <input
              type="number"
              value={draft.readingOrder}
              onChange={(event) =>
                updateDraft({ ...draft, readingOrder: Number(event.target.value) || 0 })
              }
            />
          </label>
        </div>

        <BlockEditor
          blocks={draft.blocks}
          terms={draft.terms}
          onChange={(blocks) => updateDraft({ ...draft, blocks })}
        />

        <ExampleEditor
          example={draft.example}
          onChange={(example) => updateDraft({ ...draft, example })}
        />

        <TermListEditor
          terms={draft.terms}
          savedTermIds={savedTermIds}
          onChange={(terms) => updateDraft({ ...draft, terms })}
        />

        <section className={styles.sources} aria-labelledby="admin-edit-sources-title">
          <h3 id="admin-edit-sources-title">출처</h3>
          <ul className={styles.sourceList}>
            {draft.sources.map((source, index) => (
              // 출처는 라벨이 겹칠 수 있어 순번으로 자리를 잡는다.
              <li key={index} className={styles.sourceRow}>
                <label className={styles.field}>
                  <span>이름</span>
                  <input
                    type="text"
                    value={source.label}
                    onChange={(event) =>
                      updateDraft({
                        ...draft,
                        sources: draft.sources.map((item, position) =>
                          position === index ? { ...item, label: event.target.value } : item,
                        ),
                      })
                    }
                  />
                </label>
                <label className={styles.field}>
                  <span>주소</span>
                  <input
                    type="url"
                    value={source.url ?? ''}
                    onChange={(event) =>
                      updateDraft({
                        ...draft,
                        sources: draft.sources.map((item, position) =>
                          position === index
                            ? { ...item, url: event.target.value === '' ? null : event.target.value }
                            : item,
                        ),
                      })
                    }
                  />
                </label>
                <button
                  type="button"
                  onClick={() =>
                    updateDraft({
                      ...draft,
                      sources: draft.sources.filter((_, position) => position !== index),
                    })
                  }
                  aria-label={`${index + 1}번째 출처 삭제`}
                >
                  삭제
                </button>
              </li>
            ))}
          </ul>
          <button
            type="button"
            onClick={() => updateDraft({ ...draft, sources: [...draft.sources, { label: '', url: null }] })}
          >
            출처 추가
          </button>
        </section>

        <div className={styles.actions}>
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
          <p id="admin-edit-unsaved" className={styles.detail}>
            저장한 뒤 게시할 수 있습니다.
          </p>
        ) : null}
      </fieldset>

      {action.kind === 'saved' ? <p role="status">초안을 저장했습니다.</p> : null}

      {action.kind === 'draftChanged' ? (
        <section className={styles.conflict}>
          <p role="alert">다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요.</p>
          <p className={styles.detail}>
            {action.message}
            {action.currentDraftVersion === null
              ? ''
              : ` (서버 초안 번호 ${action.currentDraftVersion})`}
          </p>
          <button type="button" onClick={reload}>
            새로 불러오기
          </button>
        </section>
      ) : null}

      {action.kind === 'termIdTaken' ? (
        <p role="alert">
          {action.message}
          {action.termId === null ? '' : ` (용어 ${action.termId}`}
          {action.ownerArticleId === null ? '' : `, 쓰는 글 ${action.ownerArticleId}`}
          {action.termId === null ? '' : ')'}
        </p>
      ) : null}

      {action.kind === 'invalid' ? (
        <section className={styles.conflict}>
          <p role="alert">{action.message}</p>
          <ul>
            {action.fields.map((field) => (
              <li key={`${field.field}:${field.message}`}>
                {field.field}: {field.message}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {action.kind === 'checkFailed' ? (
        <PublishCheckResultList failures={action.failures} checkVersion={action.checkVersion} />
      ) : null}

      {action.kind === 'published' ? (
        <p role="status">
          {action.result.revisionNo}판으로 게시했습니다. 검사 규칙 {action.result.checkVersion} ·
          대응표 {action.result.sectorMapVersion} · {action.result.publishedAt}
        </p>
      ) : null}

      {action.kind === 'unpublished' ? (
        <p role="status">
          {action.revisionNo === null
            ? '이미 초안이라 바뀐 것이 없습니다.'
            : `${action.revisionNo}판의 게시를 취소했습니다.`}
        </p>
      ) : null}

      {action.kind === 'notice' ? <p role="alert">{action.message}</p> : null}
    </main>
  )
}

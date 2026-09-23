import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  checkAdminAccess,
  createArticle,
  fetchAdminArticles,
  type AdminAccessResult,
  type AdminArticleListResult,
} from '@/api/learningAdmin'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import styles from './AdminLearningListPage.module.css'

/**
 * 배우기 관리 목록(`/admin/learning`, 013 US5).
 *
 * <p><b>권한 확인이 200일 때만 화면을 그린다.</b> 서버는 권한이 없으면 404를 주며 `admin: false`인
 * 200을 주지 않는다(계약 §1). 권한이 없는 계정에는 목록도 편집 링크도 만들기 폼도 보이지 않는다
 * (US5 시나리오 1). 편집 수단을 숨기는 것이 아니라 아예 만들지 않는다.
 *
 * <p><b>`needsUpdate`의 `null`을 `false`로 접지 않는다.</b> 대응표를 확인할 수 없어 판정하지 못한 것과
 * 판정해서 괜찮은 것은 다른 사실이다(원칙 VI).
 *
 * <p><b>`AppHeader`에 관리 메뉴를 더하지 않는다.</b> 운영자는 주소로 들어온다. 일반 사용자 화면
 * 어디에도 관리 화면의 존재가 드러나지 않아야 한다.
 */

type AccessState = { status: 'checking' } | AdminAccessResult
type ListState = { status: 'loading' } | AdminArticleListResult

type CreateFormState = {
  articleId: string
  title: string
  readingOrder: string
}

const EMPTY_FORM: CreateFormState = { articleId: '', title: '', readingOrder: '' }

function formatOccurredAt(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('ko-KR')
}

export function AdminLearningListPage() {
  const navigate = useNavigate()
  const [access, setAccess] = useState<AccessState>({ status: 'checking' })
  const [list, setList] = useState<ListState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [form, setForm] = useState<CreateFormState>(EMPTY_FORM)
  const [createNotice, setCreateNotice] = useState<string | null>(null)

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
    if (access.status !== 'admin') return
    const controller = new AbortController()
    let active = true
    setList({ status: 'loading' })
    void fetchAdminArticles(controller.signal).then((result) => {
      if (!active) return
      // 로그인이 풀린 것은 권한 없음과 다른 사실이다. 재로그인 안내로 보낸다(014 FR-012).
      if (result.status === 'unauthenticated') {
        setAccess({ status: 'unauthenticated' })
        return
      }
      setList(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [access.status, attempt])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  const submitCreate = async (event: React.FormEvent) => {
    event.preventDefault()
    setCreateNotice(null)
    const readingOrder = Number(form.readingOrder)
    if (!Number.isInteger(readingOrder)) {
      setCreateNotice('읽는 순서는 정수여야 합니다.')
      return
    }

    const result = await createArticle({
      articleId: form.articleId.trim(),
      title: form.title.trim(),
      readingOrder,
    })

    if (result.status === 'created') {
      // 새 글은 빈 초안이다. 목록에 남을 이유가 없으니 바로 편집 화면으로 간다.
      navigate(`/admin/learning/${encodeURIComponent(result.article.articleId)}`)
      return
    }
    if (result.status === 'idTaken' || result.status === 'invalid') {
      setCreateNotice(result.message)
      return
    }
    if (result.status === 'unauthenticated') {
      setAccess({ status: 'unauthenticated' })
      return
    }
    setCreateNotice(result.message)
  }

  if (access.status === 'checking') {
    return (
      <main className={styles.main}>
        <p role="status">관리 권한을 확인하고 있습니다.</p>
      </main>
    )
  }

  // 권한이 없는 계정에는 편집 수단을 아예 만들지 않는다(US5 시나리오 1).
  if (access.status === 'denied') {
    return (
      <main className={styles.main} aria-labelledby="admin-learning-denied-title">
        <h1 id="admin-learning-denied-title">볼 수 없는 화면입니다</h1>
        <p>
          <Link to="/learn">배우기로 가기</Link>
        </p>
      </main>
    )
  }

  // 014가 재로그인을 안내한다. 이 화면은 사실만 알리고 스스로 로그인 화면을 만들지 않는다.
  if (access.status === 'unauthenticated') {
    return (
      <main className={styles.main} aria-labelledby="admin-learning-auth-title">
        <h1 id="admin-learning-auth-title">다시 로그인해 주세요</h1>
        <p role="alert">로그인이 풀렸습니다. 다시 로그인한 뒤 이 주소를 여세요.</p>
      </main>
    )
  }

  if (access.status === 'failed') {
    return (
      <main className={styles.main}>
        <LearningLoadFailure message={access.message} onRetry={retry} />
      </main>
    )
  }

  return (
    <main className={styles.main} aria-labelledby="admin-learning-title">
      <h1 id="admin-learning-title" className={styles.title}>
        배우기 관리
      </h1>

      {list.status === 'loading' ? <p role="status">글 목록을 불러오는 중입니다.</p> : null}

      {list.status === 'loaded' ? (
        <table className={styles.table}>
          <caption className={styles.caption}>글 목록</caption>
          <thead>
            <tr>
              <th scope="col">순서</th>
              <th scope="col">제목</th>
              <th scope="col">상태</th>
              <th scope="col">초안 번호</th>
              <th scope="col">게시하지 않은 변경</th>
              <th scope="col">갱신 필요</th>
              <th scope="col">마지막 기록</th>
              <th scope="col">편집</th>
            </tr>
          </thead>
          <tbody>
            {list.articles.map((article) => (
              <tr key={article.articleId}>
                <td>{article.readingOrder}</td>
                <td>{article.title}</td>
                <td>{article.status}</td>
                <td>{article.draftVersion}</td>
                <td>{article.hasUnpublishedChanges ? '게시하지 않은 변경 있음' : '없음'}</td>
                <td>
                  {article.needsUpdate === null
                    ? '대응표를 확인할 수 없음'
                    : article.needsUpdate
                      ? '갱신 필요'
                      : '없음'}
                </td>
                <td>
                  {article.lastPublication === null
                    ? '기록 없음'
                    : `${article.lastPublication.action} ${
                        article.lastPublication.revisionNo ?? '-'
                      }판 · 사용자 ${article.lastPublication.actorUserId} · ${formatOccurredAt(
                        article.lastPublication.occurredAt,
                      )}`}
                </td>
                <td>
                  <Link to={`/admin/learning/${encodeURIComponent(article.articleId)}`}>
                    편집
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {list.status === 'failed' ? (
        <LearningLoadFailure
          message={list.kind === 'api' ? list.message : undefined}
          onRetry={retry}
        />
      ) : null}

      <form className={styles.createForm} onSubmit={submitCreate}>
        <h2>글 만들기</h2>
        <label className={styles.field}>
          <span>식별자</span>
          <input
            type="text"
            value={form.articleId}
            onChange={(event) => setForm({ ...form, articleId: event.target.value })}
          />
        </label>
        <label className={styles.field}>
          <span>제목</span>
          <input
            type="text"
            value={form.title}
            onChange={(event) => setForm({ ...form, title: event.target.value })}
          />
        </label>
        <label className={styles.field}>
          <span>읽는 순서</span>
          <input
            type="number"
            value={form.readingOrder}
            onChange={(event) => setForm({ ...form, readingOrder: event.target.value })}
          />
        </label>
        <button type="submit">만들기</button>
        {createNotice === null ? null : (
          <p role="alert" className={styles.notice}>
            {createNotice}
          </p>
        )}
      </form>
    </main>
  )
}

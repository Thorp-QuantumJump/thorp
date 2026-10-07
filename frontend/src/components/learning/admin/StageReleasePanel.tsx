import { useState } from 'react'
import {
  fetchStageReadiness,
  releaseStage,
  type StageReadinessResult,
  type StageReleaseOutcome,
} from '@/api/learningAdmin'
import type { StageAdminArticle, StageAdminView, StageBlocker } from '@/types/learningAdmin'
import { applyPublishToUnsentDraft } from './adminDraftStorage'
import { PublishCheckResultList } from './PublishCheckResultList'
import styles from './AdminEditor.module.css'

/**
 * 단계 목록과 단계 공개(013 FR-031, contracts/learning-admin-api §9~§11, research R23, T173).
 *
 * <p><b>미리 검사와 공개를 나눈다.</b> 미리 검사는 아무것도 쓰지 않는다. 공개는 되돌릴 수 없으므로 미리 검사를
 * 통과한 단계에서만 누를 수 있고, 공개될 글 목록을 보인 확인 창에서 확인할 때만 요청한다. 취소하면 요청하지
 * 않는다.
 *
 * <p><b>요청의 글 목록은 미리 검사가 돌려준 목록과 초안 번호 그대로다.</b> 그사이 서버가 바뀌었으면 409
 * `LEARNING_STAGE_CHANGED`가 오고, 화면은 미리 검사를 다시 하게 할 뿐 서버가 알려 준 목록으로 다시 보내지
 * 않는다 — 운영자가 보지 않은 초안을 공개하지 않는다(R23).
 *
 * <p><b>검사 문장은 서버 것 그대로다.</b> 글마다 걸린 항목을 `PublishCheckResultList`로 그린다. 공개를 막는 것
 * (`blockers`)은 서버가 이름만 주므로 그 이름의 뜻만 화면이 적는다.
 *
 * <p>공개에 성공하면 게시된 글들의 탭 보관본을 게시 결과대로 고친다(`applyPublishToUnsentDraft`, 계약 §11).
 */

interface StageReleasePanelProps {
  stages: readonly StageAdminView[]
  /** 공개에 성공했다. 목록과 단계를 다시 읽는다 */
  onReleased: () => void
  onUnauthenticated: () => void
}

const BLOCKER_TEXT: Record<string, string> = {
  ALREADY_RELEASED: '이미 공개한 단계입니다.',
  ORDER: '앞 번호 단계가 아직 공개되지 않았습니다.',
  EMPTY: '이 단계에 글이 없습니다.',
}

function describeBlocker(blocker: StageBlocker): string {
  return BLOCKER_TEXT[blocker] ?? blocker
}

function formatTime(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('ko-KR')
}

export function StageReleasePanel({
  stages,
  onReleased,
  onUnauthenticated,
}: StageReleasePanelProps) {
  return (
    <section className={styles.editor} aria-labelledby="stage-release-title">
      <h2 id="stage-release-title">단계 공개</h2>
      <p className={styles.guide}>
        단계 공개는 그 단계의 글을 한 번에 게시하고 되돌릴 수 없습니다. 먼저 &lsquo;미리
        검사&rsquo;로 걸리는 것이 없는지 확인하세요. 미리 검사는 아무것도 바꾸지 않습니다.
      </p>
      <ol className={styles.list}>
        {stages.map((stage) => (
          <StageCard
            key={stage.stageKey}
            stage={stage}
            onReleased={onReleased}
            onUnauthenticated={onUnauthenticated}
          />
        ))}
      </ol>
    </section>
  )
}

type ReadinessState = { status: 'idle' } | { status: 'loading' } | StageReadinessResult
type ReleaseState = { status: 'idle' } | { status: 'sending' } | StageReleaseOutcome

interface StageCardProps {
  stage: StageAdminView
  onReleased: () => void
  onUnauthenticated: () => void
}

function StageCard({ stage, onReleased, onUnauthenticated }: StageCardProps) {
  const [readiness, setReadiness] = useState<ReadinessState>({ status: 'idle' })
  const [release, setRelease] = useState<ReleaseState>({ status: 'idle' })

  const titles = new Map<string, string>(
    [...stage.articles, ...stage.placementOnlyArticles].map((article) => [
      article.articleId,
      article.title,
    ]),
  )
  const titleOf = (articleId: string) => titles.get(articleId) ?? articleId
  const released = stage.releasedAt !== null
  const busy = readiness.status === 'loading' || release.status === 'sending'

  const check = async () => {
    setRelease({ status: 'idle' })
    setReadiness({ status: 'loading' })
    const result = await fetchStageReadiness(stage.stageKey)
    if (result.status === 'unauthenticated') {
      onUnauthenticated()
      return
    }
    setReadiness(result)
  }

  const ready = readiness.status === 'loaded' && readiness.readiness.ready
  const releaseArticles = readiness.status === 'loaded' ? readiness.readiness.articles : []

  const confirmAndRelease = async () => {
    if (!ready || busy) return
    const list = releaseArticles.map((article) => `- ${titleOf(article.articleId)}`).join('\n')
    const confirmed = window.confirm(
      `${stage.number}단계 '${stage.name}'를 공개합니다. 아래 글 ${releaseArticles.length}편이 함께 게시되며 되돌릴 수 없습니다.\n\n${list}\n\n공개할까요?`,
    )
    if (!confirmed) return

    setRelease({ status: 'sending' })
    const result = await releaseStage(stage.stageKey, releaseArticles)
    if (result.status === 'unauthenticated') {
      onUnauthenticated()
      return
    }
    setRelease(result)
    if (result.status === 'released') {
      for (const published of result.result.published) {
        applyPublishToUnsentDraft(
          published.articleId,
          published.renamedQuestions,
          result.result.transferredTerms,
        )
      }
      setReadiness({ status: 'idle' })
      onReleased()
    }
    if (
      result.status === 'changed' ||
      result.status === 'checkFailed' ||
      result.status === 'rejected'
    ) {
      // 미리 검사 결과가 낡았다. 다시 하기 전에는 공개 단추를 열지 않는다.
      setReadiness({ status: 'idle' })
    }
  }

  return (
    <li className={styles.item}>
      <div className={styles.head}>
        <h3>
          {stage.number}단계 · {stage.name} <span className={styles.badge}>{stage.levelLabel}</span>
        </h3>
        <span className={styles.hint}>
          {released
            ? `공개됨 · ${formatTime(stage.releasedAt ?? '')}${
                stage.releasedByUserId === null ? '' : ` · 사용자 ${stage.releasedByUserId}`
              }`
            : stage.releasable
              ? '공개 전 · 앞 단계가 공개되어 공개할 수 있습니다'
              : '공개 전 · 앞 단계를 먼저 공개해야 합니다'}
        </span>
      </div>

      <StageArticleList articles={stage.articles} />
      {stage.placementOnlyArticles.length > 0 ? (
        <>
          <p className={styles.hint}>
            초안을 다른 단계로 옮겼지만 아직 다시 게시하지 않아 공개 배치만 이 단계인 글입니다. 옮긴
            단계로 다시 게시하거나 게시를 취소해야 이 단계를 공개할 수 있습니다.
          </p>
          <StageArticleList articles={stage.placementOnlyArticles} />
        </>
      ) : null}

      {released ? null : (
        <div className={styles.controls}>
          <button type="button" onClick={() => void check()} disabled={busy}>
            {stage.number}단계 미리 검사
          </button>
          <button
            type="button"
            onClick={() => void confirmAndRelease()}
            disabled={!ready || busy}
            aria-describedby={ready ? undefined : `stage-release-hint-${stage.stageKey}`}
          >
            {stage.number}단계 공개
          </button>
        </div>
      )}
      {released || ready ? null : (
        <p id={`stage-release-hint-${stage.stageKey}`} className={styles.hint}>
          미리 검사를 통과해야 공개할 수 있습니다.
        </p>
      )}

      <ReadinessView readiness={readiness} titleOf={titleOf} />
      <ReleaseView release={release} stageNumber={stage.number} titleOf={titleOf} />
    </li>
  )
}

function StageArticleList({ articles }: { articles: readonly StageAdminArticle[] }) {
  if (articles.length === 0) return <p className={styles.empty}>이 단계에 글이 없습니다.</p>
  return (
    <ul className={styles.list}>
      {articles.map((article) => (
        <li key={article.articleId}>
          {article.stagePosition}. {article.title} ({article.articleId}) · {article.format} ·{' '}
          {article.status}
          {article.publishedEdition === null ? '' : ` · 공개 판 ${article.publishedEdition}`} · 초안{' '}
          {article.draftVersion}번
        </li>
      ))}
    </ul>
  )
}

function ReadinessView({
  readiness,
  titleOf,
}: {
  readiness: ReadinessState
  titleOf: (articleId: string) => string
}) {
  switch (readiness.status) {
    case 'idle':
      return null
    case 'loading':
      return <p role="status">미리 검사하는 중입니다.</p>
    case 'stageNotFound':
    case 'failed':
      return <p role="alert">{readiness.message}</p>
    case 'unauthenticated':
      return null
    case 'loaded': {
      const { blockers, articles, ready, checkVersion } = readiness.readiness
      const failing = articles.filter((article) => article.failures.length > 0)
      return (
        <div>
          <p role="status" className={styles.notice}>
            {ready
              ? `미리 검사를 모두 통과했습니다. 글 ${articles.length}편을 공개할 수 있습니다.`
              : `미리 검사에 걸린 것이 있습니다. 공개를 막는 것 ${blockers.length}개, 걸린 글 ${failing.length}편입니다.`}{' '}
            검사 규칙 {checkVersion}
          </p>
          {blockers.length > 0 ? (
            <ul className={styles.issues}>
              {blockers.map((blocker) => (
                <li key={blocker}>{describeBlocker(blocker)}</li>
              ))}
            </ul>
          ) : null}
          {failing.map((article) => (
            <PublishCheckResultList
              key={article.articleId}
              title={`${titleOf(article.articleId)} (${article.articleId})`}
              failures={article.failures}
              announce={false}
            />
          ))}
        </div>
      )
    }
  }
}

function ReleaseView({
  release,
  stageNumber,
  titleOf,
}: {
  release: ReleaseState
  stageNumber: number
  titleOf: (articleId: string) => string
}) {
  switch (release.status) {
    case 'idle':
    case 'unauthenticated':
      return null
    case 'sending':
      return <p role="status">단계를 공개하는 중입니다.</p>
    case 'released': {
      const renamed = release.result.published.filter(
        (article) => article.renamedQuestions.length > 0,
      )
      return (
        <div role="status">
          <p className={styles.notice}>
            {stageNumber}단계를 공개했습니다(
            {formatTime(release.result.releasedAt)}). 글 {release.result.published.length}편을
            게시했습니다.
          </p>
          {renamed.map((article) => (
            <p key={article.articleId} className={styles.hint}>
              {titleOf(article.articleId)}: 이 문제는 이미 답한 사용자에게도 새 문제로 보입니다(
              {article.renamedQuestions.map((rename) => `${rename.from} → ${rename.to}`).join(', ')}
              ).
            </p>
          ))}
          {release.result.transferredTerms.length > 0 ? (
            <p className={styles.hint}>
              넘겨받은 용어:{' '}
              {release.result.transferredTerms
                .map((term) => `${term.termId}(${term.fromArticleId} → ${term.toArticleId})`)
                .join(', ')}
            </p>
          ) : null}
        </div>
      )
    }
    case 'checkFailed':
      return (
        <div>
          <p role="alert" className={styles.notice}>
            {release.message} 아무 글도 게시하지 않았습니다.
          </p>
          {release.articles.map((article) => (
            <PublishCheckResultList
              key={article.articleId}
              title={`${titleOf(article.articleId)} (${article.articleId})`}
              failures={article.failures}
              checkVersion={release.checkVersion}
              announce={false}
            />
          ))}
        </div>
      )
    case 'changed':
      return (
        <div role="alert">
          <p className={styles.notice}>
            {release.message} 그사이 글 목록이나 초안이 바뀌었습니다. 미리 검사를 다시 한 뒤
            공개하세요.
          </p>
          {/* 서버가 읽은 공개 집합이다(계약 §11). 어느 글이 바뀌었는지 보이기만 하고 이 목록으로 다시 보내지 않는다. */}
          {release.articles.length > 0 ? (
            <ul className={styles.issues} aria-label="서버가 읽은 공개 글">
              {release.articles.map((article) => (
                <li key={article.articleId}>
                  {titleOf(article.articleId)} ({article.articleId}) · 초안 {article.draftVersion}번
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      )
    case 'rejected':
    case 'stageNotFound':
    case 'failed':
      return (
        <p role="alert" className={styles.notice}>
          {release.message}
        </p>
      )
    case 'invalid':
      return (
        <div role="alert">
          <p className={styles.notice}>{release.message}</p>
          <ul className={styles.issues}>
            {release.fields.map((field) => (
              <li key={`${field.field}:${field.message}`}>
                {field.field}: {field.message}
              </li>
            ))}
          </ul>
        </div>
      )
  }
}

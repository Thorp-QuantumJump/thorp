import { useState } from 'react'
import {
  fetchStageReadiness,
  releaseStage,
  type StageReadinessResult,
  type StageReleaseOutcome,
} from '@/api/learningAdmin'
import type { StageAdminArticle, StageAdminView, StageBlocker } from '@/types/learningAdmin'
import { applyPublishToUnsentDraft } from './adminDraftStorage'
import { formatAdminTime } from './adminTime'
import { PublishCheckResultList } from './PublishCheckResultList'
import { PublishedChanges } from './PublishedChanges'
import styles from './AdminEditor.module.css'

/**
 * 단계 목록과 단계 공개(013 FR-031, contracts/learning-admin-api §9~§11, research R23, T173).
 *
 * <p><b>미리 검사와 공개를 나눈다.</b> 미리 검사는 아무것도 쓰지 않는다. 공개는 미리 검사를 받은 뒤에만 누를 수
 * 있고, 공개될 글 목록을 보인 확인 창에서 확인할 때만 요청한다. 취소하면 요청하지 않는다.
 *
 * <p><b>미리 검사에 걸린 것이 있어도 공개 단추를 막지 않는다.</b> 판정은 서버가 공개 요청에서 다시 한다. 화면이
 * 막으면 서버가 무엇으로 거절하는지(422 검사 항목, 409 막는 것) 운영자가 볼 수 없다(quickstart 8-1·8-3). 대신
 * 확인 창이 걸린 것이 있어 서버가 거절한다고 먼저 알린다.
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
  const [readinessState, setReadinessState] = useState<ReadinessState>({ status: 'idle' })
  const [releaseState, setReleaseState] = useState<ReleaseState>({ status: 'idle' })

  const titles = new Map<string, string>(
    [...stage.articles, ...stage.placementOnlyArticles].map((article) => [
      article.articleId,
      article.title,
    ]),
  )
  const titleOf = (articleId: string) => titles.get(articleId) ?? articleId
  const released = stage.releasedAt !== null
  const busy = readinessState.status === 'loading' || releaseState.status === 'sending'

  const check = async () => {
    setReleaseState({ status: 'idle' })
    setReadinessState({ status: 'loading' })
    const result = await fetchStageReadiness(stage.stageKey)
    if (result.status === 'unauthenticated') {
      onUnauthenticated()
      return
    }
    setReadinessState(result)
  }

  const checked = readinessState.status === 'loaded' ? readinessState.readiness : null

  const confirmAndRelease = async () => {
    if (checked === null || busy) return
    const releaseArticles = checked.articles
    const list = releaseArticles.map((article) => `- ${titleOf(article.articleId)}`).join('\n')
    const warning = checked.ready
      ? ''
      : '미리 검사에 걸린 것이 있어 서버가 공개를 거절할 것입니다. 거절되면 아무 글도 게시되지 않습니다.\n\n'
    const confirmed = window.confirm(
      `${warning}${stage.number}단계 '${stage.name}'를 공개합니다. 아래 글 ${releaseArticles.length}편이 함께 게시되며 되돌릴 수 없습니다.\n\n${list}\n\n공개할까요?`,
    )
    if (!confirmed) return

    setReleaseState({ status: 'sending' })
    const outcome = await releaseStage(stage.stageKey, releaseArticles)
    if (outcome.status === 'unauthenticated') {
      onUnauthenticated()
      return
    }
    setReleaseState(outcome)
    if (outcome.status === 'released') {
      for (const published of outcome.result.published) {
        applyPublishToUnsentDraft(
          published.articleId,
          published.renamedQuestions,
          outcome.result.transferredTerms,
        )
      }
      setReadinessState({ status: 'idle' })
      onReleased()
    }
    if (
      outcome.status === 'changed' ||
      outcome.status === 'checkFailed' ||
      outcome.status === 'rejected'
    ) {
      // 미리 검사 결과가 낡았다. 다시 하기 전에는 공개 단추를 열지 않는다.
      setReadinessState({ status: 'idle' })
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
            ? `공개됨 · ${formatAdminTime(stage.releasedAt ?? '')}${
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
            disabled={checked === null || busy}
            aria-describedby={checked === null ? `stage-release-hint-${stage.stageKey}` : undefined}
          >
            {stage.number}단계 공개
          </button>
        </div>
      )}
      {released || checked !== null ? null : (
        <p id={`stage-release-hint-${stage.stageKey}`} className={styles.hint}>
          미리 검사를 한 뒤에 공개할 수 있습니다.
        </p>
      )}

      <ReadinessView state={readinessState} titleOf={titleOf} />
      <ReleaseView state={releaseState} stageNumber={stage.number} titleOf={titleOf} />
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
  state,
  titleOf,
}: {
  state: ReadinessState
  titleOf: (articleId: string) => string
}) {
  switch (state.status) {
    case 'idle':
      return null
    case 'loading':
      return <p role="status">미리 검사하는 중입니다.</p>
    case 'stageNotFound':
    case 'failed':
      return <p role="alert">{state.message}</p>
    case 'unauthenticated':
      return null
    case 'loaded': {
      const { blockers, articles, ready, checkVersion } = state.readiness
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
  state,
  stageNumber,
  titleOf,
}: {
  state: ReleaseState
  stageNumber: number
  titleOf: (articleId: string) => string
}) {
  switch (state.status) {
    case 'idle':
    case 'unauthenticated':
      return null
    case 'sending':
      return <p role="status">단계를 공개하는 중입니다.</p>
    case 'released': {
      const { published, releasedAt, transferredTerms } = state.result
      return (
        <div role="status">
          <p className={styles.notice}>
            {stageNumber}단계를 공개했습니다({formatAdminTime(releasedAt)}). 글 {published.length}
            편을 게시했습니다.
          </p>
          <PublishedChanges
            renamed={published.map((article) => ({
              title: titleOf(article.articleId),
              articleId: article.articleId,
              renamedQuestions: article.renamedQuestions,
            }))}
            transferredTerms={transferredTerms}
          />
        </div>
      )
    }
    case 'checkFailed':
      return (
        <div>
          <p role="alert" className={styles.notice}>
            {state.message} 아무 글도 게시하지 않았습니다.
          </p>
          {state.articles.map((article) => (
            <PublishCheckResultList
              key={article.articleId}
              title={`${titleOf(article.articleId)} (${article.articleId})`}
              failures={article.failures}
              checkVersion={state.checkVersion}
              announce={false}
            />
          ))}
        </div>
      )
    case 'changed':
      return (
        <div role="alert">
          <p className={styles.notice}>
            {state.message} 그사이 글 목록이나 초안이 바뀌었습니다. 미리 검사를 다시 한 뒤
            공개하세요.
          </p>
          {/* 서버가 읽은 공개 집합이다(계약 §11). 어느 글이 바뀌었는지 보이기만 하고 이 목록으로 다시 보내지 않는다. */}
          {state.articles.length > 0 ? (
            <ul className={styles.issues} aria-label="서버가 읽은 공개 글">
              {state.articles.map((article) => (
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
          {state.message}
        </p>
      )
    case 'invalid':
      return (
        <div role="alert">
          <p className={styles.notice}>{state.message}</p>
          <ul className={styles.issues}>
            {state.fields.map((field) => (
              <li key={`${field.field}:${field.message}`}>
                {field.field}: {field.message}
              </li>
            ))}
          </ul>
        </div>
      )
  }
}

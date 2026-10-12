import { useId, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import { settledGainedNotice } from '@/components/learning/article/completionText'
import { useRestoreFocusAfterConceal } from '@/components/learning/article/useRestoreFocusAfterConceal'
import { useSettledAuth } from '@/components/learning/article/useSettledAuth'
import { stageCountsOf } from '@/components/learning/curriculum/articleProgress'
import { ChangeLog } from '@/components/learning/curriculum/ChangeLog'
import { isFirstStageReleased } from '@/components/learning/curriculum/stages'
import { LEARN_PATH, articlePath } from '@/components/learning/learningPaths'
import { LearningLoadFailure } from '@/components/learning/LearningLoadFailure'
import { BadgeFamilies } from '@/components/learning/me/BadgeFamilies'
import { ContinueCard } from '@/components/learning/me/ContinueCard'
import { stageOfNumber } from '@/components/learning/me/continueLink'
import { GainGrid } from '@/components/learning/me/GainGrid'
import { GuestSample } from '@/components/learning/me/GuestSample'
import { LevelCard } from '@/components/learning/me/LevelCard'
import { QuietNotice } from '@/components/learning/me/QuietNotice'
import { RecordLoadFailure } from '@/components/learning/me/RecordLoadFailure'
import { ReloginNotice } from '@/components/learning/me/ReloginNotice'
import { StageStations } from '@/components/learning/me/StageStations'
import { stageColor } from '@/components/learning/stageColors'
import { AppHeader } from '@/components/navigation/AppHeader'
import { useCurriculum } from '@/hooks/useCurriculum'
import { useLearnerOverview } from '@/hooks/useLearnerOverview'
import type { Curriculum } from '@/types/learning'
import type { LearnerOverview } from '@/types/learningMe'
import styles from './LearningMePage.module.css'

/**
 * '나의 학습' 화면(`/learn/me`, 013 FR-020·FR-024·FR-025·FR-029·FR-030, design-notes §3.3).
 *
 * <p><b>로그인 사용자</b>에게 위에서부터 레벨 카드와 숫자 줄, 단계별 진행, 이어서 읽기, 익힌 용어(누르면 그 글), 갈래별
 * 배지, 변경 기록을 보인다(FR-020 순서). 경험치를 언제 무엇으로 받았는지의 내역은 보이지 않는다. 기록을 받지 못하면
 * '학습 기록을 불러오지 못했습니다'와 '다시 시도'만 보인다. 이번 조회에서 새로 생긴 배지·레벨은 초점을 옮기지 않는
 * 조용한 알림으로 한 번 읽힌다. '학습 초기화'는 이 화면에 두지 않는다(FR-025, 회원정보 화면).
 *
 * <p><b>비로그인 사용자</b>에게는 '로그인하면 학습 기록이 남습니다.' 한 줄·로그인 연결·견본 그림과 그 아래 '나의 학습에
 * 쌓이는 것' 세 칸을 보인다. 숫자·상태는 없다. 로그인이 만료된 동안은 비로그인처럼 기록을 보이지 않고, 한 줄 자리에
 * 재로그인 안내를 둔다(research R33 `expired`).
 *
 * <p>로그인 상태를 다시 확인하는 동안에는 자리를 둔 채 숨기고, 확인이 끝나면 숨기기 전에 있던 초점을 되돌린다
 * (research R33, FR-019).
 *
 * <p><b>첫 단계를 공개하기 전에는 배우기 목록으로 보낸다.</b> 나의 학습은 첫 단계를 공개할 때 켠다(FR-031, research R33).
 * 브라우저 저장소에 학습 기록을 쓰지 않는다.
 */
export function LearningMePage() {
  const location = useLocation()
  const { settled, concealed } = useSettledAuth()
  const { state: curriculumState, retry: retryCurriculum } = useCurriculum()

  const curriculum = curriculumState.status === 'loaded' ? curriculumState.curriculum : null
  const released = curriculum !== null && isFirstStageReleased(curriculum)
  const overview = useLearnerOverview({ auth: settled, enabled: released })
  const recordRef = useRef<HTMLDivElement>(null)
  useRestoreFocusAfterConceal(recordRef, concealed)

  if (curriculum && !released) return <Navigate to={LEARN_PATH} replace />

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="learning-me-title">
        <Link className={styles.back} to={LEARN_PATH} state={location.state}>
          <span aria-hidden="true">←</span> 배우기
        </Link>
        <h1 id="learning-me-title" className={styles.title}>
          나의 학습
        </h1>

        {curriculumState.status === 'loading' ? (
          <p className={styles.quiet} role="status">
            나의 학습을 불러오는 중입니다.
          </p>
        ) : null}
        {curriculumState.status === 'failed' ? (
          <LearningLoadFailure
            message={curriculumState.kind === 'api' ? curriculumState.message : undefined}
            onRetry={retryCurriculum}
          />
        ) : null}

        {curriculum && released ? (
          <div ref={recordRef} className={concealed ? styles.concealed : undefined}>
            {settled.kind === 'anonymous' || settled.kind === 'expired' ? (
              <>
                <div className={styles.guest}>
                  {settled.kind === 'expired' ? <ReloginNotice /> : <GuestSample place="me" />}
                </div>
                <GainGrid />
              </>
            ) : null}
            {settled.kind === 'authenticated' && overview.state.status === 'loading' ? (
              <p className={styles.quiet} role="status">
                나의 학습을 불러오는 중입니다.
              </p>
            ) : null}
            {settled.kind === 'authenticated' && overview.state.status === 'failed' ? (
              <div className={styles.failure}>
                <RecordLoadFailure onRetry={overview.retry} />
              </div>
            ) : null}
            {overview.state.status === 'loaded' ? (
              <Journal
                overview={overview.state.overview}
                curriculum={curriculum}
                linkState={location.state}
              />
            ) : null}
          </div>
        ) : null}
      </main>
    </div>
  )
}

interface JournalProps {
  overview: LearnerOverview
  curriculum: Curriculum
  linkState: unknown
}

/** 로그인 사용자의 '나의 학습'. FR-020 순서대로 둔다. */
function Journal({ overview, curriculum, linkState }: JournalProps) {
  const stationsId = useId()
  const termsId = useId()
  const badgesId = useId()
  const [changeLogOpen, setChangeLogOpen] = useState(false)
  const counts = useMemo(() => stageCountsOf(overview.stages), [overview.stages])
  const titles = useMemo(() => {
    const map = new Map<string, string>()
    for (const stage of curriculum.stages)
      for (const article of stage.articles) map.set(article.articleId, article.title)
    return map
  }, [curriculum.stages])
  const earned = overview.counts.badges

  return (
    <>
      <QuietNotice text={settledGainedNotice(overview.gained)} />
      <LevelCard overview={overview} />

      <section aria-labelledby={stationsId}>
        <h2 id={stationsId} className={styles.section}>
          단계별 진행
        </h2>
        <StageStations stages={curriculum.stages} counts={counts} linkState={linkState} />
      </section>

      {overview.counts.publishedArticles > 0 ? (
        <div className={styles.continue}>
          <ContinueCard
            continueReading={overview.continueReading}
            publishedArticles={overview.counts.publishedArticles}
            linkState={linkState}
          />
        </div>
      ) : null}

      <section aria-labelledby={termsId}>
        <h2 id={termsId} className={styles.section}>
          익힌 용어<small>누르면 그 용어를 설명하는 글로 갑니다</small>
        </h2>
        {overview.masteredTerms.length > 0 ? (
          <ul className={styles.terms}>
            {overview.masteredTerms.map((term) => {
              const stage = stageOfNumber(term.articleNumber)
              const title = titles.get(term.articleId)
              return (
                <li key={term.termId}>
                  <Link
                    className={styles.term}
                    to={articlePath(term.articleId)}
                    state={linkState}
                    style={{
                      ['--stage' as string]: stage ? stageColor(stage) : 'var(--thorp-muted)',
                    }}
                  >
                    <i aria-hidden="true" />
                    <span>
                      <b>{term.label}</b>
                      {term.articleNumber || title ? (
                        <small>{[term.articleNumber, title].filter(Boolean).join(' ')}</small>
                      ) : null}
                    </span>
                  </Link>
                </li>
              )
            })}
          </ul>
        ) : (
          <p className={styles.quiet}>아직 익힌 용어가 없습니다.</p>
        )}
        <p className={styles.termsNote}>
          글의 확인 문제를 하나 이상 맞히면 그 글의 용어를 익힌 것으로 셉니다.
        </p>
      </section>

      <section aria-labelledby={badgesId}>
        <h2 id={badgesId} className={styles.section}>
          배지<small>받은 배지 {earned}개 · 기준은 모두 공개합니다</small>
        </h2>
        <BadgeFamilies badges={overview.badges} />
      </section>

      <div className={styles.log}>
        <ChangeLog
          entries={curriculum.changeLog}
          open={changeLogOpen}
          onToggle={setChangeLogOpen}
        />
      </div>
    </>
  )
}

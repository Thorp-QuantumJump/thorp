import { useEffect, useId, useRef, useState, type ReactNode, type RefObject } from 'react'
import type { QuizQuestion } from '@/types/learning'
import type { QuizSummary, SavedAnswer } from '@/types/learningMe'
import styles from './QuizPanel.module.css'

/**
 * 로그인 모드에서 답 하나를 보낸 결과. 화면이 정답·풀이를 보일지 이것으로 정한다.
 *
 * - `saved`: 서버가 판정하고 저장했다(다시 풀기는 판정만). `choiceId`는 서버가 돌려준 값이다. 다른 탭에서 먼저
 *   답했으면(`alreadyAnswered`) 고른 것과 다를 수 있다
 * - `failed`: 기록되지 않았다. 같은 보기를 다시 보낼 수 있다
 * - `questionNotFound`: 공개 판에 그 문제나 보기가 없다. 운영자가 문제를 바꿨다
 * - `stale`: 다른 곳에서 기록이나 글이 바뀌었다(다른 탭의 학습 초기화, 이미 익힘, 게시 취소). 같은 보기를 다시
 *   보내도 같은 실패다
 */
export type QuizSubmitOutcome =
  | { status: 'saved'; choiceId: string; correct: boolean; alreadyAnswered?: boolean }
  | { status: 'failed' }
  | { status: 'questionNotFound' }
  | { status: 'stale' }

type GuestQuizProps = {
  questions: readonly QuizQuestion[]
  /** 비로그인. 답을 어디에도 보내거나 남기지 않는다(013 FR-022, SC-009) */
  mode: 'guest'
}

type RecordQuizProps = {
  questions: readonly QuizQuestion[]
  /** 로그인. 첫 답을 저장한 뒤에만 맞았는지와 정답·풀이를 보인다(013 FR-022) */
  mode: 'record'
  /** 저장된 첫 답. 공개 판에 있는 문제의 것만 온다 */
  answers: readonly SavedAnswer[]
  summary: QuizSummary
  /** 이 글에 익힐 용어가 있는가. 없으면 다시 풀기 안내가 용어를 말하지 않는다(spec Edge Cases) */
  hasTerms: boolean
  /** 첫 답을 보낸다. 성공하면 부르는 쪽이 `answers`와 `summary`를 바꾼다 */
  onAnswer: (questionId: string, choiceId: string) => Promise<QuizSubmitOutcome>
  /** 다시 풀기 답을 보낸다. 저장하지 않으며 성공하면 부르는 쪽이 `summary`를 바꾼다 */
  onRetry: (questionId: string, choiceId: string) => Promise<QuizSubmitOutcome>
  /** 문제나 기록이 바뀌었을 때 글을 다시 불러온다 */
  onReload: () => void
}

type QuizPanelProps = GuestQuizProps | RecordQuizProps

/**
 * 글 끝 확인 문제(013 FR-022, 목업 '글 끝').
 *
 * <p><b>비로그인 모드는 화면 안의 상태뿐이다.</b> 답하면 바로 맞았는지와 정답·풀이를 보이고 다음 문제로
 * 넘어간다. 요청도 브라우저 저장도 하지 않으므로 글을 다시 열면 처음 상태다(SC-009). 정답과 풀이는 공개
 * 응답에 실려 오지만 답하기 전에는 보이지 않는다(R22). 자기 카드 틀과 h2 제목을 갖는다.
 *
 * <p><b>로그인 모드는 저장한 뒤에 공개한다.</b> 완료 카드 안에 틀 없이 h3 제목으로 놓인다(목업 '글 끝'). 고른 보기를
 * 잠그고 첫 답을 보낸 뒤, 저장 응답을 받아야 맞았는지와 정답·풀이를 보인다. 실패하면 정답·풀이 없이
 * '기록되지 않았습니다'와 같은 보기를 다시 보내는 '다시 시도'만 둔다. 다시 들어오면 저장된 첫 답의 결과와
 * 풀이로 보인다. 첫 답에서 하나도 맞히지 못한 글은 '다시 풀기'로 익힘을 받을 수 있고 경험치는 없다.
 *
 * <p><b>한 문제씩, 한 번만 답한다.</b> 답한 문제와 지금 답할 문제까지만 보이고, 답한 문제는 단추 대신 고른 답·
 * 맞음·정답·틀린 보기를 글자로 단 정적 목록이 된다. 고른 보기는 바꿀 수 없다. 모두 답하면 맞힌 개수를 보인다.
 *
 * <p><b>틀린 보기에는 모두 '틀린 보기'를 붙인다.</b> 틀린 보기는 금지 표현 검사를 하지 않는 자리라 그 표시가
 * 바로잡는 장치다(FR-017, SC-002). 고르지 않은 틀린 보기도 빠뜨리지 않는다. 확인 문제의 숫자는 가상의
 * 숫자라 머리에 '가상의 예'를 붙인다(FR-005, FR-026). 틀린 보기는 빨간색이 아니라 글자로 보인다
 * (design-notes §4).
 */
export function QuizPanel(props: QuizPanelProps) {
  if (props.questions.length === 0) return null
  return props.mode === 'guest' ? <GuestQuiz {...props} /> : <RecordQuiz {...props} />
}

/** 답하면 초점을 그 문제의 풀이로 옮긴다. 고른 단추가 정적 목록으로 바뀌어 초점이 사라지지 않게 한다. */
function useFocusExplanation(key: string | null): RefObject<HTMLParagraphElement | null> {
  const ref = useRef<HTMLParagraphElement>(null)
  useEffect(() => {
    if (key !== null) ref.current?.focus()
  }, [key])
  return ref
}

/**
 * 확인 문제의 머리. 비로그인 글 끝에서는 카드 틀과 h2, 로그인 완료 카드 안에서는 틀 없이 h3다(카드 제목이
 * h2라서 같은 층에 두지 않는다).
 */
function PanelFrame({ embedded, children }: { embedded: boolean; children: ReactNode }) {
  const titleId = useId()
  const Heading = embedded ? 'h3' : 'h2'
  return (
    <section className={embedded ? styles.embedded : styles.panel} aria-labelledby={titleId}>
      <div className={styles.head}>
        <Heading id={titleId} className={styles.title}>
          확인 문제
        </Heading>
        <span className={styles.hypothetical}>가상의 예</span>
      </div>
      {children}
    </section>
  )
}

function GuestQuiz({ questions }: GuestQuizProps) {
  const [answers, setAnswers] = useState<readonly string[]>([])
  const [justAnswered, setJustAnswered] = useState<number | null>(null)
  // 고른 답과 맞았는지를 풀이 안에서 한 번만 읽게 한다(목업 '답한 뒤 초점은 풀이로만').
  const explanationRef = useFocusExplanation(justAnswered === null ? null : String(justAnswered))

  const shown = questions.slice(0, Math.min(answers.length + 1, questions.length))
  const correctCount = answers.filter(
    (choiceId, index) => choiceId === questions[index].correctChoiceId,
  ).length

  const choose = (index: number, choiceId: string) => {
    setAnswers((previous) => (previous.length === index ? [...previous, choiceId] : previous))
    setJustAnswered(index)
  }

  return (
    <PanelFrame embedded={false}>
      {shown.map((question, index) => (
        <QuizItem
          key={question.questionId}
          number={index + 1}
          question={question}
          view={
            answers[index] === undefined
              ? { kind: 'open', onChoose: (choiceId) => choose(index, choiceId) }
              : { kind: 'answered', choiceId: answers[index] }
          }
          explanationRef={index === justAnswered ? explanationRef : undefined}
        />
      ))}
      {answers.length === questions.length ? (
        <p className={styles.score}>
          {questions.length}문제 중 {correctCount}개를 맞혔습니다.
        </p>
      ) : null}
    </PanelFrame>
  )
}

/** 보낸 뒤 결과를 기다리거나 실패한 답. 고른 보기는 잠겨 있다. */
type Pending = {
  questionId: string
  choiceId: string
  /** 첫 답인가, 다시 풀기인가 */
  round: 'first' | 'retry'
  phase: 'saving' | 'failed' | 'questionNotFound' | 'stale'
}

function RecordQuiz({
  questions,
  answers,
  summary,
  hasTerms,
  onAnswer,
  onRetry,
  onReload,
}: RecordQuizProps) {
  const [pending, setPending] = useState<Pending | null>(null)
  /** 다시 풀기 중이면 이번 다시 풀기에서 답한 결과. 저장하지 않으므로 화면 안에만 둔다 */
  const [retryAnswers, setRetryAnswers] = useState<
    readonly { questionId: string; choiceId: string; correct: boolean }[] | null
  >(null)
  /** 다른 화면에서 먼저 답해 고른 것과 다른 첫 답이 돌아온 문제 */
  const [answeredElsewhere, setAnsweredElsewhere] = useState<ReadonlySet<string>>(new Set())
  const [justAnswered, setJustAnswered] = useState<string | null>(null)
  const explanationRef = useFocusExplanation(justAnswered)
  const pendingMessageRef = useRef<HTMLSpanElement>(null)
  const pendingActionRef = useRef<HTMLButtonElement>(null)
  const retryTitleRef = useRef<HTMLHeadingElement>(null)

  // 보기를 누르면 그 단추가 잠긴 목록으로 바뀌어 초점이 빠진다. 기록하는 동안에는 그 줄로, 실패하면 그 줄의
  // 단추로 옮긴다. 저장되면 위의 풀이로 옮긴다(FR-019, SC-008).
  useEffect(() => {
    if (!pending) return
    if (pending.phase === 'saving') pendingMessageRef.current?.focus()
    else pendingActionRef.current?.focus()
  }, [pending])

  const saved = new Map(answers.map((answer) => [answer.questionId, answer]))
  // 답한 문제는 모두 보이고, 답하지 않은 문제는 처음 만나는 하나만 연다. 운영자가 앞에 문제를 더해도 뒤의 답한
  // 문제의 결과와 풀이가 가려지지 않는다(US6 시나리오 3).
  const firstOpen = questions.findIndex((question) => !saved.has(question.questionId))
  const shown = questions
    .map((question, index) => ({ question, index }))
    .filter(({ question, index }) => saved.has(question.questionId) || index === firstOpen)

  const send = async (
    round: Pending['round'],
    questionId: string,
    choiceId: string,
    submit: RecordQuizProps['onAnswer'],
  ) => {
    setPending({ questionId, choiceId, round, phase: 'saving' })
    const outcome = await submit(questionId, choiceId)
    if (outcome.status !== 'saved') {
      setPending({ questionId, choiceId, round, phase: outcome.status })
      return
    }
    setPending(null)
    if (round === 'retry') {
      setRetryAnswers((previous) => [
        ...(previous ?? []),
        { questionId, choiceId: outcome.choiceId, correct: outcome.correct },
      ])
    } else if (outcome.alreadyAnswered && outcome.choiceId !== choiceId) {
      setAnsweredElsewhere((previous) => new Set(previous).add(questionId))
    }
    setJustAnswered(`${round}:${questionId}`)
  }

  const answer = (questionId: string, choiceId: string) =>
    void send('first', questionId, choiceId, onAnswer)
  const retry = (questionId: string, choiceId: string) =>
    void send('retry', questionId, choiceId, onRetry)

  const pendingLine = (round: Pending['round'], questionId: string) =>
    pending?.round === round && pending.questionId === questionId ? (
      <PendingLine
        pending={pending}
        messageRef={pendingMessageRef}
        actionRef={pendingActionRef}
        onResend={() =>
          (round === 'first' ? answer : retry)(pending.questionId, pending.choiceId)
        }
        onReload={onReload}
      />
    ) : null

  const retryDone = retryAnswers ? retryAnswers.some((item) => item.correct) : false
  // 맞히면 거기서 끝난다. 남은 문제는 더 보이지 않는다.
  const retryShown = retryAnswers
    ? questions.slice(0, Math.min(retryAnswers.length + (retryDone ? 0 : 1), questions.length))
    : []
  const canStartRetry =
    summary.allAnswered && summary.retryAvailable && !summary.mastered && pending === null

  return (
    <PanelFrame embedded>
      {shown.map(({ question, index }) => {
        const answered = saved.get(question.questionId)
        const locked = pending?.round === 'first' && pending.questionId === question.questionId
        return (
          <QuizItem
            key={question.questionId}
            number={index + 1}
            question={question}
            firstAnswer
            view={
              answered
                ? { kind: 'answered', choiceId: answered.choiceId }
                : locked
                  ? { kind: 'locked', choiceId: pending.choiceId }
                  : { kind: 'open', onChoose: (choiceId) => answer(question.questionId, choiceId) }
            }
            explanationRef={
              justAnswered === `first:${question.questionId}` ? explanationRef : undefined
            }
          >
            {answered && answeredElsewhere.has(question.questionId) ? (
              <p className={styles.note}>다른 화면에서 먼저 고른 답이 첫 답으로 기록되어 있습니다.</p>
            ) : null}
            {answered ? null : pendingLine('first', question.questionId)}
          </QuizItem>
        )
      })}

      {summary.allAnswered ? (
        <p className={styles.score}>
          {summary.total}문제 중 {summary.correct}개를 맞혔습니다.
        </p>
      ) : null}

      {retryAnswers !== null ? (
        <div className={styles.retry}>
          <h4 ref={retryTitleRef} className={styles.retryTitle} tabIndex={-1}>
            다시 풀기
          </h4>
          <p className={styles.hint}>
            {hasTerms
              ? '다시 풀기에서 맞히면 이 글의 용어가 익힘이 됩니다. 경험치는 더하지 않습니다.'
              : '다시 풀기는 경험치를 더하지 않습니다.'}
          </p>
          {retryShown.map((question, index) => {
            const done = retryAnswers[index]
            const locked =
              !done && pending?.round === 'retry' && pending.questionId === question.questionId
            return (
              <QuizItem
                key={question.questionId}
                number={index + 1}
                question={question}
                view={
                  done
                    ? { kind: 'answered', choiceId: done.choiceId }
                    : locked
                      ? { kind: 'locked', choiceId: pending.choiceId }
                      : {
                          kind: 'open',
                          onChoose: (choiceId) => retry(question.questionId, choiceId),
                        }
                }
                explanationRef={
                  justAnswered === `retry:${question.questionId}` ? explanationRef : undefined
                }
              >
                {done ? null : pendingLine('retry', question.questionId)}
              </QuizItem>
            )
          })}
          {retryDone ? (
            <p className={styles.score}>
              {hasTerms ? '익힘이 되었습니다.' : '다시 풀기에서 맞혔습니다.'}
            </p>
          ) : null}
        </div>
      ) : null}

      {canStartRetry && (retryAnswers === null || retryAnswers.length === questions.length) ? (
        <button
          type="button"
          className={styles.retryButton}
          onClick={() => {
            setRetryAnswers([])
            setJustAnswered(null)
            // 누른 단추가 사라지므로 다시 풀기 제목으로 초점을 옮긴다.
            requestAnimationFrame(() => retryTitleRef.current?.focus())
          }}
        >
          다시 풀기
        </button>
      ) : null}
    </PanelFrame>
  )
}

const PENDING_MESSAGE: Record<Pending['phase'], string> = {
  saving: '기록하는 중입니다.',
  failed: '기록되지 않았습니다.',
  questionNotFound: '이 문제가 바뀌었습니다. 글을 다시 불러와 주세요.',
  stale: '학습 기록이 바뀌었습니다. 글을 다시 불러와 주세요.',
}

/**
 * 정답·풀이 대신 놓이는 한 줄(FR-022). 기록하는 동안에는 이 줄에, 실패하면 단추에 초점이 온다. 단추는 이 줄의
 * 문장을 설명으로 달아 화면 낭독기가 무엇이 실패했는지 함께 읽는다.
 */
function PendingLine({
  pending,
  messageRef,
  actionRef,
  onResend,
  onReload,
}: {
  pending: Pending
  messageRef: RefObject<HTMLSpanElement | null>
  actionRef: RefObject<HTMLButtonElement | null>
  onResend: () => void
  onReload: () => void
}) {
  const messageId = useId()
  return (
    <p className={styles.pending}>
      <span id={messageId} ref={messageRef} className={styles.pendingMessage} tabIndex={-1}>
        {PENDING_MESSAGE[pending.phase]}
      </span>
      {pending.phase === 'saving' ? null : (
        <button
          ref={actionRef}
          type="button"
          className={styles.inlineButton}
          aria-describedby={messageId}
          onClick={pending.phase === 'failed' ? onResend : onReload}
        >
          {pending.phase === 'failed' ? '다시 시도' : '다시 불러오기'}
        </button>
      )}
    </p>
  )
}

/**
 * 문제 하나의 보기 자리.
 *
 * - `open`: 고를 수 있다
 * - `locked`: 고른 보기를 보냈고 결과를 기다리거나 실패했다. 고른 보기만 표시하고 정답은 보이지 않는다
 * - `answered`: 결과를 받았다. 고른 답·맞음·정답·틀린 보기와 풀이를 보인다
 */
type ItemView =
  | { kind: 'open'; onChoose: (choiceId: string) => void }
  | { kind: 'locked'; choiceId: string }
  | { kind: 'answered'; choiceId: string }

interface QuizItemProps {
  number: number
  question: QuizQuestion
  view: ItemView
  /** 로그인 사용자의 첫 답 자리다. 문제 번호 옆에 '첫 답'을 붙인다(목업 '글 끝') */
  firstAnswer?: boolean
  explanationRef?: RefObject<HTMLParagraphElement | null>
  /** 보기 아래 줄(기록 중·실패·먼저 답한 첫 답 안내) */
  children?: ReactNode
}

function QuizItem({ number, question, view, firstAnswer, explanationRef, children }: QuizItemProps) {
  const promptId = useId()
  const hintId = useId()

  return (
    <div className={styles.question}>
      <p id={promptId} className={styles.prompt}>
        <span className={styles.number}>문제 {number}</span>
        {firstAnswer ? <span className={styles.firstAnswer}>첫 답</span> : null}
        <span>{question.prompt}</span>
      </p>

      {view.kind === 'open' ? (
        <>
          {/* 한 번만 답하는 선택이라 라디오가 아니라 질문을 이름으로 단 묶음 안의 단추로 둔다. */}
          <div
            className={styles.choices}
            role="group"
            aria-labelledby={promptId}
            aria-describedby={hintId}
          >
            {question.choices.map((choice, index) => (
              <button
                key={choice.choiceId}
                type="button"
                className={styles.choice}
                onClick={() => view.onChoose(choice.choiceId)}
              >
                <span className={styles.choiceNumber} aria-hidden="true">
                  {index + 1}
                </span>
                <span className={styles.choiceText}>{choice.text}</span>
              </button>
            ))}
          </div>
          <p id={hintId} className={styles.hint}>
            고르면 바로 답이 됩니다.
          </p>
        </>
      ) : null}

      {view.kind === 'locked' ? (
        // 저장 응답 전에는 맞았는지를 드러내지 않는다. 고른 보기만 표시하고 바꿀 수 없다(FR-022).
        <ol className={styles.answered}>
          {question.choices.map((choice, index) => {
            const picked = choice.choiceId === view.choiceId
            return (
              <li key={choice.choiceId} className={styles.answer} data-picked={picked || undefined}>
                <span className={styles.choiceNumber} aria-hidden="true">
                  {index + 1}
                </span>
                <span className={styles.choiceText}>
                  <span className="sr-only">{index + 1}. </span>
                  {choice.text}
                </span>
                {picked ? (
                  <span className={styles.tags}>
                    <span className={styles.tagPicked}>고른 답</span>
                  </span>
                ) : null}
              </li>
            )
          })}
        </ol>
      ) : null}

      {view.kind === 'answered' ? (
        <AnsweredChoices question={question} chosen={view.choiceId} explanationRef={explanationRef} />
      ) : null}

      {children}
    </div>
  )
}

function AnsweredChoices({
  question,
  chosen,
  explanationRef,
}: {
  question: QuizQuestion
  chosen: string
  explanationRef?: RefObject<HTMLParagraphElement | null>
}) {
  const pickedIndex = question.choices.findIndex((choice) => choice.choiceId === chosen)
  const pickedCorrect = chosen === question.correctChoiceId
  return (
    <>
      <ol className={styles.answered}>
        {question.choices.map((choice, index) => {
          const picked = choice.choiceId === chosen
          const correct = choice.choiceId === question.correctChoiceId
          return (
            <li
              key={choice.choiceId}
              className={styles.answer}
              data-correct={correct || undefined}
              data-picked={picked || undefined}
            >
              <span className={styles.choiceNumber} aria-hidden="true">
                {index + 1}
              </span>
              <span className={styles.choiceText}>
                <span className="sr-only">{index + 1}. </span>
                {choice.text}
              </span>
              <span className={styles.tags}>
                {picked ? <span className={styles.tagPicked}>고른 답</span> : null}
                {correct ? (
                  <span className={styles.tagCorrect}>{picked ? '맞음' : '정답'}</span>
                ) : (
                  <span className={styles.tagWrong}>틀린 보기</span>
                )}
              </span>
            </li>
          )
        })}
      </ol>
      <p ref={explanationRef} className={styles.explanation} tabIndex={-1}>
        <span className="sr-only">
          고른 답 {pickedIndex + 1}번, {pickedCorrect ? '맞음' : '틀린 보기'}.{' '}
        </span>
        {/* 이름표와 풀이 사이에 실제 띄어쓰기를 둔다. 여백만 두면 읽기·복사에서 '풀이합성…'처럼 붙는다. */}
        <b>풀이</b> {question.explanation}
      </p>
    </>
  )
}

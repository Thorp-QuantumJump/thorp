import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import type { QuizQuestion } from '@/types/learning'
import styles from './QuizPanel.module.css'

interface QuizPanelProps {
  questions: readonly QuizQuestion[]
  /**
   * 기록 방식. 비로그인은 답을 어디에도 보내거나 남기지 않는다. 로그인 모드(첫 답 저장)는 B12가 더한다
   * (013 FR-022, research R22).
   */
  mode: 'guest'
}

/**
 * 글 끝 확인 문제(013 FR-022, 목업 '글 끝').
 *
 * <p><b>비로그인 모드는 화면 안의 상태뿐이다.</b> 답하면 바로 맞았는지와 정답·풀이를 보이고 다음 문제로
 * 넘어간다. 요청도 브라우저 저장도 하지 않으므로 글을 다시 열면 처음 상태다(SC-009). 정답과 풀이는 공개
 * 응답에 실려 오지만 답하기 전에는 보이지 않는다(R22).
 *
 * <p><b>한 문제씩, 한 번만 답한다.</b> 답한 문제와 지금 답할 문제까지만 보이고, 답한 문제는 단추 대신 고른 답·
 * 맞음·정답·틀린 보기를 글자로 단 정적 목록이 된다. 고른 보기는 바꿀 수 없다. 모두 답하면 맞힌 개수를 보인다.
 *
 * <p><b>틀린 보기에는 모두 '틀린 보기'를 붙인다.</b> 틀린 보기는 금지 표현 검사를 하지 않는 자리라 그 표시가
 * 바로잡는 장치다(FR-017, SC-002). 고르지 않은 틀린 보기도 빠뜨리지 않는다. 확인 문제의 숫자는 가상의
 * 숫자라 머리에 '가상의 예'를 붙인다(FR-005, FR-026). 틀린 보기는 빨간색이 아니라 글자로 보인다
 * (design-notes §4).
 */
export function QuizPanel({ questions }: QuizPanelProps) {
  const titleId = useId()
  const [answers, setAnswers] = useState<readonly string[]>([])
  const [justAnswered, setJustAnswered] = useState<number | null>(null)
  const explanationRef = useRef<HTMLParagraphElement>(null)

  // 답하면 초점을 그 문제의 풀이로 옮긴다. 고른 단추가 정적 목록으로 바뀌어 초점이 사라지지 않게 하고,
  // 고른 답과 맞았는지를 풀이 안에서 한 번만 읽게 한다(목업 '답한 뒤 초점은 풀이로만').
  useEffect(() => {
    if (justAnswered !== null) explanationRef.current?.focus()
  }, [justAnswered])

  if (questions.length === 0) return null

  const shown = questions.slice(0, Math.min(answers.length + 1, questions.length))
  const correctCount = answers.filter(
    (choiceId, index) => choiceId === questions[index].correctChoiceId,
  ).length

  const choose = (index: number, choiceId: string) => {
    setAnswers((previous) => (previous.length === index ? [...previous, choiceId] : previous))
    setJustAnswered(index)
  }

  return (
    <section className={styles.panel} aria-labelledby={titleId}>
      <div className={styles.head}>
        <h2 id={titleId} className={styles.title}>
          확인 문제
        </h2>
        <span className={styles.hypothetical}>가상의 예</span>
      </div>
      {shown.map((question, index) => (
        <QuizItem
          key={question.questionId}
          number={index + 1}
          question={question}
          chosen={answers[index]}
          onChoose={(choiceId) => choose(index, choiceId)}
          explanationRef={index === justAnswered ? explanationRef : undefined}
        />
      ))}
      {answers.length === questions.length ? (
        <p className={styles.score}>
          {questions.length}문제 중 {correctCount}개를 맞혔습니다.
        </p>
      ) : null}
    </section>
  )
}

interface QuizItemProps {
  number: number
  question: QuizQuestion
  /** 고른 보기. 아직 답하지 않았으면 없다 */
  chosen: string | undefined
  onChoose: (choiceId: string) => void
  explanationRef?: RefObject<HTMLParagraphElement | null>
}

function QuizItem({ number, question, chosen, onChoose, explanationRef }: QuizItemProps) {
  const promptId = useId()
  const hintId = useId()
  const pickedIndex = question.choices.findIndex((choice) => choice.choiceId === chosen)
  const pickedCorrect = chosen === question.correctChoiceId

  return (
    <div className={styles.question}>
      <p id={promptId} className={styles.prompt}>
        <span className={styles.number}>문제 {number}</span>
        <span>{question.prompt}</span>
      </p>

      {chosen === undefined ? (
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
                onClick={() => onChoose(choice.choiceId)}
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
      ) : (
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
      )}
    </div>
  )
}

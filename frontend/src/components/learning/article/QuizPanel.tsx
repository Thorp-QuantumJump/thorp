import { useId, useRef, useState } from 'react'
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
 * 글 끝 확인 문제(013 FR-022).
 *
 * <p><b>비로그인 모드는 화면 안의 상태뿐이다.</b> 답하면 바로 맞았는지와 정답·풀이를 보이고 다음 문제로
 * 넘어간다. 요청도 브라우저 저장도 하지 않으므로 글을 다시 열면 처음 상태다(SC-009). 정답과 풀이는 공개
 * 응답에 실려 오지만 답하기 전에는 보이지 않는다(R22).
 *
 * <p><b>한 문제에 한 번만 답한다.</b> 고른 보기는 바꿀 수 없다. 모두 답하면 맞힌 개수를 보인다.
 *
 * <p>틀린 보기는 빨간색이 아니라 '틀린 보기' 글자로 보인다. 빨강·파랑은 값의 오르내림을 뜻한다
 * (design-notes §4).
 */
export function QuizPanel({ questions }: QuizPanelProps) {
  const titleId = useId()
  const [current, setCurrent] = useState(0)
  const [answers, setAnswers] = useState<ReadonlyArray<string | undefined>>([])
  const promptRef = useRef<HTMLParagraphElement>(null)

  if (questions.length === 0) return null

  const question = questions[current]
  const chosen = answers[current]
  const answeredAll = answers.filter((answer) => answer !== undefined).length === questions.length
  const correctCount = questions.filter(
    (item, index) => answers[index] === item.correctChoiceId,
  ).length

  const choose = (choiceId: string) => {
    if (chosen !== undefined) return
    setAnswers((previous) => {
      const next = [...previous]
      next[current] = choiceId
      return next
    })
  }

  const goNext = () => {
    setCurrent((index) => index + 1)
    // 다음 문제 질문으로 초점을 옮겨 스크린 리더가 새 질문부터 읽게 한다.
    requestAnimationFrame(() => promptRef.current?.focus())
  }

  return (
    <section className={styles.panel} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.title}>
        확인 문제
      </h2>
      <p className={styles.progress}>
        {questions.length}문제 중 {current + 1}번째
      </p>
      <p ref={promptRef} className={styles.prompt} tabIndex={-1}>
        {question.prompt}
      </p>
      <ul className={styles.choices}>
        {question.choices.map((choice) => {
          const isChosen = chosen === choice.choiceId
          const isCorrect = choice.choiceId === question.correctChoiceId
          const revealed = chosen !== undefined
          return (
            <li key={choice.choiceId}>
              <button
                type="button"
                className={styles.choice}
                data-chosen={isChosen || undefined}
                data-correct={(revealed && isCorrect) || undefined}
                aria-pressed={isChosen}
                disabled={revealed}
                onClick={() => choose(choice.choiceId)}
              >
                <span>{choice.text}</span>
                {revealed && isCorrect ? <span className={styles.mark}>정답</span> : null}
                {revealed && isChosen && !isCorrect ? (
                  <span className={styles.mark}>틀린 보기</span>
                ) : null}
              </button>
            </li>
          )
        })}
      </ul>

      <div className={styles.result} role="status">
        {chosen !== undefined ? (
          <>
            <p className={styles.verdict}>
              {chosen === question.correctChoiceId ? '맞았습니다.' : '틀렸습니다.'}
            </p>
            <p className={styles.explanation}>{question.explanation}</p>
            {answeredAll ? (
              <p className={styles.score}>
                {questions.length}문제 중 {correctCount}개
              </p>
            ) : null}
          </>
        ) : null}
      </div>

      {chosen !== undefined && current < questions.length - 1 ? (
        <button type="button" className={styles.next} onClick={goNext}>
          다음 문제
        </button>
      ) : null}
    </section>
  )
}
